package org.traceform.edge;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.zip.GZIPOutputStream;

final class ColdTrajectory {
    static void initialize(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS cold_models (namespace TEXT PRIMARY KEY, normalizer TEXT, bundle TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS cold_states (namespace TEXT, token TEXT, PRIMARY KEY(namespace,token), FOREIGN KEY(namespace) REFERENCES cold_models(namespace))");
        db.execSQL("CREATE TABLE IF NOT EXISTS cold_edges (namespace TEXT, source TEXT, target TEXT, n INTEGER, PRIMARY KEY(namespace,source,target), FOREIGN KEY(namespace,source) REFERENCES cold_states(namespace,token), FOREIGN KEY(namespace,target) REFERENCES cold_states(namespace,token))");
    }
    static void register(SQLiteDatabase db, String namespace, String normalizer, String[] tokens, JSONObject bundle) throws Exception {
        if (!namespace.matches("[0-9a-f]{64}") || !normalizer.matches("[0-9a-f]{64}") || tokens.length == 0 || tokens.length > 16)
            throw new IllegalArgumentException("bounded model identity and topology required");
        HashSet<String> vocabulary = new HashSet<>();
        for (String token : tokens) if (token.length() == 0 || token.length() > 64 || !vocabulary.add(token)) throw new IllegalArgumentException("invalid token vocabulary");
        db.beginTransaction();
        try {
            try (Cursor existing = db.rawQuery("SELECT normalizer,bundle FROM cold_models WHERE namespace=?", new String[]{namespace})) {
                if (existing.moveToFirst()) {
                    if (!normalizer.equals(existing.getString(0)) || !ModelJson.equal(bundle, new JSONObject(existing.getString(1)))) throw new IllegalArgumentException("cold model binding mismatch");
                    HashSet<String> registered = new HashSet<>();
                    try (Cursor states = db.rawQuery("SELECT token FROM cold_states WHERE namespace=?", new String[]{namespace})) {
                        while (states.moveToNext()) registered.add(states.getString(0));
                    }
                    if (!registered.equals(vocabulary)) throw new IllegalArgumentException("cold vocabulary binding mismatch");
                    ColdLineage.registerModel(db, namespace, normalizer, tokens, bundle);
                    db.setTransactionSuccessful(); return;
                }
            }
            if (DatabaseUtils.queryNumEntries(db, "cold_models") >= ColdLineage.modelLimit(db)) throw new IllegalArgumentException("retained cold model capacity exceeded");
            db.execSQL("INSERT INTO cold_models VALUES (?,?,?)", new Object[]{namespace, normalizer, bundle.toString()});
            for (String token : tokens) db.execSQL("INSERT INTO cold_states VALUES (?,?)", new Object[]{namespace, token});
            ColdLineage.registerModel(db, namespace, normalizer, tokens, bundle);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    static void append(SQLiteDatabase db, JSONObject previous, JSONObject frame) throws Exception {
        db.beginTransaction();
        try { appendInTransaction(db, previous, frame); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    private static void appendInTransaction(SQLiteDatabase db, JSONObject previous, JSONObject frame) throws Exception {
        if (previous == null || !frame.optBoolean("continuous_from_previous", false)) return;
        for (String key : new String[]{"session_id", "model_version", "model_sha256", "normalizer_sha256", "source_mode"})
            if (frame.isNull(key) || !frame.getString(key).equals(previous.optString(key))) return;
        long gap = frame.getLong("timestamp_epoch_ms") - previous.getLong("timestamp_epoch_ms");
        if (gap <= 0 || gap > 300000) return;
        try (Cursor binding = db.rawQuery("SELECT normalizer,bundle FROM cold_models WHERE namespace=?", new String[]{frame.getString("model_version")})) {
            if (!binding.moveToFirst() || !frame.getString("normalizer_sha256").equals(binding.getString(0)))
                throw new IllegalArgumentException("frame normalizer binding mismatch");
            if (!frame.getString("model_sha256").equals(new JSONObject(binding.getString(1)).getString("model_sha256")))
                throw new IllegalArgumentException("frame model content binding mismatch");
        }
        Object[] keys = {frame.getString("model_version"), previous.getString("state_token"), frame.getString("state_token")};
        db.execSQL("INSERT OR IGNORE INTO cold_edges VALUES (?,?,?,0)", keys);
        long count;
        try (Cursor row = db.rawQuery("SELECT n FROM cold_edges WHERE namespace=? AND source=? AND target=?",
                new String[]{(String)keys[0],(String)keys[1],(String)keys[2]})) {
            if (!row.moveToFirst() || row.getType(0) != Cursor.FIELD_TYPE_INTEGER || row.getLong(0) < 0)
                throw new IllegalArgumentException("nonnegative integer version count required");
            count = Math.addExact(row.getLong(0),1);
        }
        db.execSQL("UPDATE cold_edges SET n=? WHERE namespace=? AND source=? AND target=?", new Object[]{count,keys[0],keys[1],keys[2]});
        ColdLineage.appendRegistered(db, frame.getString("model_version"), previous.getString("state_token"), frame.getString("state_token"));
    }
    static long transitions(SQLiteDatabase db) {
        return DatabaseUtils.longForQuery(db, "SELECT COALESCE(SUM(n),0) FROM cold_edges", null);
    }
    static JSONObject countSummary(SQLiteDatabase db) throws Exception {
        db.beginTransaction();
        try {
            long retained = transitions(db);
            Object cumulative = JSONObject.NULL;
            if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='cold_lineage_metadata'",null) != 0
                    && DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM cold_lineage_metadata WHERE key='version_graph_migration'",null) != 0) {
                ColdLineage.modelLimit(db);
                try (Cursor row = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN typeof(n)='integer' AND n>=0 THEN 0 ELSE 1 END),0),COALESCE(SUM(n),0) FROM cold_lineage_edges",null)) {
                    if (!row.moveToFirst() || row.getLong(0) > 1024 || row.getLong(1) != 0 || row.getType(2) != Cursor.FIELD_TYPE_INTEGER || row.getLong(2) < 0)
                        throw new IllegalArgumentException("bounded nonnegative integer lineage counts required");
                    cumulative = row.getLong(2);
                }
            }
            JSONObject summary = new JSONObject().put("retained_version_transitions",retained).put("cumulative_lineage_transitions",cumulative)
                .put("cumulative_lineage_available",cumulative != JSONObject.NULL)
                .put("scope","version_counts_can_decrease_after_reclamation_lineage_counts_remain_cumulative");
            db.setTransactionSuccessful(); return summary;
        } finally { db.endTransaction(); }
    }
    static void backfillOnce(SQLiteDatabase db) throws Exception {
        db.execSQL("CREATE TABLE IF NOT EXISTS storage_metadata (key TEXT PRIMARY KEY, value TEXT)");
        db.beginTransaction();
        try {
            if (DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM storage_metadata WHERE key='cold_migration'", null) == 0) {
                String scope = "existing_counts_preserved_no_replay";
                if (transitions(db) == 0) {
                    JSONObject previous = null;
                    try (Cursor rows = db.rawQuery("SELECT payload FROM frames ORDER BY ts", null)) {
                        while (rows.moveToNext()) {
                            JSONObject frame = new JSONObject(rows.getString(0));
                            boolean registered = DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM cold_models WHERE namespace=?", new String[]{frame.optString("model_version")}) != 0;
                            if (registered) append(db, previous, frame);
                            previous = registered ? frame : null;
                        }
                    }
                    scope = "retained_hot_registered_models_explicit_continuity_only";
                }
                db.execSQL("INSERT INTO storage_metadata VALUES ('cold_migration',?)", new Object[]{scope});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    static JSONObject export(SQLiteDatabase db, File destination) throws Exception {
        JSONObject checkpoint = new JSONObject();
        JSONArray models = new JSONArray(), edges = new JSONArray();
        long count;
        db.beginTransaction();
        try {
            try (Cursor rows = db.rawQuery("SELECT namespace,normalizer,bundle FROM cold_models ORDER BY namespace", null)) {
                while (rows.moveToNext()) models.put(new JSONObject().put("namespace", rows.getString(0)).put("normalizer_sha256", rows.getString(1)).put("model", new JSONObject(rows.getString(2))));
            }
            try (Cursor rows = db.rawQuery("SELECT e.namespace,e.source,e.target,e.n,t.total FROM cold_edges e JOIN (SELECT namespace,source,SUM(n) total FROM cold_edges GROUP BY namespace,source) t ON e.namespace=t.namespace AND e.source=t.source ORDER BY e.namespace,e.source,e.target", null)) {
                while (rows.moveToNext()) edges.put(new JSONObject().put("namespace", rows.getString(0)).put("source", rows.getString(1)).put("target", rows.getString(2)).put("count", rows.getLong(3)).put("conditional_probability", rows.getLong(3) / (double) rows.getLong(4)));
            }
            JSONObject lineage = ColdLineage.snapshot(db);
            if (lineage != null) checkpoint.put("lineage_graph",lineage);
            checkpoint.put("count_summary",countSummary(db));
            count = transitions(db);
            checkpoint.put("latest_frame_epoch_ms", DatabaseUtils.longForQuery(db, "SELECT COALESCE(MAX(ts),0) FROM frames", null));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        checkpoint.put("protocol", "traceform.native-cold.v1").put("models", models).put("edges", edges)
            .put("transitions", count).put("scope", "fixed_model_subset_transition_statistics_not_causal_or_potential_inversion");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(checkpoint.toString().getBytes("UTF-8")); }
        byte[] compressed = bytes.toByteArray();
        if (compressed.length > 500000) throw new IllegalArgumentException("cold checkpoint exceeds 500000 bytes");
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(compressed); output.getFD().sync(); }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return new JSONObject().put("checkpoint_bytes", compressed.length).put("models", models.length()).put("transitions", count)
            .put("count_summary",checkpoint.getJSONObject("count_summary"))
            .put("latest_frame_epoch_ms", checkpoint.getLong("latest_frame_epoch_ms")).put("scope", checkpoint.getString("scope"));
    }
    static JSONObject verifyCountBound(ModelEngine model) throws Exception {
        for (boolean migrated : new boolean[]{false,true}) try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            initialize(db);
            register(db,model.modelVersion,model.normalizerDigest,model.tokens,model.coldBundle());
            db.execSQL("INSERT INTO cold_edges VALUES (?,?,?,?)",new Object[]{model.modelVersion,model.tokens[0],model.tokens[0],Long.MAX_VALUE});
            if (migrated) ColdLineage.migrateOnce(db);
            JSONObject previous = new JSONObject().put("model_version",model.modelVersion).put("model_sha256",model.modelDigest)
                .put("normalizer_sha256",model.normalizerDigest).put("session_id","count_bound_fixture")
                .put("source_mode","synthetic_fixture").put("state_token",model.tokens[0]).put("timestamp_epoch_ms",1000L);
            JSONObject frame = new JSONObject(previous.toString()).put("timestamp_epoch_ms",2000L).put("continuous_from_previous",true);
            boolean rejected = false;
            try { append(db,previous,frame); } catch (ArithmeticException expected) { rejected = true; }
            try (Cursor row = db.rawQuery("SELECT n FROM cold_edges",null)) {
                if (!rejected || db.inTransaction() || !row.moveToFirst() || row.getType(0) != Cursor.FIELD_TYPE_INTEGER || row.getLong(0) != Long.MAX_VALUE)
                    throw new IllegalStateException("version counter overflow accepted or changed integer count");
            }
            if (migrated && DatabaseUtils.longForQuery(db,"SELECT n FROM cold_lineage_edges",null) != Long.MAX_VALUE)
                throw new IllegalStateException("overflow changed cumulative lineage count");
            db.execSQL("UPDATE cold_edges SET n=1.5");
            boolean fractional = false;
            try { append(db,previous,frame); } catch (IllegalArgumentException expected) { fractional = true; }
            try (Cursor row = db.rawQuery("SELECT n FROM cold_edges",null)) {
                if (!fractional || db.inTransaction() || !row.moveToFirst() || row.getType(0) != Cursor.FIELD_TYPE_FLOAT || row.getDouble(0) != 1.5)
                    throw new IllegalStateException("fractional version count accepted or changed");
            }
        }
        return new JSONObject().put("passed",true).put("legacy_and_migrated_overflow_rejected",true)
            .put("integer_type_and_lineage_count_preserved",true).put("fractional_counts_rejected",true)
            .put("scope","synthetic_SQLite_per_edge_integer_counter_bound_not_unbounded_total_history");
    }
    static JSONObject verify(File directory) throws Exception {
        File destination = new File(directory, "cold_fixture.json.gz");
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.setForeignKeyConstraintsEnabled(true);
            initialize(db);
            db.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY, payload TEXT)");
            String namespace = String.format("%064d", 1), normalizer = String.format("%064d", 9);
            JSONObject bundle = new JSONObject().put("fixture", true).put("model_sha256", String.format("%064d", 7));
            register(db, namespace, normalizer, new String[]{"A", "B"}, bundle);
            register(db, namespace, normalizer, new String[]{"B", "A"}, bundle);
            boolean vocabularyMismatch = false;
            try { register(db, namespace, normalizer, new String[]{"A", "foreign"}, bundle); }
            catch (IllegalArgumentException expected) { vocabularyMismatch = true; }
            if (!vocabularyMismatch) throw new IllegalStateException("registered vocabulary mismatch accepted");
            JSONObject first = new JSONObject().put("model_version", namespace).put("normalizer_sha256", normalizer)
                .put("model_sha256", String.format("%064d", 7))
                .put("source_mode", "fixture").put("session_id", "session1").put("timestamp_epoch_ms", 1000).put("state_token", "A");
            JSONObject second = new JSONObject(first.toString()).put("timestamp_epoch_ms", 2000).put("state_token", "B").put("continuous_from_previous", true);
            db.execSQL("INSERT INTO frames VALUES (?,?)", new Object[]{1000, first.toString()});
            db.execSQL("INSERT INTO frames VALUES (?,?)", new Object[]{2000, second.toString()});
            db.execSQL("INSERT INTO frames VALUES (3000,'malformed')");
            boolean rollback = false;
            try { backfillOnce(db); } catch (org.json.JSONException expected) { rollback = true; }
            if (!rollback || transitions(db) != 0 || DatabaseUtils.queryNumEntries(db, "storage_metadata") != 0) throw new IllegalStateException("cold migration did not roll back");
            db.execSQL("DELETE FROM frames WHERE ts=3000");
            backfillOnce(db);
            backfillOnce(db);
            if (transitions(db) != 1) throw new IllegalStateException("cold migration repeated counts");
            db.execSQL("DELETE FROM storage_metadata WHERE key='cold_migration'");
            backfillOnce(db);
            if (transitions(db) != 1) throw new IllegalStateException("existing cold counts replayed");
            JSONObject changedModel = new JSONObject(second.toString()).put("timestamp_epoch_ms", 3000)
                .put("model_sha256", String.format("%064d", 8));
            append(db, second, changedModel);
            if (transitions(db) != 1) throw new IllegalStateException("model content boundary entered cold graph");
            JSONObject changedPrevious = new JSONObject(second.toString()).put("model_sha256", String.format("%064d", 8));
            boolean modelMismatch = false;
            try { append(db, changedPrevious, changedModel); }
            catch (IllegalArgumentException expected) { modelMismatch = true; }
            if (!modelMismatch || transitions(db) != 1) throw new IllegalStateException("unregistered model content entered cold graph");
            JSONObject missingModel = new JSONObject(second.toString()).put("timestamp_epoch_ms", 3000);
            missingModel.remove("model_sha256");
            append(db, second, missingModel);
            if (transitions(db) != 1) throw new IllegalStateException("unbound model content entered cold graph");
            JSONObject boundary = new JSONObject(second.toString()).put("session_id", "session2").put("timestamp_epoch_ms", 3000);
            append(db, second, boundary);
            JSONObject broken = new JSONObject(boundary.toString()).put("continuous_from_previous", false).put("timestamp_epoch_ms", 4000);
            append(db, boundary, broken);
            if (transitions(db) != 1) throw new IllegalStateException("boundary counts changed");
            boolean invalid = false;
            try { append(db, boundary, new JSONObject(boundary.toString()).put("timestamp_epoch_ms", 5000).put("state_token", "foreign")); }
            catch (android.database.sqlite.SQLiteConstraintException expected) { invalid = true; }
            if (!invalid || transitions(db) != 1) throw new IllegalStateException("foreign state accepted");
            boolean mismatch = false;
            try { register(db, namespace, String.format("%064d", 8), new String[]{"A", "B"}, bundle); }
            catch (IllegalArgumentException expected) { mismatch = true; }
            if (!mismatch) throw new IllegalStateException("normalizer mismatch accepted");
            JSONObject wrongFirst = new JSONObject(first.toString()).put("normalizer_sha256", String.format("%064d", 8));
            JSONObject wrongSecond = new JSONObject(second.toString()).put("normalizer_sha256", String.format("%064d", 8));
            boolean frameMismatch = false;
            try { append(db, wrongFirst, wrongSecond); } catch (IllegalArgumentException expected) { frameMismatch = true; }
            if (!frameMismatch || transitions(db) != 1) throw new IllegalStateException("frame normalizer binding accepted");
            for (int index = 2; index <= 4; index++) register(db, String.format("%064d", index), normalizer, new String[]{"A", "B"}, bundle);
            boolean overflow = false;
            try { register(db, String.format("%064d", 5), normalizer, new String[]{"A"}, bundle); }
            catch (IllegalArgumentException expected) { overflow = true; }
            if (!overflow || DatabaseUtils.queryNumEntries(db, "cold_models") != 4) throw new IllegalStateException("namespace bound failed");
            JSONObject summary = export(db, destination);
            if (summary.getLong("transitions") != 1 || summary.getInt("models") != 4) throw new IllegalStateException("checkpoint count mismatch");
            byte[] old = Files.readAllBytes(destination.toPath()), random = new byte[600000];
            new java.util.Random(41).nextBytes(random);
            JSONObject oversized = new JSONObject().put("fixture", java.util.Base64.getEncoder().encodeToString(random));
            db.execSQL("UPDATE cold_models SET bundle=? WHERE namespace=?", new Object[]{oversized.toString(), namespace});
            boolean sizeRejected = false;
            try { export(db, destination); } catch (IllegalArgumentException expected) { sizeRejected = true; }
            if (!sizeRejected || !java.util.Arrays.equals(old, Files.readAllBytes(destination.toPath()))) throw new IllegalStateException("size failure replaced accepted checkpoint");
            return new JSONObject().put("passed", true).put("model_content_boundary_verified", true)
                .put("registered_model_content_binding_verified", true).put("missing_model_content_excluded", true)
                .put("scope", "native_cold_atomic_idempotent_backfill_boundaries_binding_namespace_bound_and_atomic_size_fixture");
        } finally { destination.delete(); }
    }
}
