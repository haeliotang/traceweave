package com.inceptless.traceweave.edge;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;

/** Private disposable source-consistent input, with writes disabled after copy. */
final class WeeklySnapshot implements AutoCloseable {
    final SQLiteDatabase database;
    final boolean warmIncluded;
    private final File path;
    private WeeklySnapshot(SQLiteDatabase database, File path, boolean warmIncluded) {
        this.database = database; this.path = path; this.warmIncluded = warmIncluded;
    }

    static WeeklySnapshot copy(SQLiteDatabase source, File directory, long asOf) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 35) throw new UnsupportedOperationException("protected weekly snapshot requires Android 35 read-only transactions");
        if (source.inTransaction()) throw new IllegalStateException("snapshot requires its own source transaction");
        File path = new File(directory, "weekly-" + java.util.UUID.randomUUID() + ".sqlite");
        SQLiteDatabase target = null;
        boolean complete = false;
        boolean warmIncluded = false;
        try {
            target = SQLiteDatabase.openOrCreateDatabase(path, null);
            target.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY, payload TEXT)");
            target.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)");
            ColdTrajectory.initialize(target);
            source.beginTransactionReadOnly();
            try {
                target.beginTransaction();
                try {
                    int modelLimit = ColdLineage.modelLimit(source);
                    int models = 0;
                    try (Cursor rows = source.rawQuery("SELECT namespace,normalizer,bundle FROM cold_models ORDER BY namespace", null)) {
                        while (rows.moveToNext()) {
                            if (++models > modelLimit) throw new IllegalArgumentException("bounded source registry required");
                            target.execSQL("INSERT INTO cold_models VALUES (?,?,?)", new Object[]{rows.getString(0), rows.getString(1), rows.getString(2)});
                        }
                    }
                    int states = 0;
                    try (Cursor rows = source.rawQuery("SELECT namespace,token FROM cold_states ORDER BY namespace,token", null)) {
                        while (rows.moveToNext()) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cold state snapshot cancelled");
                            if (++states > 16 * modelLimit || rows.getType(0) != Cursor.FIELD_TYPE_STRING || rows.getType(1) != Cursor.FIELD_TYPE_STRING)
                                throw new IllegalArgumentException("bounded text cold state identities required");
                            target.execSQL("INSERT INTO cold_states VALUES (?,?)", new Object[]{rows.getString(0), rows.getString(1)});
                        }
                    }
                    int edges = 0;
                    try (Cursor rows = source.rawQuery("SELECT namespace,source,target,n FROM cold_edges ORDER BY namespace,source,target", null)) {
                        while (rows.moveToNext()) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cold edge snapshot cancelled");
                            if (++edges > 256 * modelLimit || rows.getType(0) != Cursor.FIELD_TYPE_STRING || rows.getType(1) != Cursor.FIELD_TYPE_STRING
                                    || rows.getType(2) != Cursor.FIELD_TYPE_STRING || rows.getType(3) != Cursor.FIELD_TYPE_INTEGER || rows.getLong(3) < 0)
                                throw new IllegalArgumentException("bounded cold graph with integer nonnegative counts required");
                            target.execSQL("INSERT INTO cold_edges VALUES (?,?,?,?)",
                                new Object[]{rows.getString(0), rows.getString(1), rows.getString(2), rows.getLong(3)});
                        }
                    }
                    ColdLineage.copySnapshot(source, target);
                    long copied = 0;
                    long start = Math.subtractExact(asOf, 48L * 3600000);
                    try (Cursor rows = source.rawQuery("SELECT ts,payload FROM frames WHERE ts>? AND ts<=? ORDER BY ts",
                            new String[]{Long.toString(start), Long.toString(asOf)})) {
                        while (rows.moveToNext()) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("snapshot copy cancelled");
                            if (++copied > 172800) throw new IllegalArgumentException("snapshot exceeds 48h 1Hz record bound");
                            target.execSQL("INSERT INTO frames VALUES (?,?)", new Object[]{rows.getLong(0), rows.getString(1)});
                        }
                    }
                    warmIncluded = android.database.DatabaseUtils.longForQuery(source,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='warm_trajectory'", null) == 1;
                    if (warmIncluded) {
                        long warmCopied = 0;
                        long warmStart = Math.subtractExact(asOf, 30L * 86400000);
                        try (Cursor rows = source.rawQuery("SELECT bucket,ts,payload,n FROM warm_trajectory WHERE ts>? AND ts<=? ORDER BY bucket",
                                new String[]{Long.toString(warmStart), Long.toString(asOf)})) {
                            while (rows.moveToNext()) {
                                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("warm snapshot copy cancelled");
                                if (++warmCopied > 86400) throw new IllegalArgumentException("snapshot exceeds 30d 30s warm record bound");
                                if (rows.getType(0) != Cursor.FIELD_TYPE_INTEGER || rows.getType(1) != Cursor.FIELD_TYPE_INTEGER
                                        || rows.getType(3) != Cursor.FIELD_TYPE_INTEGER || rows.getType(2) != Cursor.FIELD_TYPE_STRING)
                                    throw new IllegalArgumentException("warm snapshot requires original integer clocks/counts and text payload");
                                target.execSQL("INSERT INTO warm_trajectory VALUES (?,?,?,?)",
                                    new Object[]{rows.getLong(0), rows.getLong(1), rows.getString(2), rows.getLong(3)});
                            }
                        }
                    }
                    target.setTransactionSuccessful();
                } finally { target.endTransaction(); }
                source.setTransactionSuccessful();
            } finally { source.endTransaction(); }
            target.execSQL("PRAGMA query_only=ON");
            complete = true;
            return new WeeklySnapshot(target, path, warmIncluded);
        } finally {
            if (!complete) {
                if (target != null) target.close();
                SQLiteDatabase.deleteDatabase(path);
            }
        }
    }
    @Override public void close() {
        try { database.close(); }
        finally { SQLiteDatabase.deleteDatabase(path); }
    }
}
