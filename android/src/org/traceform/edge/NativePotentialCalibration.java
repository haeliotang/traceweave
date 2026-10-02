package org.traceform.edge;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

/** Training-only empirical drift projection at candidate centers. */
final class NativePotentialCalibration {
    static JSONObject run(SQLiteDatabase snapshot, ModelEngine model, double[] candidateCenters, long trainingEnd, String sourceDigest) throws Exception {
        if (model.tokens.length < 2) return new JSONObject().put("available", false).put("reason", "fewer_than_two_centers");
        double[][] centers = new double[model.tokens.length][model.names.length];
        for (int node = 0; node < centers.length; node++)
            System.arraycopy(candidateCenters, node * model.names.length, centers[node], 0, model.names.length);
        EmpiricalGraphPotential fit = new EmpiricalGraphPotential(centers, 1.0, 1.0);
        JSONObject previous = null;
        int pairs = 0;
        try (Cursor rows = snapshot.rawQuery("SELECT payload FROM frames WHERE ts<=? ORDER BY ts", new String[]{Long.toString(trainingEnd)})) {
            while (rows.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("training potential cancelled");
                JSONObject frame = new JSONObject(rows.getString(0));
                if (!model.modelVersion.equals(frame.getString("model_version"))
                        || !model.modelDigest.equals(frame.getString("model_sha256"))
                        || !model.normalizerDigest.equals(frame.getString("normalizer_sha256"))) {
                    previous = null; continue;
                }
                if (continuous(previous, frame)) {
                    fit.add(vector(previous), vector(frame), (frame.getLong("timestamp_epoch_ms") - previous.getLong("timestamp_epoch_ms")) / 1000.0);
                    pairs++;
                }
                previous = frame;
            }
        }
        if (pairs < 2) return new JSONObject().put("available", false).put("reason", "fewer_than_two_continuous_training_pairs");
        EmpiricalGraphPotential.Result potential = fit.project();
        Integer[] order = new Integer[model.tokens.length];
        for (int node = 0; node < order.length; node++) order[node] = node;
        java.util.Arrays.sort(order, java.util.Comparator.<Integer>comparingDouble(node -> potential.energy[node]).thenComparing(node -> model.tokens[node]));
        JSONArray ordering = new JSONArray();
        for (int node : order) ordering.put(model.tokens[node]);
        return new JSONObject().put("available", true).put("protocol", "traceform.graph-potential.v1")
            .put("centers", new JSONArray(potential.centers)).put("empirical_drift", new JSONArray(potential.drift))
            .put("relative_energy", new JSONArray(potential.energy)).put("energy_ordered_tokens", ordering)
            .put("bandwidth", 1.0).put("mobility", 1.0).put("gauge_anchor", 0)
            .put("fit_pair_count", potential.pairs).put("training_end_epoch_ms", trainingEnd)
            .put("source_sha256", sourceDigest).put("parent_model_sha256", model.modelDigest)
            .put("normalizer_sha256", model.normalizerDigest)
            .put("edge_residual_rms", potential.residualRms)
            .put("conservative_fraction", Double.isNaN(potential.conservativeFraction) ? JSONObject.NULL : potential.conservativeFraction)
            .put("scope", "training_empirical_drift_projection_and_energy_order_not_thermodynamic_equilibrium_or_bifurcation");
    }
    private static boolean continuous(JSONObject previous, JSONObject frame) throws Exception {
        if (previous == null || !frame.getBoolean("continuous_from_previous")) return false;
        for (String field : new String[]{"model_version", "model_sha256", "normalizer_sha256", "session_id", "source_mode"})
            if (!frame.getString(field).equals(previous.getString(field))) return false;
        long gap;
        try { gap = Math.subtractExact(frame.getLong("timestamp_epoch_ms"), previous.getLong("timestamp_epoch_ms")); }
        catch (ArithmeticException overflow) { return false; }
        double elapsed = (frame.getLong("elapsed_realtime_nanos") - (double) previous.getLong("elapsed_realtime_nanos")) / 1e6;
        return gap > 0 && gap <= 300000 && elapsed > 0 && Math.abs(gap - elapsed) <= 1000;
    }
    static void validate(JSONObject potential, ModelEngine model, JSONObject report) throws Exception {
        if (Boolean.FALSE.equals(potential.opt("available"))) {
            if (!potential.getString("reason").equals("fewer_than_two_centers")
                    && !potential.getString("reason").equals("fewer_than_two_continuous_training_pairs"))
                throw new IllegalArgumentException("known unavailable potential reason required");
            return;
        }
        int states = model.tokens.length, dimension = model.names.length;
        if (!Boolean.TRUE.equals(potential.opt("available")) || !"traceform.graph-potential.v1".equals(potential.getString("protocol"))
                || !model.modelDigest.equals(potential.getString("parent_model_sha256"))
                || !model.normalizerDigest.equals(potential.getString("normalizer_sha256"))
                || !report.getString("source_sha256").equals(potential.getString("source_sha256"))
                || report.getLong("training_end_epoch_ms") != potential.getLong("training_end_epoch_ms")
                || potential.getInt("fit_pair_count") < 2 || potential.getInt("fit_pair_count") >= report.getInt("training_records")
                || potential.getDouble("bandwidth") != 1.0 || potential.getDouble("mobility") != 1.0
                || potential.getInt("gauge_anchor") != 0)
            throw new IllegalArgumentException("training source-bound potential required");
        JSONArray centers = potential.getJSONArray("centers"), drift = potential.getJSONArray("empirical_drift"), energy = potential.getJSONArray("relative_energy");
        JSONArray order = potential.getJSONArray("energy_ordered_tokens"), flat = report.getJSONArray("candidate_centers");
        if (centers.length() != states || drift.length() != states || energy.length() != states || order.length() != states)
            throw new IllegalArgumentException("matching potential topology required");
        java.util.Map<String, Integer> nodes = new java.util.HashMap<>();
        for (int node = 0; node < states; node++) {
            nodes.put(model.tokens[node], node);
            if (centers.getJSONArray(node).length() != dimension || drift.getJSONArray(node).length() != dimension)
                throw new IllegalArgumentException("matching potential coordinate dimension required");
            for (int index = 0; index < dimension; index++) {
                if (number(centers.getJSONArray(node), index) != flat.getDouble(node * dimension + index))
                    throw new IllegalArgumentException("potential must bind candidate centers");
                number(drift.getJSONArray(node), index);
            }
            number(energy, node);
        }
        if (energy.getDouble(0) != 0 || !(potential.get("edge_residual_rms") instanceof Number)
                || !Double.isFinite(potential.getDouble("edge_residual_rms")) || potential.getDouble("edge_residual_rms") < 0)
            throw new IllegalArgumentException("finite gauge and residual required");
        if (!potential.isNull("conservative_fraction")) {
            double fraction = potential.getDouble("conservative_fraction");
            if (!(potential.get("conservative_fraction") instanceof Number) || !Double.isFinite(fraction) || fraction < 0 || fraction > 1)
                throw new IllegalArgumentException("bounded conservative fraction required");
        }
        String previous = null; double previousEnergy = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < states; index++) {
            String token = order.getString(index); Integer node = nodes.remove(token);
            if (node == null || energy.getDouble(node) < previousEnergy
                    || energy.getDouble(node) == previousEnergy && previous != null && token.compareTo(previous) < 0)
                throw new IllegalArgumentException("ordered unique potential tokens required");
            previous = token; previousEnergy = energy.getDouble(node);
        }
    }
    private static double number(JSONArray values, int index) throws Exception {
        if (!(values.get(index) instanceof Number) || !Double.isFinite(values.getDouble(index)))
            throw new IllegalArgumentException("finite numeric potential coordinates required");
        return values.getDouble(index);
    }
    private static double[] vector(JSONObject frame) throws Exception {
        JSONArray values = frame.getJSONArray("continuous_vector");
        double[] result = new double[values.length()];
        for (int index = 0; index < result.length; index++) result[index] = values.getDouble(index);
        return result;
    }
}
