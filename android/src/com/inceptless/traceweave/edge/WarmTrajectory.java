// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

final class WarmTrajectory {
    static final String MIGRATION_SCOPE = "retained_hot_only_no_occupancy_reconstruction";
    static void append(SQLiteDatabase database, JSONObject payload, WarmBucket warm) throws Exception {
        long now = payload.getLong("timestamp_epoch_ms");
        int represented = 1;
        boolean continuous = warm.continuous;
        long missing = warm.missingBefore;
        try (Cursor existing = database.rawQuery("SELECT n,payload,ts FROM warm_trajectory WHERE bucket=?", new String[]{Long.toString(warm.bucket)})) {
            if (existing.moveToFirst()) {
                if (now < existing.getLong(2)) throw new IllegalArgumentException("warm representative time reversal");
                JSONObject old = new JSONObject(existing.getString(1));
                represented += existing.getInt(0);
                continuous &= old.getBoolean("continuous_from_previous");
                missing = Math.max(missing, old.getLong("missing_buckets_before"));
            }
        }
        JSONObject representative = new JSONObject();
        for (String key : new String[]{"timestamp_epoch_ms", "state_token", "energy_distance", "model_version",
                "model_sha256", "normalizer_sha256", "source_mode", "session_id", "state_relative_potential",
                "state_potential_available", "state_potential_scope"}) representative.put(key, payload.opt(key) == null ? JSONObject.NULL : payload.opt(key));
        representative.put("timestamp_utc", payload.optString("timestamp_utc", java.time.Instant.ofEpochMilli(now).toString()));
        representative.put("bucket_epoch_ms", warm.bucket).put("bucket_frame_count", represented)
            .put("continuous_from_previous", continuous).put("missing_buckets_before", missing)
            .put("sample_policy", "latest_observed_in_utc_30s_bucket")
            .put("evidence_scope", "ordered_subset_observations_with_optional_model_center_potential_not_physical_energy");
        database.execSQL("INSERT OR REPLACE INTO warm_trajectory VALUES (?,?,?,?)",
            new Object[]{warm.bucket, now, representative.toString(), represented});
    }
    static void backfillOnce(SQLiteDatabase database) throws Exception {
        database.execSQL("CREATE TABLE IF NOT EXISTS storage_metadata (key TEXT PRIMARY KEY, value TEXT)");
        database.beginTransaction();
        try {
            try (Cursor marker = database.rawQuery("SELECT value FROM storage_metadata WHERE key='warm_migration'", null)) {
                if (marker.moveToFirst()) { database.setTransactionSuccessful(); return; }
            }
            JSONObject previous = null;
            WarmBucket previousBucket = null;
            Long currentBucket = null;
            boolean alreadyPresent = false;
            try (Cursor rows = database.rawQuery("SELECT payload FROM frames ORDER BY ts", null)) {
                while (rows.moveToNext()) {
                    JSONObject frame = new JSONObject(rows.getString(0));
                    long now = frame.getLong("timestamp_epoch_ms");
                    boolean continuous = previous != null && frame.optBoolean("continuous_from_previous", false)
                        && !frame.isNull("session_id") && frame.optString("session_id").equals(previous.optString("session_id"))
                        && frame.optString("model_version").equals(previous.optString("model_version"))
                        && now > previous.getLong("timestamp_epoch_ms") && now - previous.getLong("timestamp_epoch_ms") <= 300000;
                    WarmBucket bucket = WarmBucket.next(now, continuous, previousBucket);
                    if (currentBucket == null || currentBucket != bucket.bucket) {
                        currentBucket = bucket.bucket;
                        try (Cursor existing = database.rawQuery("SELECT bucket FROM warm_trajectory WHERE bucket=?", new String[]{Long.toString(bucket.bucket)})) {
                            alreadyPresent = existing.moveToFirst();
                        }
                    }
                    if (!alreadyPresent) append(database, frame, bucket);
                    previous = frame; previousBucket = bucket;
                }
            }
            database.execSQL("INSERT INTO storage_metadata VALUES ('warm_migration',?)", new Object[]{MIGRATION_SCOPE});
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }
    static JSONObject verifyMigration() throws Exception {
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            database.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY, payload TEXT)");
            database.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)");
            WarmBucket previous = null;
            for (long instant : new long[]{1000, 2000, 31000, 32000}) {
                JSONObject frame = new JSONObject().put("timestamp_epoch_ms", instant).put("state_token", "A")
                    .put("model_version", "fixture").put("energy_distance", 1)
                    .put("state_relative_potential", instant / 1000.0).put("state_potential_available", true)
                    .put("state_potential_scope", "synthetic_potential_fixture");
                database.execSQL("INSERT INTO frames VALUES (?,?)", new Object[]{instant, frame.toString()});
                if (instant < 30000) {
                    previous = WarmBucket.next(instant, true, previous);
                    append(database, frame, previous);
                }
            }
            database.execSQL("INSERT INTO frames VALUES (33000,'{')");
            boolean rejected = false;
            try { backfillOnce(database); } catch (org.json.JSONException expected) { rejected = true; }
            if (!rejected || android.database.DatabaseUtils.queryNumEntries(database, "warm_trajectory") != 1
                    || android.database.DatabaseUtils.queryNumEntries(database, "storage_metadata") != 0)
                throw new IllegalStateException("migration did not roll back");
            database.execSQL("DELETE FROM frames WHERE ts=33000");
            backfillOnce(database); backfillOnce(database);
            try (Cursor rows = database.rawQuery("SELECT payload FROM warm_trajectory ORDER BY bucket", null)) {
                int index = 0;
                while (rows.moveToNext()) {
                    JSONObject retained = new JSONObject(rows.getString(0));
                    if (!retained.getBoolean("state_potential_available")
                            || retained.getDouble("state_relative_potential") != (index++ == 0 ? 2.0 : 32.0)
                            || !retained.getString("state_potential_scope").equals("synthetic_potential_fixture"))
                        throw new IllegalStateException("warm state potential retention mismatch");
                }
            }
            try (Cursor result = database.rawQuery("SELECT COUNT(*),SUM(n) FROM warm_trajectory", null)) {
                result.moveToFirst();
                if (result.getInt(0) != 2 || result.getInt(1) != 4) throw new IllegalStateException("migration duplicate or missing counts");
            }
            return new JSONObject().put("passed", true).put("scope", "native_sqlite_warm_backfill_rollback_existing_bucket_and_idempotence_fixture");
        }
    }
}
