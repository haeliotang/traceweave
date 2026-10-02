package org.traceform.edge;

import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.io.File;

/** Short synthetic storage checks, separate from expensive calibration. */
final class WeeklySnapshotFixture {
    static JSONObject verify(ModelEngine model, File cache) throws Exception {
        File directory = new File(cache, "snapshot-fixture-" + java.util.UUID.randomUUID());
        if (!directory.mkdir()) throw new IllegalStateException("snapshot fixture directory unavailable");
        try (SQLiteDatabase source = SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(source);
            ColdTrajectory.register(source, model.modelVersion, model.normalizerDigest, model.tokens, model.coldBundle());
            source.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY, payload TEXT)");
            source.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)");
            long now = 31L * 86400000, hotStart = now - 48L * 3600000, warmStart = now - 30L * 86400000;
            for (long instant : new long[]{hotStart, hotStart + 1, now, now + 1})
                source.execSQL("INSERT INTO frames VALUES (?,?)", new Object[]{instant, "synthetic_hot_" + instant});
            for (long instant : new long[]{warmStart, warmStart + 1, now, now + 1})
                source.execSQL("INSERT INTO warm_trajectory VALUES (?,?,?,1)", new Object[]{instant, instant, "synthetic_warm_" + instant});
            source.execSQL("INSERT INTO cold_edges VALUES (?,?,?,7)", new Object[]{model.modelVersion, model.tokens[0], model.tokens[0]});
            ColdLineage.migrateOnce(source);
            JSONObject sourceAudit = NativeColdAudit.run(source);
            String coldDigest = sourceAudit.getString("source_sha256");
            String lineageDigest = sourceAudit.getJSONObject("lineage_graph").getString("source_sha256");
            try (WeeklySnapshot snapshot = WeeklySnapshot.copy(source, directory, now)) {
                if (source.inTransaction() || !snapshot.warmIncluded
                        || DatabaseUtils.queryNumEntries(snapshot.database, "frames") != 2
                        || DatabaseUtils.queryNumEntries(snapshot.database, "warm_trajectory") != 2
                        || !coldDigest.equals(NativeColdAudit.run(snapshot.database).getString("source_sha256"))
                        || !lineageDigest.equals(NativeColdAudit.run(snapshot.database).getJSONObject("lineage_graph").getString("source_sha256")))
                    throw new IllegalStateException("snapshot windows, cold source digest or source lock mismatch");
                source.execSQL("DELETE FROM frames"); source.execSQL("DELETE FROM warm_trajectory"); source.execSQL("DELETE FROM cold_edges"); source.execSQL("DELETE FROM cold_lineage_edges");
                if (DatabaseUtils.queryNumEntries(snapshot.database, "frames") != 2
                        || DatabaseUtils.queryNumEntries(snapshot.database, "warm_trajectory") != 2
                        || ColdTrajectory.transitions(snapshot.database) != 7
                        || NativeColdAudit.run(snapshot.database).getJSONObject("lineage_graph").getLong("transitions") != 7)
                    throw new IllegalStateException("source mutation changed isolated snapshot");
                boolean protectedWrites = false;
                try { snapshot.database.execSQL("DELETE FROM frames"); }
                catch (android.database.sqlite.SQLiteException expected) { protectedWrites = true; }
                if (!protectedWrites) throw new IllegalStateException("snapshot writes accepted");
            }
            empty(directory);
            source.execSQL("INSERT INTO warm_trajectory VALUES (?,?,?,1.5)", new Object[]{now, now, "synthetic_invalid_count"});
            rejected(source, directory, now);
            source.execSQL("DELETE FROM warm_trajectory");
            source.execSQL("INSERT INTO cold_edges VALUES (?,?,?,1.5)", new Object[]{model.modelVersion, model.tokens[0], model.tokens[0]});
            rejected(source, directory, now);
            source.execSQL("DELETE FROM cold_edges");
            source.execSQL("INSERT INTO frames VALUES (?,?)", new Object[]{now, new byte[]{123,125}});
            rejected(source, directory, now);
            source.execSQL("DELETE FROM frames");
            String modelBundle = DatabaseUtils.stringForQuery(source, "SELECT bundle FROM cold_models WHERE namespace=?", new String[]{model.modelVersion});
            source.execSQL("UPDATE cold_models SET bundle=?", new Object[]{modelBundle.getBytes(java.nio.charset.StandardCharsets.UTF_8)});
            rejected(source, directory, now);
            source.execSQL("UPDATE cold_models SET bundle=?", new Object[]{modelBundle});
            boolean cancelled = false;
            try { Thread.currentThread().interrupt(); try (WeeklySnapshot ignored = WeeklySnapshot.copy(source, directory, now)) {} }
            catch (InterruptedException expected) { cancelled = true; }
            finally { Thread.interrupted(); }
            if (!cancelled || source.inTransaction()) throw new IllegalStateException("snapshot cancellation or source transaction leak");
            empty(directory);
            source.execSQL("DROP TABLE warm_trajectory");
            try (WeeklySnapshot snapshot = WeeklySnapshot.copy(source, directory, now)) {
                if (snapshot.warmIncluded) throw new IllegalStateException("missing warm source reported included");
            }
            empty(directory);
            return new JSONObject().put("passed", true).put("retention_window_boundaries_verified", true)
                .put("lineage_graph_digest_and_isolation_verified", true).put("cold_source_digest_preserved", true).put("later_source_mutation_isolated", true)
                .put("snapshot_write_rejected", true).put("fractional_warm_cold_counts_rejected", true)
                .put("blob_hot_and_model_payload_rejected", true).put("cancelled_transaction_released", true).put("temporary_files_cleaned", true)
                .put("missing_warm_table_distinguished", true).put("scope", "synthetic_short_snapshot_fixture_not_full_scale_or_crash_recovery");
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) SQLiteDatabase.deleteDatabase(file);
            directory.delete();
        }
    }
    private static void rejected(SQLiteDatabase source, File directory, long now) throws Exception {
        boolean rejected = false;
        try (WeeklySnapshot ignored = WeeklySnapshot.copy(source, directory, now)) {}
        catch (IllegalArgumentException | android.database.sqlite.SQLiteException expected) { rejected = true; }
        if (!rejected || source.inTransaction()) throw new IllegalStateException("invalid snapshot accepted or source transaction leak");
        empty(directory);
    }
    private static void empty(File directory) {
        File[] files = directory.listFiles();
        if (files == null || files.length != 0) throw new IllegalStateException("snapshot files retained after close or failure");
    }
}
