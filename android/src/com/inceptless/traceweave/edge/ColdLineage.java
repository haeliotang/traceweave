package com.inceptless.traceweave.edge;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One atomic conversion of legacy version graphs to explicit label lineages. */
final class ColdLineage {
    static int modelLimit(SQLiteDatabase db) {
        if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='cold_lineage_metadata'",null) == 0) return 4;
        if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM cold_lineage_metadata WHERE key='version_graph_migration'",null) == 0) return 4;
        if (!"explicit_label_lineage_not_state_semantic_equivalence".equals(DatabaseUtils.stringForQuery(db,
                "SELECT value FROM cold_lineage_metadata WHERE key='version_graph_migration'",null)))
            throw new IllegalArgumentException("known lineage migration marker required");
        return 8;
    }
    private static void initialize(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS cold_lineage_metadata (key TEXT PRIMARY KEY,value TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS cold_lineages (namespace TEXT PRIMARY KEY,normalizer TEXT,tokens TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS cold_model_lineages (version TEXT PRIMARY KEY,namespace TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS cold_lineage_edges (namespace TEXT,source TEXT,target TEXT,n INTEGER,PRIMARY KEY(namespace,source,target))");
    }
    static void copySnapshot(SQLiteDatabase source, SQLiteDatabase target) throws Exception {
        if (!source.inTransaction() || !target.inTransaction()) throw new IllegalStateException("lineage copy requires source and target transactions");
        JSONObject graph = snapshot(source);
        if (graph == null) return;
        initialize(target);
        JSONArray lineages = graph.getJSONArray("lineages"), models = graph.getJSONArray("models"), edges = graph.getJSONArray("edges");
        for (int index = 0; index < lineages.length(); index++) {
            cancelled(); JSONObject lineage = lineages.getJSONObject(index);
            target.execSQL("INSERT INTO cold_lineages VALUES (?,?,?)",new Object[]{lineage.getString("namespace"),lineage.getString("normalizer_sha256"),lineage.getJSONArray("tokens").toString()});
        }
        for (int index = 0; index < models.length(); index++) {
            cancelled(); JSONObject model = models.getJSONObject(index);
            target.execSQL("INSERT INTO cold_model_lineages VALUES (?,?)",new Object[]{model.getString("version"),model.getString("namespace")});
        }
        for (int index = 0; index < edges.length(); index++) {
            cancelled(); JSONObject edge = edges.getJSONObject(index);
            target.execSQL("INSERT INTO cold_lineage_edges VALUES (?,?,?,?)",new Object[]{edge.getString("namespace"),edge.getString("source"),edge.getString("target"),edge.getLong("count")});
        }
        target.execSQL("INSERT INTO cold_lineage_metadata VALUES ('version_graph_migration','explicit_label_lineage_not_state_semantic_equivalence')");
        if (!graph.getString("source_sha256").equals(snapshot(target).getString("source_sha256")))
            throw new IllegalStateException("lineage snapshot digest changed during copy");
    }
    static void migrateOnce(SQLiteDatabase db) throws Exception {
        if (db.inTransaction()) throw new IllegalStateException("lineage migration requires its own transaction");
        db.beginTransaction();
        try {
            initialize(db);
            if (DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM cold_lineage_metadata WHERE key='version_graph_migration'", null) != 0) {
                db.setTransactionSuccessful(); return;
            }
            if (DatabaseUtils.queryNumEntries(db, "cold_lineages") != 0 || DatabaseUtils.queryNumEntries(db, "cold_model_lineages") != 0
                    || DatabaseUtils.queryNumEntries(db, "cold_lineage_edges") != 0)
                throw new IllegalArgumentException("unmarked lineage data must not be replayed");
            NativeColdAudit.run(db);
            Map<String,String> versions = new HashMap<>(), normalizers = new HashMap<>();
            Map<String,Set<String>> vocabularies = new HashMap<>();
            try (Cursor rows = db.rawQuery("SELECT namespace,normalizer,bundle FROM cold_models ORDER BY namespace", null)) {
                while (rows.moveToNext()) {
                    cancelled();
                    String version = rows.getString(0), normalizer = rows.getString(1);
                    JSONObject model = new JSONObject(rows.getString(2));
                    String lineage = model.optString("cold_state_namespace", version);
                    if (!lineage.matches("[0-9a-f]{64}") || model.has("cold_state_namespace") != model.has("token_migration"))
                        throw new IllegalArgumentException("paired bounded lineage binding required");
                    JSONArray tokens = model.getJSONArray("tokens"); Set<String> vocabulary = new HashSet<>();
                    for (int index = 0; index < tokens.length(); index++) vocabulary.add(tokens.getString(index));
                    if (model.has("token_migration")) {
                        JSONObject mapping = model.getJSONObject("token_migration");
                        if (mapping.length() != vocabulary.size()) throw new IllegalArgumentException("complete lineage mapping required");
                        for (String token : vocabulary)
                            if (!(mapping.get(token) instanceof String) || !token.equals(mapping.getString(token)))
                                throw new IllegalArgumentException("identity node lineage required");
                    }
                    if (normalizers.containsKey(lineage)) {
                        if (!normalizer.equals(normalizers.get(lineage)) || !vocabulary.equals(vocabularies.get(lineage)))
                            throw new IllegalArgumentException("lineage normalizer or vocabulary mismatch");
                    } else {
                        if (normalizers.size() >= 4) throw new IllegalArgumentException("four cold lineages maximum");
                        normalizers.put(lineage, normalizer); vocabularies.put(lineage, vocabulary);
                        db.execSQL("INSERT INTO cold_lineages VALUES (?,?,?)", new Object[]{lineage, normalizer, tokens.toString()});
                    }
                    versions.put(version, lineage);
                    db.execSQL("INSERT INTO cold_model_lineages VALUES (?,?)", new Object[]{version, lineage});
                }
            }
            try (Cursor rows = db.rawQuery("SELECT namespace,source,target,n FROM cold_edges ORDER BY namespace,source,target", null)) {
                while (rows.moveToNext()) {
                    cancelled();
                    String lineage = versions.get(rows.getString(0)), source = rows.getString(1), target = rows.getString(2);
                    long old = 0;
                    try (Cursor existing = db.rawQuery("SELECT n FROM cold_lineage_edges WHERE namespace=? AND source=? AND target=?", new String[]{lineage,source,target})) {
                        if (existing.moveToFirst()) old = existing.getLong(0);
                    }
                    long count = Math.addExact(old, rows.getLong(3));
                    db.execSQL("INSERT OR REPLACE INTO cold_lineage_edges VALUES (?,?,?,?)", new Object[]{lineage,source,target,count});
                }
            }
            db.execSQL("INSERT INTO cold_lineage_metadata VALUES ('version_graph_migration','explicit_label_lineage_not_state_semantic_equivalence')");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    static JSONObject snapshot(SQLiteDatabase db) throws Exception {
        if (!db.inTransaction()) throw new IllegalStateException("lineage snapshot requires source transaction");
        if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='cold_lineage_metadata'",null) == 0) return null;
        if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM cold_lineage_metadata WHERE key='version_graph_migration'",null) == 0) {
            if (DatabaseUtils.queryNumEntries(db,"cold_lineages") != 0 || DatabaseUtils.queryNumEntries(db,"cold_model_lineages") != 0
                    || DatabaseUtils.queryNumEntries(db,"cold_lineage_edges") != 0) throw new IllegalArgumentException("unmarked lineage graph");
            return null;
        }
        try (Cursor marker = db.rawQuery("SELECT value FROM cold_lineage_metadata WHERE key='version_graph_migration'",null)) {
            if (!marker.moveToFirst() || !"explicit_label_lineage_not_state_semantic_equivalence".equals(text(marker,0)))
                throw new IllegalArgumentException("known lineage migration marker required");
        }
        JSONArray lineages = new JSONArray(), models = new JSONArray(), edges = new JSONArray();
        Map<String,Set<String>> vocabularies = new HashMap<>(); Map<String,String> normalizers = new HashMap<>();
        try (Cursor rows = db.rawQuery("SELECT namespace,normalizer,tokens FROM cold_lineages ORDER BY namespace",null)) {
            while (rows.moveToNext()) {
                cancelled();
                String namespace = text(rows,0), normalizer = text(rows,1), encoded = text(rows,2);
                if (vocabularies.size() >= 4 || !namespace.matches("[0-9a-f]{64}") || !normalizer.matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("bounded lineage identities required");
                JSONArray tokens = new JSONArray(encoded); Set<String> vocabulary = new HashSet<>();
                if (tokens.length() < 1 || tokens.length() > 16) throw new IllegalArgumentException("bounded lineage vocabulary required");
                for (int index = 0; index < tokens.length(); index++) {
                    Object token = tokens.get(index);
                    if (!(token instanceof String) || ((String)token).isEmpty() || ((String)token).length() > 64 || !vocabulary.add((String)token))
                        throw new IllegalArgumentException("unique lineage tokens required");
                }
                if (vocabularies.put(namespace,vocabulary) != null) throw new IllegalArgumentException("duplicate lineage identity");
                normalizers.put(namespace,normalizer);
                lineages.put(new JSONObject().put("namespace",namespace).put("normalizer_sha256",normalizer).put("tokens",tokens));
            }
        }
        try (Cursor rows = db.rawQuery("SELECT version,namespace FROM cold_model_lineages ORDER BY version",null)) {
            while (rows.moveToNext()) {
                cancelled(); String version = text(rows,0), namespace = text(rows,1);
                if (models.length() >= 8 || !version.matches("[0-9a-f]{64}") || !vocabularies.containsKey(namespace))
                    throw new IllegalArgumentException("bounded registered lineage mapping required");
                try (Cursor binding = db.rawQuery("SELECT normalizer,bundle FROM cold_models WHERE namespace=?",new String[]{version})) {
                    if (!binding.moveToFirst() || !normalizers.get(namespace).equals(text(binding,0))) throw new IllegalArgumentException("lineage model binding required");
                    JSONObject model = new JSONObject(text(binding,1));
                    JSONArray tokens = model.getJSONArray("tokens"); Set<String> vocabulary = new HashSet<>();
                    for (int index = 0; index < tokens.length(); index++) vocabulary.add(tokens.getString(index));
                    if (!namespace.equals(model.optString("cold_state_namespace",version)) || !vocabulary.equals(vocabularies.get(namespace)))
                        throw new IllegalArgumentException("lineage model vocabulary or identity mismatch");
                }
                models.put(new JSONObject().put("version",version).put("namespace",namespace));
            }
        }
        if (models.length() != DatabaseUtils.queryNumEntries(db,"cold_models")) throw new IllegalArgumentException("incomplete model lineage mapping");
        long transitions = 0;
        try (Cursor rows = db.rawQuery("SELECT namespace,source,target,n FROM cold_lineage_edges ORDER BY namespace,source,target",null)) {
            while (rows.moveToNext()) {
                cancelled(); String namespace = text(rows,0), source = text(rows,1), target = text(rows,2);
                Set<String> vocabulary = vocabularies.get(namespace);
                if (edges.length() >= 1024 || vocabulary == null || !vocabulary.contains(source) || !vocabulary.contains(target)
                        || rows.getType(3) != Cursor.FIELD_TYPE_INTEGER || rows.getLong(3) < 0)
                    throw new IllegalArgumentException("bound lineage edge and integer count required");
                long count = rows.getLong(3); transitions = Math.addExact(transitions,count);
                edges.put(new JSONObject().put("namespace",namespace).put("source",source).put("target",target).put("count",count));
            }
        }
        JSONObject result = new JSONObject().put("protocol","traceform.native-cold-lineage.v1").put("lineages",lineages)
            .put("models",models).put("edges",edges).put("transitions",transitions)
            .put("scope","explicit_node_label_lineage_not_model_probability_or_human_state_equivalence");
        String digest = HexDigest.encode(java.security.MessageDigest.getInstance("SHA-256").digest(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return result.put("source_sha256",digest);
    }
    private static String text(Cursor row, int column) {
        if (row.getType(column) != Cursor.FIELD_TYPE_STRING) throw new IllegalArgumentException("lineage TEXT column required");
        return row.getString(column);
    }
    static void registerModel(SQLiteDatabase db, String version, String normalizer, String[] tokens, JSONObject bundle) throws Exception {
        if (!db.inTransaction()) throw new IllegalStateException("lineage admission requires model transaction");
        if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='cold_lineage_metadata'",null) == 0) return;
        if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM cold_lineage_metadata WHERE key='version_graph_migration'",null) != 1)
            throw new IllegalStateException("lineage migration incomplete");
        String lineage = bundle.optString("cold_state_namespace",version);
        if (!lineage.matches("[0-9a-f]{64}") || !version.equals(bundle.getString("model_version"))
                || !normalizer.equals(bundle.getString("normalizer_sha256"))
                || bundle.has("cold_state_namespace") != bundle.has("token_migration"))
            throw new IllegalArgumentException("model-bound lineage admission required");
        Set<String> vocabulary = new HashSet<>(java.util.Arrays.asList(tokens));
        JSONArray encoded = bundle.getJSONArray("tokens"); Set<String> declared = new HashSet<>();
        for (int index = 0; index < encoded.length(); index++) {
            if (!(encoded.get(index) instanceof String) || !declared.add(encoded.getString(index)))
                throw new IllegalArgumentException("unique lineage model vocabulary required");
        }
        if (!declared.equals(vocabulary)) throw new IllegalArgumentException("lineage registration vocabulary mismatch");
        if (bundle.has("token_migration")) {
            JSONObject mapping = bundle.getJSONObject("token_migration");
            if (mapping.length() != vocabulary.size()) throw new IllegalArgumentException("complete lineage mapping required");
            for (String token : vocabulary)
                if (!(mapping.get(token) instanceof String) || !token.equals(mapping.getString(token)))
                    throw new IllegalArgumentException("warm-start identity lineage required");
        }
        try (Cursor row = db.rawQuery("SELECT normalizer,tokens FROM cold_lineages WHERE namespace=?",new String[]{lineage})) {
            if (row.moveToFirst()) {
                JSONArray stored = new JSONArray(text(row,1)); Set<String> registered = new HashSet<>();
                for (int index = 0; index < stored.length(); index++) registered.add(stored.getString(index));
                if (!normalizer.equals(text(row,0)) || !vocabulary.equals(registered)) throw new IllegalArgumentException("lineage normalizer or vocabulary mismatch");
            } else {
                if (DatabaseUtils.queryNumEntries(db,"cold_lineages") >= 4) throw new IllegalArgumentException("four cold lineages maximum");
                db.execSQL("INSERT INTO cold_lineages VALUES (?,?,?)",new Object[]{lineage,normalizer,new JSONArray(tokens).toString()});
            }
        }
        try (Cursor row = db.rawQuery("SELECT namespace FROM cold_model_lineages WHERE version=?",new String[]{version})) {
            if (row.moveToFirst() && !lineage.equals(text(row,0))) throw new IllegalArgumentException("immutable model lineage binding required");
        }
        db.execSQL("INSERT OR IGNORE INTO cold_model_lineages VALUES (?,?)",new Object[]{version,lineage});
    }
    static void appendRegistered(SQLiteDatabase db, String version, String source, String target) throws Exception {
        if (!db.inTransaction()) throw new IllegalStateException("lineage count must share frame transaction");
        if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='cold_lineage_metadata'",null) == 0) return;
        if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM cold_lineage_metadata WHERE key='version_graph_migration'",null) != 1)
            throw new IllegalStateException("lineage migration incomplete");
        String lineage = DatabaseUtils.stringForQuery(db,"SELECT namespace FROM cold_model_lineages WHERE version=?",new String[]{version});
        JSONArray vocabulary = new JSONArray(DatabaseUtils.stringForQuery(db,"SELECT tokens FROM cold_lineages WHERE namespace=?",new String[]{lineage}));
        boolean hasSource = false, hasTarget = false;
        for (int index = 0; index < vocabulary.length(); index++) {
            hasSource |= source.equals(vocabulary.getString(index)); hasTarget |= target.equals(vocabulary.getString(index));
        }
        if (!hasSource || !hasTarget) throw new IllegalArgumentException("unbound lineage edge");
        long count = 0;
        try (Cursor row = db.rawQuery("SELECT n FROM cold_lineage_edges WHERE namespace=? AND source=? AND target=?",new String[]{lineage,source,target})) {
            if (row.moveToFirst()) {
                if (row.getType(0) != Cursor.FIELD_TYPE_INTEGER || row.getLong(0) < 0) throw new IllegalArgumentException("integer lineage count required");
                count = row.getLong(0);
            }
        }
        db.execSQL("INSERT OR REPLACE INTO cold_lineage_edges VALUES (?,?,?,?)",new Object[]{lineage,source,target,Math.addExact(count,1)});
    }
    private static void cancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cold lineage migration cancelled");
    }
    static Set<String> retainedVersions(SQLiteDatabase db) throws Exception {
        if (!db.inTransaction()) throw new IllegalStateException("retained references require a source transaction");
        Set<String> versions = new HashSet<>();
        for (String table : new String[]{"frames","warm_trajectory"}) {
            if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",new String[]{table}) != 1)
                throw new IllegalArgumentException("complete retained source tables required for pruning");
            long maximum = table.equals("frames") ? 172800 : 86400;
            if (DatabaseUtils.queryNumEntries(db,table) > maximum) throw new IllegalArgumentException("bounded retained source required for references");
            try (Cursor rows = db.rawQuery("SELECT DISTINCT json_extract(payload,'$.model_version') FROM " + table,null)) {
                while (rows.moveToNext()) {
                    cancelled(); String version = text(rows,0);
                    if (!version.matches("[0-9a-f]{64}") || DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM cold_models WHERE namespace=?",new String[]{version}) != 1)
                        throw new IllegalArgumentException("retained model reference is unbound");
                    versions.add(version);
                }
            }
        }
        return versions;
    }
    static int pruneVersions(SQLiteDatabase db, String activeVersion, String rollbackVersion) throws Exception {
        if (db.inTransaction()) throw new IllegalStateException("version pruning requires its own transaction");
        db.beginTransaction();
        try {
            int retired = pruneVersionsInTransaction(db,activeVersion,rollbackVersion);
            db.setTransactionSuccessful(); return retired;
        } finally { db.endTransaction(); }
    }
    static int pruneVersionsInTransaction(SQLiteDatabase db, String activeVersion, String rollbackVersion) throws Exception {
        if (!db.inTransaction()) throw new IllegalStateException("version pruning requires an admission transaction");
        if (activeVersion == null || !activeVersion.matches("[0-9a-f]{64}")
                || rollbackVersion != null && !rollbackVersion.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("protected registry versions required");
        if (snapshot(db) == null) throw new IllegalStateException("completed lineage migration required before pruning");
        if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM cold_models WHERE namespace=?",new String[]{activeVersion}) != 1)
            throw new IllegalArgumentException("active model must be registered before pruning");
        Set<String> protectedVersions = new HashSet<>(); protectedVersions.add(activeVersion);
        if (rollbackVersion != null) protectedVersions.add(rollbackVersion);
        protectedVersions.addAll(retainedVersions(db));
        java.util.List<String> retired = new java.util.ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT namespace FROM cold_models ORDER BY namespace",null)) {
            while (rows.moveToNext()) { cancelled(); String version = text(rows,0); if (!protectedVersions.contains(version)) retired.add(version); }
        }
        for (String version : retired) {
            cancelled(); Object[] identity = {version};
            db.execSQL("DELETE FROM cold_edges WHERE namespace=?",identity);
            db.execSQL("DELETE FROM cold_states WHERE namespace=?",identity);
            db.execSQL("DELETE FROM cold_model_lineages WHERE version=?",identity);
            db.execSQL("DELETE FROM cold_models WHERE namespace=?",identity);
        }
        snapshot(db);
        return retired.size();
    }
    static JSONObject verifyRetention(ModelEngine model) throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(db);
            String second = String.format("%064d",2);
            JSONObject bundle = new JSONObject(model.coldBundle().toString()).put("model_version",second).put("cold_state_namespace",model.modelVersion);
            JSONObject mapping = new JSONObject(); for (String token : model.tokens) mapping.put(token,token);
            bundle.put("token_migration",mapping);
            ColdTrajectory.register(db,model.modelVersion,model.normalizerDigest,model.tokens,model.coldBundle());
            ColdTrajectory.register(db,second,model.normalizerDigest,model.tokens,bundle);
            for (String version : new String[]{model.modelVersion,second})
                db.execSQL("INSERT INTO cold_edges VALUES (?,?,?,7)",new Object[]{version,model.tokens[0],model.tokens[0]});
            JSONObject legacyCounts = ColdTrajectory.countSummary(db);
            if (legacyCounts.getBoolean("cumulative_lineage_available") || !legacyCounts.isNull("cumulative_lineage_transitions"))
                throw new IllegalStateException("unmigrated cumulative history reported as measured zero");
            migrateOnce(db);
            db.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY,payload TEXT)");
            db.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY,ts INTEGER,payload TEXT,n INTEGER)");
            db.execSQL("INSERT INTO warm_trajectory VALUES (0,1000,?,1)",new Object[]{new JSONObject().put("model_version",second).toString()});
            boolean outside = false;
            try { retainedVersions(db); } catch (IllegalStateException expected) { outside = true; }
            if (!outside) throw new IllegalStateException("retained references read outside a transaction");
            db.beginTransaction();
            try {
                Set<String> references = retainedVersions(db);
                if (references.size() != 1 || !references.contains(second)) throw new IllegalStateException("retained version snapshot mismatch");
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            if (pruneVersions(db,model.modelVersion,null) != 0) throw new IllegalStateException("warm referenced model reclaimed");
            db.execSQL("DELETE FROM warm_trajectory");
            if (pruneVersions(db,model.modelVersion,second) != 0) throw new IllegalStateException("rollback model reclaimed");
            db.execSQL("INSERT INTO frames VALUES (1000,?)",new Object[]{new JSONObject().put("model_version",second).toString()});
            if (pruneVersions(db,model.modelVersion,null) != 0) throw new IllegalStateException("hot referenced model reclaimed");
            db.execSQL("DELETE FROM frames");
            db.execSQL("INSERT INTO frames VALUES (1000,'{}')");
            boolean invalid = false;
            try { pruneVersions(db,model.modelVersion,null); } catch (IllegalArgumentException expected) { invalid = true; }
            if (!invalid || DatabaseUtils.queryNumEntries(db,"cold_models") != 2 || db.inTransaction())
                throw new IllegalStateException("unbound retained reference permitted pruning");
            db.execSQL("DELETE FROM frames");
            if (pruneVersions(db,model.modelVersion,null) != 1 || pruneVersions(db,model.modelVersion,null) != 0
                    || DatabaseUtils.queryNumEntries(db,"cold_models") != 1 || DatabaseUtils.queryNumEntries(db,"cold_model_lineages") != 1
                    || DatabaseUtils.longForQuery(db,"SELECT SUM(n) FROM cold_lineage_edges",null) != 14)
                throw new IllegalStateException("version reclamation lost cumulative lineage counts");
            JSONObject counts = ColdTrajectory.countSummary(db);
            if (counts.getLong("retained_version_transitions") != 7 || counts.getLong("cumulative_lineage_transitions") != 14
                    || !counts.getBoolean("cumulative_lineage_available")) throw new IllegalStateException("post reclamation counts conflated retained and cumulative history");
            return new JSONObject().put("passed",true).put("post_reclamation_count_summary",counts).put("hot_warm_active_rollback_protection_verified",true)
                .put("unbound_reference_rejects_pruning",true).put("retired_version_reclaimed_idempotently",true)
                .put("cumulative_lineage_counts_preserved",true).put("retained_references_require_transaction",true)
                .put("scope","synthetic_version_retention_not_registry_files_or_automatic_activation");
        }
    }
    static JSONObject verifyCapacity() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(db);
            String first = String.format("%064d",1), normalizer = String.format("%064d",9);
            String[] tokens = {"A","B"};
            JSONObject base = new JSONObject().put("model_version",first).put("model_sha256",String.format("%064d",7))
                .put("normalizer_sha256",normalizer).put("tokens",new JSONArray(tokens));
            ColdTrajectory.register(db,first,normalizer,tokens,base);
            migrateOnce(db);
            for (int index = 2; index <= 8; index++) {
                String version = String.format("%064d",index);
                JSONObject bundle = new JSONObject(base.toString()).put("model_version",version).put("cold_state_namespace",first)
                    .put("token_migration",new JSONObject().put("A","A").put("B","B"));
                ColdTrajectory.register(db,version,normalizer,tokens,bundle);
            }
            boolean overflow = false;
            String ninth = String.format("%064d",10);
            try { ColdTrajectory.register(db,ninth,normalizer,tokens,new JSONObject(base.toString()).put("model_version",ninth)); }
            catch (IllegalArgumentException expected) { overflow = true; }
            if (!overflow || DatabaseUtils.queryNumEntries(db,"cold_models") != 8 || db.inTransaction())
                throw new IllegalStateException("migrated version capacity not bounded at eight");
            if (NativeColdAudit.run(db).getJSONObject("lineage_graph").getJSONArray("models").length() != 8)
                throw new IllegalStateException("eight retained versions failed graph audit");
            db.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY,payload TEXT)");
            db.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY,ts INTEGER,payload TEXT,n INTEGER)");
            for (int index = 2; index <= 7; index++) db.execSQL("INSERT INTO warm_trajectory VALUES (?,?,?,1)",new Object[]{index,index,
                new JSONObject().put("model_version",String.format("%064d",index)).toString()});
            String rollback = String.format("%064d",8);
            if (pruneVersions(db,first,rollback) != 0) throw new IllegalStateException("retained week versions reclaimed");
            db.execSQL("DELETE FROM warm_trajectory");
            if (pruneVersions(db,first,rollback) != 6 || DatabaseUtils.queryNumEntries(db,"cold_models") != 2)
                throw new IllegalStateException("expired version capacity not reclaimed");
            return new JSONObject().put("passed",true).put("eight_versions_bounded_and_audited",true)
                .put("six_warm_versions_plus_active_and_rollback_protected",true).put("expired_versions_reclaim_capacity",true)
                .put("scope","synthetic_small_metadata_fixture_not_eight_full_model_disk_bound_or_real_weekly_cadence");
        }
    }
    static JSONObject verifyAdmission(ModelEngine model) throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(db);
            ColdTrajectory.register(db,model.modelVersion,model.normalizerDigest,model.tokens,model.coldBundle());
            migrateOnce(db);
            String version = String.format("%064d",3), rejectedVersion = String.format("%064d",4);
            JSONObject bundle = new JSONObject(model.coldBundle().toString()).put("model_version",version).put("cold_state_namespace",model.modelVersion);
            JSONObject mapping = new JSONObject(); for (String token : model.tokens) mapping.put(token,token);
            bundle.put("token_migration",mapping);
            ColdTrajectory.register(db,version,model.normalizerDigest,model.tokens,bundle);
            ColdTrajectory.register(db,version,model.normalizerDigest,model.tokens,bundle);
            JSONObject conflict = new JSONObject(bundle.toString()).put("model_version",rejectedVersion).put("normalizer_sha256",String.format("%064d",9));
            boolean rejected = false;
            try { ColdTrajectory.register(db,rejectedVersion,String.format("%064d",9),model.tokens,conflict); }
            catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected || db.inTransaction() || DatabaseUtils.queryNumEntries(db,"cold_models") != 2
                    || DatabaseUtils.queryNumEntries(db,"cold_model_lineages") != 2 || DatabaseUtils.queryNumEntries(db,"cold_lineages") != 1)
                throw new IllegalStateException("lineage admission conflict left partial registration");
            return new JSONObject().put("passed",true).put("new_version_admitted_idempotently",true).put("normalizer_conflict_registration_rolled_back",true)
                .put("scope","isolated_synthetic_lineage_admission_not_full_migration_or_production_activation");
        }
    }
    static JSONObject verify(ModelEngine model, java.io.File directory) throws Exception {
        verifyFailedMigration(model, "vocabulary");
        verifyFailedMigration(model, "overflow");
        verifyFailedMigration(model, "cancelled");
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(db);
            String first = model.modelVersion, second = String.format("%064d", 2);
            JSONObject next = new JSONObject(model.coldBundle().toString()).put("model_version", second).put("cold_state_namespace", first);
            JSONObject mapping = new JSONObject(); for (String token : model.tokens) mapping.put(token, token);
            next.put("token_migration", mapping);
            ColdTrajectory.register(db, first, model.normalizerDigest, model.tokens, model.coldBundle());
            ColdTrajectory.register(db, second, model.normalizerDigest, model.tokens, next);
            for (String version : new String[]{first, second})
                db.execSQL("INSERT INTO cold_edges VALUES (?,?,?,7)", new Object[]{version,model.tokens[0],model.tokens[0]});
            next.put("normalizer_sha256", String.format("%064d",9));
            db.execSQL("UPDATE cold_models SET normalizer=?,bundle=? WHERE namespace=?",new Object[]{String.format("%064d",9),next.toString(),second});
            boolean rejected = false;
            try { migrateOnce(db); } catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected || db.inTransaction() || ColdTrajectory.transitions(db) != 14)
                throw new IllegalStateException("lineage conflict accepted or legacy counts changed");
            next.put("normalizer_sha256", model.normalizerDigest);
            db.execSQL("UPDATE cold_models SET normalizer=?,bundle=? WHERE namespace=?",new Object[]{model.normalizerDigest,next.toString(),second});
            migrateOnce(db); migrateOnce(db);
            if (DatabaseUtils.queryNumEntries(db,"cold_lineages") != 1 || DatabaseUtils.queryNumEntries(db,"cold_model_lineages") != 2
                    || DatabaseUtils.longForQuery(db,"SELECT SUM(n) FROM cold_lineage_edges",null) != 14 || ColdTrajectory.transitions(db) != 14)
                throw new IllegalStateException("lineage merge or migration idempotence mismatch");
            String third = String.format("%064d",3), fourth = String.format("%064d",4);
            JSONObject admitted = new JSONObject(next.toString()).put("model_version",third);
            ColdTrajectory.register(db,third,model.normalizerDigest,model.tokens,admitted);
            ColdTrajectory.register(db,third,model.normalizerDigest,model.tokens,admitted);
            JSONObject conflict = new JSONObject(admitted.toString()).put("model_version",fourth).put("normalizer_sha256",String.format("%064d",9));
            boolean admissionRejected = false;
            try { ColdTrajectory.register(db,fourth,String.format("%064d",9),model.tokens,conflict); }
            catch (IllegalArgumentException expected) { admissionRejected = true; }
            if (!admissionRejected || DatabaseUtils.queryNumEntries(db,"cold_models") != 3
                    || DatabaseUtils.queryNumEntries(db,"cold_model_lineages") != 3 || db.inTransaction())
                throw new IllegalStateException("new version lineage admission or conflict rollback failed");
            JSONObject previous = new JSONObject().put("model_version",first).put("model_sha256",model.modelDigest)
                .put("normalizer_sha256",model.normalizerDigest).put("session_id","synthetic_lineage_writer")
                .put("source_mode","synthetic_fixture").put("state_token",model.tokens[0]).put("timestamp_epoch_ms",1000L);
            JSONObject frame = new JSONObject(previous.toString()).put("timestamp_epoch_ms",2000L).put("continuous_from_previous",true);
            ColdTrajectory.append(db,previous,frame);
            if (ColdTrajectory.transitions(db) != 15 || DatabaseUtils.longForQuery(db,"SELECT SUM(n) FROM cold_lineage_edges",null) != 15)
                throw new IllegalStateException("version and lineage counters diverged");
            JSONObject audit = NativeColdAudit.run(db).getJSONObject("lineage_graph");
            if (audit.getLong("transitions") != 15 || !audit.getString("source_sha256").equals(NativeColdAudit.run(db).getJSONObject("lineage_graph").getString("source_sha256")))
                throw new IllegalStateException("lineage audit count or digest instability");
            db.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY,payload TEXT)");
            java.io.File exported = java.io.File.createTempFile("lineage-export-",".json.gz",directory);
            try {
                ColdTrajectory.export(db,exported);
                java.io.ByteArrayOutputStream decoded = new java.io.ByteArrayOutputStream();
                try (java.util.zip.GZIPInputStream input = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(exported))) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) decoded.write(buffer,0,count);
                }
                JSONObject checkpoint = new JSONObject(new String(decoded.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
                JSONObject archived = checkpoint.getJSONObject("lineage_graph");
                if (archived.getLong("transitions") != 15 || !archived.getString("source_sha256").equals(audit.getString("source_sha256")))
                    throw new IllegalStateException("lineage gzip export differs from audited graph");
            } finally { exported.delete(); }
            db.execSQL("UPDATE cold_lineage_edges SET target='foreign'");
            boolean badEdge = false;
            try { NativeColdAudit.run(db); } catch (IllegalArgumentException expected) { badEdge = true; }
            if (!badEdge || db.inTransaction()) throw new IllegalStateException("invalid lineage edge accepted or transaction retained");
            db.execSQL("UPDATE cold_lineage_edges SET target=?",new Object[]{model.tokens[0]});
            db.execSQL("UPDATE cold_lineage_edges SET n=?",new Object[]{Long.MAX_VALUE});
            boolean overflow = false;
            try { ColdTrajectory.append(db,previous,frame); } catch (ArithmeticException expected) { overflow = true; }
            if (!overflow || db.inTransaction() || ColdTrajectory.transitions(db) != 15
                    || DatabaseUtils.longForQuery(db,"SELECT n FROM cold_lineage_edges",null) != Long.MAX_VALUE)
                throw new IllegalStateException("lineage overflow committed partial version counter");
            return new JSONObject().put("passed",true).put("lineage_conflict_rejected",true).put("legacy_counts_preserved",true)
                .put("failure_tables_and_marker_rolled_back",true).put("vocabulary_overflow_cancellation_rejected",true)
                .put("new_version_lineage_admission_and_conflict_rollback_verified",true).put("lineage_gzip_export_matches_audit",true).put("lineage_audit_digest_and_invalid_edge_rejection_verified",true).put("dual_count_update_and_overflow_rollback_verified",true).put("shared_lineage_counts_merged_once",true).put("scope","synthetic_transactional_migration_not_live_writer_or_export_integration");
        }
    }
    private static void verifyFailedMigration(ModelEngine model, String failure) throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(db);
            String second = String.format("%064d",2);
            String[] tokens = failure.equals("vocabulary") ? java.util.Arrays.copyOfRange(model.tokens,1,model.tokens.length) : model.tokens;
            JSONObject next = new JSONObject(model.coldBundle().toString()).put("model_version",second)
                .put("cold_state_namespace",model.modelVersion).put("tokens",new JSONArray(tokens));
            JSONObject mapping = new JSONObject(); for (String token : tokens) mapping.put(token,token);
            next.put("token_migration",mapping);
            ColdTrajectory.register(db,model.modelVersion,model.normalizerDigest,model.tokens,model.coldBundle());
            ColdTrajectory.register(db,second,model.normalizerDigest,tokens,next);
            long firstCount = failure.equals("overflow") ? Long.MAX_VALUE : 7;
            db.execSQL("INSERT INTO cold_edges VALUES (?,?,?,?)",new Object[]{model.modelVersion,model.tokens[0],model.tokens[0],firstCount});
            db.execSQL("INSERT INTO cold_edges VALUES (?,?,?,1)",new Object[]{second,tokens[0],tokens[0]});
            boolean rejected = false;
            try {
                if (failure.equals("cancelled")) Thread.currentThread().interrupt();
                migrateOnce(db);
            } catch (IllegalArgumentException expected) { rejected = failure.equals("vocabulary"); }
            catch (ArithmeticException expected) { rejected = failure.equals("overflow"); }
            catch (InterruptedException expected) { rejected = failure.equals("cancelled"); }
            finally { Thread.interrupted(); }
            if (!rejected || db.inTransaction()
                    || DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name LIKE 'cold_lineage%'",null) != 0
                    || DatabaseUtils.longForQuery(db,"SELECT n FROM cold_edges WHERE namespace=?",new String[]{model.modelVersion}) != firstCount)
                throw new IllegalStateException("failed migration did not roll back: " + failure);
        }
    }

}
