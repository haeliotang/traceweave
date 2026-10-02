package org.traceform.edge;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fit/evaluate an immutable source snapshot. Does not change the active model. */
final class NativeCandidateCalibration {
    static JSONObject run(SQLiteDatabase snapshot, ModelEngine model, java.io.File directory) throws Exception {
        long started = System.nanoTime();
        if (android.os.Build.VERSION.SDK_INT >= 35) snapshot.beginTransactionReadOnly();
        else snapshot.beginTransactionNonExclusive();
        try {
            JSONObject audit = NativeWeeklyAudit.run(snapshot, model);
            long auditEnd = System.nanoTime();
            JSONObject report = new JSONObject().put("audit", audit).put("accepted", false)
                .put("candidate_activated", false).put("parent_model_sha256", model.modelDigest)
                .put("normalizer_sha256", model.normalizerDigest).put("source_sha256", audit.getString("source_sha256"));
            if (audit.getLong("invalid_records") != 0) return report.put("reason", "invalid_retained_source");
            String selection = "json_extract(payload,'$.model_version')=? AND json_extract(payload,'$.model_sha256')=? AND json_extract(payload,'$.normalizer_sha256')=?";
            String[] binding = {model.modelVersion, model.modelDigest, model.normalizerDigest};
            long count = android.database.DatabaseUtils.longForQuery(snapshot, "SELECT COUNT(*) FROM frames WHERE " + selection, binding);
            if (count < 100 || count > 172800) return report.put("reason", "bounded_100_to_172800_active_records_required");
            long[] instants = new long[(int) count];
            try (Cursor rows = snapshot.rawQuery("SELECT ts FROM frames WHERE " + selection + " ORDER BY ts", binding)) {
                int index = 0;
                while (rows.moveToNext()) instants[index++] = rows.getLong(0);
            }
            int split = (int) (count * 0.7);
            int[] training = new int[split];
            try (TrainingVectors vectors = new TrainingVectors(directory, model.names.length, split, audit.getString("source_sha256"))) {
                long[][] nearestPairs = new long[model.tokens.length][model.tokens.length];
                for (int index = 0; index < split; index++) {
                    training[index] = index;
                    JSONObject frame = frame(snapshot, instants[index]);
                    double[] position = vector(frame, "continuous_vector"), velocity = vector(frame, "continuous_velocity");
                    vectors.append(instants[index], position, velocity);
                    TopologyStrain.Result nearest = model.assessTopology(position, velocity);
                    if (nearest.second >= 0) nearestPairs[Math.min(nearest.first, nearest.second)][Math.max(nearest.first, nearest.second)]++;
                }
                vectors.seal();
                GridAdaptation.Result structure = GridAdaptation.adapt(model.tokens, model.gridCoordinates(), nearestPairs);
                long structureEnd = System.nanoTime();
                IncrementalManifoldFit fit = model.incrementalFit(split, structure.grid);
                java.util.Random random = new java.util.Random(42);
                for (int epoch = 0; epoch < 6; epoch++) {
                    for (int index = training.length - 1; index > 0; index--) {
                        int other = random.nextInt(index + 1);
                        int swap = training[index]; training[index] = training[other]; training[other] = swap;
                    }
                    for (int index : training) {
                        TrainingVectors.Sample sample = vectors.read(index, instants[index]);
                        fit.update(sample.position, sample.velocity);
                    }
                }
                double[] centers = fit.centers(), velocities = fit.velocities();
                long fitEnd = System.nanoTime();
                double baselineEnergy = 0, candidateEnergy = 0;
                long baselineErrors = 0, candidateErrors = 0;
                java.util.Set<Integer> baselineStates = new java.util.HashSet<>(), candidateStates = new java.util.HashSet<>();
                for (int index = split; index < instants.length; index++) {
                    JSONObject frame = frame(snapshot, instants[index]);
                    double[] position = vector(frame, "continuous_vector"), velocity = vector(frame, "continuous_velocity");
                    TopologyStrain.Result baseline = model.assessTopology(position, velocity);
                    TopologyStrain.Result candidate = model.assessCandidate(position, velocity, centers, velocities, structure.grid);
                    baselineEnergy += baseline.energy; candidateEnergy += candidate.energy;
                    baselineStates.add(baseline.first); candidateStates.add(candidate.first);
                    if (baseline.nonadjacent) baselineErrors++;
                    if (candidate.nonadjacent) candidateErrors++;
                }
                int heldOut = instants.length - split;
                baselineEnergy /= heldOut; candidateEnergy /= heldOut;
                boolean accepted = CalibrationGate.accepts(baselineEnergy, candidateEnergy,
                    baselineStates.size(), candidateStates.size(), baselineErrors, candidateErrors);
                long holdoutEnd = System.nanoTime();
                JSONObject potential = NativePotentialCalibration.run(snapshot, model, centers, instants[split - 1], audit.getString("source_sha256"));
                long potentialEnd = System.nanoTime();
                snapshot.setTransactionSuccessful();
                return report.put("accepted", accepted).put("reason", accepted ? "holdout_distance_coverage_topology" : "holdout_gate_not_met")
                    .put("training_records", split).put("holdout_records", heldOut)
                    .put("training_end_epoch_ms", instants[split - 1]).put("holdout_start_epoch_ms", instants[split])
                    .put("baseline_mean_distance", baselineEnergy).put("candidate_mean_distance", candidateEnergy)
                    .put("baseline_states", baselineStates.size()).put("candidate_states", candidateStates.size())
                    .put("baseline_topographic_error", baselineErrors / (double) heldOut)
                    .put("candidate_topographic_error", candidateErrors / (double) heldOut)
                    .put("baseline_nonadjacent_count", baselineErrors).put("candidate_nonadjacent_count", candidateErrors)
                    .put("candidate_centers", new JSONArray(centers)).put("candidate_velocity", new JSONArray(velocities))
                    .put("candidate_grid", new JSONArray(structure.grid))
                    .put("potential", potential)
                    .put("fit_preparation_json_decodes", split).put("training_vector_spool_bytes", vectors.bytes())
                    .put("timing_ms", new JSONObject().put("audit", (auditEnd - started) / 1e6)
                        .put("training_selection_and_grid", (structureEnd - auditEnd) / 1e6)
                        .put("six_epoch_fit", (fitEnd - structureEnd) / 1e6)
                        .put("holdout", (holdoutEnd - fitEnd) / 1e6)
                        .put("potential", (potentialEnd - holdoutEnd) / 1e6)
                        .put("total_before_report_encoding", (potentialEnd - started) / 1e6))
                    .put("structural_change", new JSONObject().put("training_nonadjacent_pairs_before", structure.before)
                        .put("training_nonadjacent_pairs_after", structure.after).put("coordinate_swaps", new JSONArray(structure.swaps)))
                    .put("sample_order", "java_random_42_Fisher_Yates_six_epochs_not_Python_seed_parity")
                    .put("scope", "fixed_normalizer_representation_holdout_gate_not_predictive_or_causal_validation");
            }
        } finally { snapshot.endTransaction(); }
    }
    private static JSONObject frame(SQLiteDatabase snapshot, long instant) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("candidate fitting cancelled");
        try (Cursor row = snapshot.rawQuery("SELECT payload FROM frames WHERE ts=?", new String[]{Long.toString(instant)})) {
            if (!row.moveToFirst()) throw new IllegalStateException("snapshot changed during fitting");
            return new JSONObject(row.getString(0));
        }
    }
    private static double[] vector(JSONObject frame, String field) throws Exception {
        JSONArray values = frame.getJSONArray(field);
        double[] result = new double[values.length()];
        for (int index = 0; index < result.length; index++) result[index] = values.getDouble(index);
        return result;
    }
}
