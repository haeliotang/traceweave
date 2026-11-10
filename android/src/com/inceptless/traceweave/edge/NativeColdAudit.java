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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Bounded relational audit of the retained graph, without replaying Hot history. */
final class NativeColdAudit {
    static JSONObject run(SQLiteDatabase database) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 35) throw new UnsupportedOperationException("protected cold audit requires Android 35 read-only transactions");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("traceform.native-cold-source.v1\n".getBytes(StandardCharsets.US_ASCII));
        Map<String, Set<String>> vocabulary = new HashMap<>(), states = new HashMap<>();
        long edgeCount = 0, transitions = 0;
        database.beginTransactionReadOnly();
        try {
            int modelLimit = ColdLineage.modelLimit(database);
            text(digest, "cold_models");
            try (Cursor rows = database.rawQuery("SELECT namespace,normalizer,bundle FROM cold_models ORDER BY namespace", null)) {
                while (rows.moveToNext()) {
                    cancelled();
                    if (vocabulary.size() >= modelLimit) throw new IllegalArgumentException("bounded cold model registry required");
                    String namespace = column(rows, 0), normalizer = column(rows, 1), encoded = column(rows, 2);
                    text(digest, namespace); text(digest, normalizer); text(digest, encoded);
                    JSONObject model = new JSONObject(encoded);
                    if (!namespace.matches("[0-9a-f]{64}") || !normalizer.matches("[0-9a-f]{64}")
                            || !namespace.equals(model.getString("model_version"))
                            || !normalizer.equals(model.getString("normalizer_sha256"))
                            || !model.getString("model_sha256").matches("[0-9a-f]{64}"))
                        throw new IllegalArgumentException("cold model identity binding mismatch");
                    JSONArray tokens = model.getJSONArray("tokens");
                    if (tokens.length() < 1 || tokens.length() > 16) throw new IllegalArgumentException("bounded cold vocabulary required");
                    Set<String> expected = new HashSet<>();
                    for (int index = 0; index < tokens.length(); index++) {
                        Object value = tokens.get(index);
                        if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > 64
                                || !expected.add((String) value)) throw new IllegalArgumentException("invalid cold model vocabulary");
                    }
                    if (vocabulary.put(namespace, expected) != null) throw new IllegalArgumentException("duplicate cold model identity");
                    states.put(namespace, new HashSet<>());
                }
            }
            text(digest, "cold_states");
            int stateCount = 0;
            try (Cursor rows = database.rawQuery("SELECT namespace,token FROM cold_states ORDER BY namespace,token", null)) {
                while (rows.moveToNext()) {
                    cancelled();
                    if (++stateCount > 16 * modelLimit) throw new IllegalArgumentException("bounded cold states required");
                    String namespace = column(rows, 0), token = column(rows, 1);
                    text(digest, namespace); text(digest, token);
                    Set<String> registered = states.get(namespace);
                    if (registered == null || !vocabulary.get(namespace).contains(token) || !registered.add(token))
                        throw new IllegalArgumentException("unbound or duplicate cold state");
                }
            }
            for (String namespace : vocabulary.keySet())
                if (!states.get(namespace).equals(vocabulary.get(namespace))) throw new IllegalArgumentException("incomplete cold model vocabulary");
            text(digest, "cold_edges");
            Set<String> edges = new HashSet<>();
            try (Cursor rows = database.rawQuery("SELECT namespace,source,target,n FROM cold_edges ORDER BY namespace,source,target", null)) {
                while (rows.moveToNext()) {
                    cancelled();
                    if (++edgeCount > 256 * modelLimit) throw new IllegalArgumentException("bounded cold edges required");
                    String namespace = column(rows, 0), source = column(rows, 1), target = column(rows, 2);
                    if (rows.getType(3) != Cursor.FIELD_TYPE_INTEGER || rows.getLong(3) < 0)
                        throw new IllegalArgumentException("nonnegative integer cold count required");
                    long count = rows.getLong(3);
                    text(digest, namespace); text(digest, source); text(digest, target);
                    digest.update(ByteBuffer.allocate(8).putLong(count).array());
                    Set<String> registered = states.get(namespace);
                    if (registered == null || !registered.contains(source) || !registered.contains(target)
                            || !edges.add(new JSONArray().put(namespace).put(source).put(target).toString()))
                        throw new IllegalArgumentException("unbound or duplicate cold edge");
                    transitions = Math.addExact(transitions, count);
                }
            }
            JSONObject lineage = ColdLineage.snapshot(database);
            database.setTransactionSuccessful();
            return new JSONObject().put("protocol", "traceform.native-cold-audit.v1").put("valid", true)
                .put("models", vocabulary.size()).put("states", stateCount).put("edges", edgeCount).put("transitions", transitions)
                .put("source_sha256", HexDigest.encode(digest.digest())).put("lineage_graph", lineage == null ? JSONObject.NULL : lineage)
                .put("scope", "retained_graph_identity_vocabulary_count_binding_not_model_content_authentication_or_history_replay");
        } finally { database.endTransaction(); }
    }
    private static String column(Cursor rows, int index) {
        if (rows.getType(index) != Cursor.FIELD_TYPE_STRING) throw new IllegalArgumentException("cold identity and bundle TEXT required");
        return rows.getString(index);
    }
    private static void text(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    private static void cancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cold audit cancelled");
    }

    static JSONObject verify(ModelEngine model) throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(db);
            ColdTrajectory.register(db, model.modelVersion, model.normalizerDigest, model.tokens, model.coldBundle());
            db.execSQL("INSERT INTO cold_edges VALUES (?,?,?,7)", new Object[]{model.modelVersion, model.tokens[0], model.tokens[0]});
            JSONObject clean = run(db);
            if (clean.getLong("transitions") != 7 || !clean.getString("source_sha256").equals(run(db).getString("source_sha256")))
                throw new IllegalStateException("cold count or digest instability");
            for (String count : new String[]{"1.5", "-1"}) {
                db.execSQL("UPDATE cold_edges SET n=" + count);
                rejected(db);
            }
            db.execSQL("UPDATE cold_edges SET n=8");
            if (clean.getString("source_sha256").equals(run(db).getString("source_sha256"))) throw new IllegalStateException("cold count change lost");
            db.execSQL("UPDATE cold_edges SET target='foreign'"); rejected(db);
            db.execSQL("UPDATE cold_edges SET target=?", new Object[]{model.tokens[0]});
            db.execSQL("DELETE FROM cold_states WHERE token=?", new Object[]{model.tokens[0]}); rejected(db);
            db.execSQL("INSERT INTO cold_states VALUES (?,?)", new Object[]{model.modelVersion, model.tokens[0]});
            db.execSQL("UPDATE cold_models SET normalizer=?", new Object[]{String.format("%064d", 9)}); rejected(db);
            boolean interrupted = false;
            try { Thread.currentThread().interrupt(); run(db); }
            catch (InterruptedException expected) { interrupted = true; }
            finally { Thread.interrupted(); }
            if (!interrupted || db.inTransaction()) throw new IllegalStateException("cold cancellation transaction leak");
            return new JSONObject().put("passed", true).put("count_type_sign_rejected", true)
                .put("state_edge_model_binding_rejected", true).put("digest_stability_and_change_verified", true)
                .put("cancellation_transaction_release", true).put("scope", "synthetic_cold_graph_fixture_not_user_history");
        }
    }
    private static void rejected(SQLiteDatabase db) throws Exception {
        boolean rejected = false;
        try { run(db); } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected || db.inTransaction()) throw new IllegalStateException("invalid cold graph accepted or transaction retained");
    }
}
