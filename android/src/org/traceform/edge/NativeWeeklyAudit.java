package org.traceform.edge;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Streaming audit of retained frames; never activates a candidate model. */
final class NativeWeeklyAudit {
    static JSONObject run(SQLiteDatabase database) throws Exception {
        return run(database, null);
    }
    static JSONObject run(SQLiteDatabase database, ModelEngine active) throws Exception {
        MessageDigest source = MessageDigest.getInstance("SHA-256");
        long records = 0, invalid = 0, pairs = 0, boundaries = 0;
        long assessed = 0, nonadjacent = 0, nativeRecords = 0;
        long[] nativeKinds = new long[4];
        double energySum = 0;
        JSONObject previous = null;
        if (android.os.Build.VERSION.SDK_INT >= 35) database.beginTransactionReadOnly();
        else database.beginTransactionNonExclusive();
        try {
            java.util.Map<String, JSONObject> models = new java.util.HashMap<>();
            java.util.Map<String, String> normalizers = new java.util.HashMap<>();
            try (Cursor bindings = database.rawQuery("SELECT namespace,normalizer,bundle FROM cold_models ORDER BY namespace", null)) {
                while (bindings.moveToNext()) {
                    if (models.size() >= 4) throw new IllegalArgumentException("bounded model registry required");
                    models.put(bindings.getString(0), new JSONObject(bindings.getString(2)));
                    normalizers.put(bindings.getString(0), bindings.getString(1));
                }
            }
            try (Cursor rows = database.rawQuery("SELECT ts,payload FROM frames ORDER BY ts", null)) {
                while (rows.moveToNext()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("weekly audit cancelled");
                    records++;
                    long instant = rows.getLong(0);
                    String encoded = rows.getString(1);
                    byte[] payload = encoded == null ? new byte[0] : encoded.getBytes(StandardCharsets.UTF_8);
                    source.update(ByteBuffer.allocate(12).putLong(instant).putInt(encoded == null ? -1 : payload.length).array());
                    source.update(payload);
                    try {
                        if (encoded == null) throw new IllegalArgumentException("missing payload");
                        JSONObject frame = new JSONObject(encoded);
                        for (String clock : new String[]{"timestamp_epoch_ms", "elapsed_realtime_nanos"})
                            if (!(frame.get(clock) instanceof Long) && !(frame.get(clock) instanceof Integer))
                                throw new IllegalArgumentException("integer clock required");
                        String version = frame.getString("model_version");
                        JSONObject model = models.get(version);
                        if (model == null) throw new IllegalArgumentException("unknown model");
                        int dimension = model.getJSONArray("feature_names").length();
                        if (dimension != 128 || frame.getLong("timestamp_epoch_ms") != instant
                                || frame.getLong("elapsed_realtime_nanos") < 0
                                || !normalizers.get(version).equals(frame.getString("normalizer_sha256"))
                                || !model.getString("model_sha256").equals(frame.getString("model_sha256")))
                            throw new IllegalArgumentException("frame binding");
                        for (String field : new String[]{"model_version", "model_sha256", "normalizer_sha256", "session_id", "source_mode", "state_token"})
                            if (!(frame.get(field) instanceof String) || frame.getString(field).isEmpty())
                                throw new IllegalArgumentException("identity binding");
                        boolean known = false;
                        JSONArray tokens = model.getJSONArray("tokens");
                        for (int index = 0; index < tokens.length(); index++) known |= tokens.getString(index).equals(frame.getString("state_token"));
                        if (!known || !(frame.get("continuous_from_previous") instanceof Boolean)) throw new IllegalArgumentException("topology binding");
                        for (String field : new String[]{"continuous_vector", "continuous_velocity"}) {
                            JSONArray vector = frame.getJSONArray(field);
                            if (vector.length() != dimension) throw new IllegalArgumentException("coordinate dimension");
                            for (int index = 0; index < dimension; index++)
                                if (!(vector.get(index) instanceof Number) || !Double.isFinite(vector.getDouble(index)))
                                    throw new IllegalArgumentException("finite coordinates");
                        }
                        int[] kinds = frame.getString("source_mode").equals("native_sensor_subset")
                            ? validateNativeProvenance(frame, model.getJSONArray("feature_names")) : null;
                        if (active != null && version.equals(active.modelVersion)
                                && frame.getString("model_sha256").equals(active.modelDigest)
                                && frame.getString("normalizer_sha256").equals(active.normalizerDigest)) {
                            double[] position = new double[dimension], velocity = new double[dimension];
                            for (int index = 0; index < dimension; index++) {
                                position[index] = frame.getJSONArray("continuous_vector").getDouble(index);
                                velocity[index] = frame.getJSONArray("continuous_velocity").getDouble(index);
                            }
                            TopologyStrain.Result strain = active.assessTopology(position, velocity);
                            if (!Double.isFinite(energySum + strain.energy)) throw new IllegalArgumentException("finite energy aggregate required");
                            energySum += strain.energy;
                            assessed++;
                            if (strain.nonadjacent) nonadjacent++;
                        }
                        boolean continuous = previous != null && frame.getBoolean("continuous_from_previous");
                        if (continuous) {
                            for (String field : new String[]{"model_version", "model_sha256", "normalizer_sha256", "session_id", "source_mode"})
                                continuous &= frame.getString(field).equals(previous.getString(field));
                            if (continuous && kinds != null) {
                                JSONObject currentSources = frame.getJSONObject("feature_provenance");
                                JSONObject previousSources = previous.getJSONObject("feature_provenance");
                                JSONArray names = model.getJSONArray("feature_names");
                                for (int index = 0; index < names.length(); index++) {
                                    String name = names.getString(index);
                                    JSONObject currentReading = currentSources.getJSONObject(name), priorReading = previousSources.getJSONObject(name);
                                    String kind = currentReading.getString("kind");
                                    if (!kind.equals(priorReading.getString("kind"))
                                            || (!kind.equals("missing") && !currentReading.getString("source").equals(priorReading.getString("source"))))
                                        throw new IllegalArgumentException("continuous native frame changed feature coverage, kind or source");
                                }
                            }
                            long gap = instant - previous.getLong("timestamp_epoch_ms");
                            double elapsedGap = (frame.getLong("elapsed_realtime_nanos") - (double) previous.getLong("elapsed_realtime_nanos")) / 1e6;
                            continuous &= gap > 0 && gap <= 300000 && elapsedGap > 0 && Math.abs(gap - elapsedGap) <= 1000;
                        }
                        if (continuous) pairs++; else boundaries++;
                        if (kinds != null) {
                            nativeRecords++;
                            for (int index = 0; index < kinds.length; index++) nativeKinds[index] += kinds[index];
                        }
                        previous = frame;
                    } catch (org.json.JSONException | IllegalArgumentException error) {
                        invalid++;
                        previous = null;
                    }
                }
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        return new JSONObject().put("protocol", "traceform.native-weekly-audit.v1")
            .put("records", records).put("invalid_records", invalid).put("continuous_pairs", pairs)
            .put("segment_boundaries", boundaries).put("source_sha256", HexDigest.encode(source.digest()))
            .put("candidate_input_available", invalid == 0 && pairs >= 2)
            .put("native_provenance_records", nativeRecords)
            .put("native_observed_coordinates", nativeKinds[0]).put("native_derived_coordinates", nativeKinds[1])
            .put("native_proxy_coordinates", nativeKinds[2]).put("native_imputed_coordinates", nativeKinds[3])
            .put("native_provenance_scope", "validated_native_sensor_subset_inventory_not_independent_observability_or_physical_truth")
            .put("candidate_activation_authorized", false)
            .put("topology_assessed_records", assessed)
            .put("mean_energy_distance", assessed == 0 ? JSONObject.NULL : energySum / assessed)
            .put("topographic_error", assessed == 0 || active.tokens.length < 2 ? JSONObject.NULL : nonadjacent / (double) assessed)
            .put("topology_model_sha256", active == null ? JSONObject.NULL : active.modelDigest)
            .put("scope", "retained_hot_binding_and_active_model_nearest_two_diagnostic_not_candidate_calibration");
    }
    private static int[] validateNativeProvenance(JSONObject frame, JSONArray names) throws Exception {
        if (!frame.getString("missing_policy").equals("median_imputation_not_measurement"))
            throw new IllegalArgumentException("native missing policy required");
        JSONObject provenance = frame.getJSONObject("feature_provenance");
        if (provenance.length() != names.length()) throw new IllegalArgumentException("complete native provenance required");
        java.util.Set<String> inventory = new java.util.HashSet<>(), supplied = new java.util.HashSet<>();
        JSONArray provided = frame.getJSONArray("supplied_features"), missing = frame.getJSONArray("missing_features");
        for (int index = 0; index < provided.length(); index++) {
            if (!(provided.get(index) instanceof String) || !inventory.add(provided.getString(index)))
                throw new IllegalArgumentException("unique supplied feature names required");
            supplied.add(provided.getString(index));
        }
        for (int index = 0; index < missing.length(); index++) {
            if (!(missing.get(index) instanceof String) || !inventory.add(missing.getString(index)))
                throw new IllegalArgumentException("disjoint unique missing feature names required");
        }
        if (inventory.size() != names.length()) throw new IllegalArgumentException("complete native feature inventory required");
        int[] counts = new int[4];
        for (int index = 0; index < names.length(); index++) {
            String name = names.getString(index);
            if (!inventory.contains(name)) throw new IllegalArgumentException("model feature inventory required");
            JSONObject reading = provenance.getJSONObject(name);
            if (!(reading.get("kind") instanceof String)) throw new IllegalArgumentException("string provenance kind required");
            String kind = reading.getString("kind");
            if (!supplied.contains(name)) {
                if (!kind.equals("missing") || !reading.getString("policy").equals("median_imputation")
                        || frame.getJSONArray("continuous_vector").getDouble(index) != 0
                        || frame.getJSONArray("continuous_velocity").getDouble(index) != 0)
                    throw new IllegalArgumentException("missing coordinate must be median-imputed with zero velocity");
                counts[3]++;
            } else {
                int category = kind.equals("observed") ? 0 : kind.equals("derived") ? 1 : kind.equals("proxy") ? 2 : -1;
                if (category < 0 || !(reading.get("source") instanceof String) || reading.getString("source").isEmpty())
                    throw new IllegalArgumentException("supplied feature source and kind required");
                for (String field : new String[]{"source_age_ms", "max_age_ms"})
                    if (!(reading.get(field) instanceof Number) || !Double.isFinite(reading.getDouble(field)) || reading.getDouble(field) < 0)
                        throw new IllegalArgumentException("finite nonnegative source age required");
                if (reading.getDouble("source_age_ms") > reading.getDouble("max_age_ms"))
                    throw new IllegalArgumentException("supplied source expired");
                counts[category]++;
            }
        }
        return counts;
    }

}
