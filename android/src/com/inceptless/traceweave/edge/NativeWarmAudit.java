// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Streaming source and model binding checks for retained 30-second representatives. */
final class NativeWarmAudit {
    static JSONObject run(SQLiteDatabase database) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 35) throw new UnsupportedOperationException("protected warm audit requires Android 35 read-only transactions");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("traceform.native-warm-source.v2\n".getBytes(StandardCharsets.US_ASCII));
        long records = 0, invalid = 0;
        database.beginTransactionReadOnly();
        try {
            java.util.Map<String, JSONObject> models = new java.util.HashMap<>();
            java.util.Map<String, String> normalizers = new java.util.HashMap<>();
            try (Cursor rows = database.rawQuery("SELECT namespace,normalizer,bundle FROM cold_models ORDER BY namespace", null)) {
                while (rows.moveToNext()) {
                    if (models.size() >= 8) throw new IllegalArgumentException("bounded warm model registry required");
                    models.put(rows.getString(0), new JSONObject(rows.getString(2)));
                    normalizers.put(rows.getString(0), rows.getString(1));
                }
            }
            try (Cursor rows = database.rawQuery("SELECT bucket,ts,payload,n FROM warm_trajectory ORDER BY bucket", null)) {
                while (rows.moveToNext()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("warm audit cancelled");
                    if (++records > 86400) throw new IllegalArgumentException("bounded 30d warm source required");
                    for (int column = 0; column < 4; column++) hashColumn(digest, rows, column);
                    try {
                        if (rows.getType(0) != Cursor.FIELD_TYPE_INTEGER || rows.getType(1) != Cursor.FIELD_TYPE_INTEGER
                                || rows.getType(3) != Cursor.FIELD_TYPE_INTEGER || rows.getType(2) != Cursor.FIELD_TYPE_STRING)
                            throw new IllegalArgumentException("integer warm SQL columns and text payload required");
                        long bucket = rows.getLong(0), instant = rows.getLong(1), count = rows.getLong(3);
                        String encoded = rows.getString(2);
                        if (encoded == null) throw new IllegalArgumentException("missing warm payload");
                        JSONObject frame = new JSONObject(encoded);
                        String version = frame.getString("model_version");
                        JSONObject model = models.get(version);
                        if (model == null || !normalizers.get(version).equals(frame.getString("normalizer_sha256"))
                                || !model.getString("model_sha256").equals(frame.getString("model_sha256")))
                            throw new IllegalArgumentException("warm model binding");
                        if (integer(frame, "timestamp_epoch_ms") != instant || integer(frame, "bucket_epoch_ms") != bucket
                                || integer(frame, "bucket_frame_count") != count || count < 1 || count > 172800
                                || instant < 0 || bucket != Math.floorDiv(instant, 30000) * 30000
                                || integer(frame, "missing_buckets_before") < 0
                                || !(frame.get("continuous_from_previous") instanceof Boolean)
                                || !"latest_observed_in_utc_30s_bucket".equals(frame.getString("sample_policy")))
                            throw new IllegalArgumentException("warm clock or representative binding");
                        for (String field : new String[]{"session_id", "source_mode", "state_token"})
                            if (!(frame.get(field) instanceof String) || frame.getString(field).isEmpty())
                                throw new IllegalArgumentException("warm identity required");
                        int token = -1;
                        JSONArray tokens = model.getJSONArray("tokens");
                        for (int index = 0; index < tokens.length(); index++)
                            if (tokens.getString(index).equals(frame.getString("state_token"))) token = index;
                        if (token < 0 || number(frame, "energy_distance") < 0) throw new IllegalArgumentException("warm topology or distance binding");
                        Object available = frame.opt("state_potential_available");
                        if (Boolean.TRUE.equals(available)) {
                            JSONObject potential = model.getJSONObject("potential");
                            if (!Boolean.TRUE.equals(potential.opt("available"))
                                    || number(frame, "state_relative_potential") != potential.getJSONArray("relative_energy").getDouble(token)
                                    || !"model_center_empirical_drift_projection_gauge_zero_not_physical_energy".equals(frame.getString("state_potential_scope")))
                                throw new IllegalArgumentException("warm potential binding");
                        } else if ((available != null && available != JSONObject.NULL && !Boolean.FALSE.equals(available))
                                || !frame.isNull("state_relative_potential"))
                            throw new IllegalArgumentException("explicit unavailable potential required");
                    } catch (org.json.JSONException | IllegalArgumentException error) { invalid++; }
                }
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        return new JSONObject().put("protocol", "traceform.native-warm-audit.v2").put("records", records)
            .put("invalid_records", invalid).put("valid", invalid == 0).put("source_sha256", HexDigest.encode(digest.digest()))
            .put("scope", "retained_warm_representative_clock_model_token_optional_potential_binding_not_causal_validation");
    }
    private static void hashColumn(MessageDigest digest, Cursor row, int column) {
        int type = row.getType(column); byte[] bytes;
        switch (type) {
            case Cursor.FIELD_TYPE_NULL: bytes = null; break;
            case Cursor.FIELD_TYPE_INTEGER: bytes = ByteBuffer.allocate(8).putLong(row.getLong(column)).array(); break;
            case Cursor.FIELD_TYPE_FLOAT: bytes = ByteBuffer.allocate(8).putLong(Double.doubleToLongBits(row.getDouble(column))).array(); break;
            case Cursor.FIELD_TYPE_STRING: bytes = row.getString(column).getBytes(StandardCharsets.UTF_8); break;
            case Cursor.FIELD_TYPE_BLOB: bytes = row.getBlob(column); break;
            default: throw new IllegalArgumentException("unknown SQLite column type");
        }
        digest.update(ByteBuffer.allocate(8).putInt(type).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) digest.update(bytes);
    }
    static JSONObject verify(ModelEngine model) throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(db);
            ColdTrajectory.register(db, model.modelVersion, model.normalizerDigest, model.tokens, model.coldBundle());
            db.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)");
            JSONObject frame = model.process(model.medians, new double[model.names.length])
                .put("timestamp_epoch_ms", 1000L).put("session_id", "synthetic_warm_audit").put("source_mode", "synthetic_fixture");
            WarmTrajectory.append(db, frame, WarmBucket.next(1000, false, null));
            JSONObject clean = run(db), repeated = run(db);
            if (!clean.getBoolean("valid") || clean.getLong("records") != 1
                    || !clean.getString("source_sha256").equals(repeated.getString("source_sha256")))
                throw new IllegalStateException("clean warm audit or stable source digest failed");
            db.execSQL("UPDATE warm_trajectory SET n=1.5");
            JSONObject fractional = run(db);
            if (fractional.getBoolean("valid") || fractional.getString("source_sha256").equals(clean.getString("source_sha256")))
                throw new IllegalStateException("fractional SQL count accepted or raw source change lost");
            db.execSQL("UPDATE warm_trajectory SET n=1,ts=1000.5");
            if (run(db).getBoolean("valid")) throw new IllegalStateException("fractional SQL timestamp accepted");
            db.execSQL("UPDATE warm_trajectory SET ts=1000");
            db.execSQL("UPDATE warm_trajectory SET n=2");
            JSONObject mismatch = run(db);
            if (mismatch.getBoolean("valid") || mismatch.getLong("invalid_records") != 1
                    || mismatch.getString("source_sha256").equals(clean.getString("source_sha256")))
                throw new IllegalStateException("warm count corruption or source change undetected");
            db.execSQL("UPDATE warm_trajectory SET n=1,payload='{' ");
            if (run(db).getBoolean("valid")) throw new IllegalStateException("malformed warm payload accepted");
            boolean cancelled = false;
            try { Thread.currentThread().interrupt(); run(db); }
            catch (InterruptedException expected) { cancelled = true; }
            finally { Thread.interrupted(); }
            if (!cancelled || db.inTransaction()) throw new IllegalStateException("warm audit cancellation or transaction release failed");
            return new JSONObject().put("passed", true).put("source_digest_stable", true).put("count_corruption_rejected", true)
                .put("fractional_sql_clock_count_rejected", true)
                .put("source_change_detected", true).put("malformed_rejected", true).put("cancellation_transaction_release", true)
                .put("scope", "synthetic_warm_audit_fixture_not_retained_user_history");
        }
    }
    private static long integer(JSONObject frame, String field) throws Exception {
        Object value = frame.get(field);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("integer warm field required");
        return ((Number) value).longValue();
    }
    private static double number(JSONObject frame, String field) throws Exception {
        if (!(frame.get(field) instanceof Number) || !Double.isFinite(frame.getDouble(field)))
            throw new IllegalArgumentException("finite numeric warm field required");
        return frame.getDouble(field);
    }
}
