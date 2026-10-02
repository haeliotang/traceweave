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
        long assessed = 0, nonadjacent = 0;
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
                            long gap = instant - previous.getLong("timestamp_epoch_ms");
                            double elapsedGap = (frame.getLong("elapsed_realtime_nanos") - (double) previous.getLong("elapsed_realtime_nanos")) / 1e6;
                            continuous &= gap > 0 && gap <= 300000 && elapsedGap > 0 && Math.abs(gap - elapsedGap) <= 1000;
                        }
                        if (continuous) pairs++; else boundaries++;
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
            .put("candidate_activation_authorized", false)
            .put("topology_assessed_records", assessed)
            .put("mean_energy_distance", assessed == 0 ? JSONObject.NULL : energySum / assessed)
            .put("topographic_error", assessed == 0 || active.tokens.length < 2 ? JSONObject.NULL : nonadjacent / (double) assessed)
            .put("topology_model_sha256", active == null ? JSONObject.NULL : active.modelDigest)
            .put("scope", "retained_hot_binding_and_active_model_nearest_two_diagnostic_not_candidate_calibration");
    }
}
