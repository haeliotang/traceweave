package org.traceform.edge;

import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Immutable model snapshots and compare-current atomic activation/rollback. */
final class NativeModelRegistry {
    private final File directory;
    private final ModelEngine fallback;
    NativeModelRegistry(File directory, ModelEngine fallback) throws Exception {
        this.directory = directory; this.fallback = fallback;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("model registry directory unavailable");
    }
    ModelEngine active() throws Exception {
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquireModelRegistry(directory)) {
            JSONObject pointer = pointer();
            if (pointer == null) return fallback;
            ModelEngine active = model(pointer.getString("current"));
            if (!active.modelVersion.equals(pointer.getString("current_version"))) throw new IllegalArgumentException("active pointer version mismatch");
            return active;
        }
    }
    ModelEngine activeIfChanged(String expectedVersion, android.database.sqlite.SQLiteDatabase history) throws Exception {
        if (history == null || history.inTransaction()) throw new IllegalArgumentException("independent history transaction required");
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquireModelRegistry(directory)) {
            JSONObject pointer = pointer();
            String version = pointer == null ? fallback.modelVersion : pointer.getString("current_version");
            if (version.equals(expectedVersion)) return null;
            ModelEngine loaded = pointer == null ? fallback : model(pointer.getString("current"));
            if (!version.equals(loaded.modelVersion)) throw new IllegalArgumentException("active pointer version mismatch");
            history.beginTransaction();
            try (android.database.Cursor row = history.rawQuery("SELECT normalizer,bundle FROM cold_models WHERE namespace=?",new String[]{version})) {
                if (!row.moveToFirst() || row.getType(0) != android.database.Cursor.FIELD_TYPE_STRING
                        || row.getType(1) != android.database.Cursor.FIELD_TYPE_STRING
                        || !loaded.normalizerDigest.equals(row.getString(0))
                        || !ModelJson.equal(loaded.coldBundle(),new JSONObject(row.getString(1))))
                    throw new IllegalArgumentException("active model is not admitted to history");
                history.setTransactionSuccessful();
            } finally { history.endTransaction(); }
            return loaded;
        }
    }
    String activate(File candidates, String candidateDigest, String expectedVersion) throws Exception {
        return activate(candidates, candidateDigest, expectedVersion, java.util.Collections.emptySet(), 4, null, null);
    }
    String activate(File candidates, String candidateDigest, String expectedVersion, Set<String> retainedVersions) throws Exception {
        return activate(candidates, candidateDigest, expectedVersion, new HashSet<>(retainedVersions), 8, null, null);
    }
    String activateWithHistory(File candidates, String candidateDigest, String expectedVersion, android.database.sqlite.SQLiteDatabase history) throws Exception {
        if (history == null || history.inTransaction()) throw new IllegalArgumentException("independent history transaction required");
        return activate(candidates, candidateDigest, expectedVersion, java.util.Collections.emptySet(), 8, history, null);
    }
    String activateWeeklyWithHistory(File candidates, String candidateDigest, String expectedVersion,
            android.database.sqlite.SQLiteDatabase history, String window) throws Exception {
        if (history == null || history.inTransaction()) throw new IllegalArgumentException("independent history transaction required");
        java.time.LocalDate.parse(window);
        return activate(candidates,candidateDigest,expectedVersion,java.util.Collections.emptySet(),8,history,window);
    }
    JSONObject completedWeeklyActivation(String window) throws Exception {
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquireModelRegistry(directory)) {
            return weeklyActivation(pointer(),window);
        }
    }
    private static JSONObject weeklyActivation(JSONObject pointer, String window) throws Exception {
        if (pointer == null || !window.equals(pointer.optString("weekly_window",null))) return null;
        return new JSONObject().put("candidate_activated",true).put("accepted",true).put("activation_recovered",true)
            .put("activated_model_version",pointer.getString("weekly_activated_version"))
            .put("candidate_bundle_sha256",pointer.getString("weekly_candidate_sha256"))
            .put("scope","published_registry_window_receipt_recovery_not_new_calibration");
    }
    private static JSONObject carryWeeklyActivation(JSONObject source, JSONObject target) throws Exception {
        if (source != null && source.has("weekly_window"))
            for (String field : new String[]{"weekly_window","weekly_candidate_sha256","weekly_activated_version"}) target.put(field,source.get(field));
        return target;
    }
    private String activate(File candidates, String candidateDigest, String expectedVersion, Set<String> retainedVersions, int maximum,
            android.database.sqlite.SQLiteDatabase history, String window) throws Exception {
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquireModelRegistry(directory)) {
            boolean historyTransaction = false;
            try {
            removeAbandonedWrites();
            JSONObject pointer = pointer();
            if (window != null) {
                JSONObject completed = weeklyActivation(pointer,window);
                if (completed != null) return completed.getString("activated_model_version");
            }
            JSONObject candidate = NativeCandidateStore.read(candidates,candidateDigest);
            if (history != null) {
                history.beginTransaction(); historyTransaction = true;
                if (ColdLineage.snapshot(history) == null) throw new IllegalStateException("completed lineage migration required for activation");
                retainedVersions = ColdLineage.retainedVersions(history);
            }
            ModelEngine current = pointer == null ? fallback : model(pointer.getString("current"));
            if (!current.modelVersion.equals(expectedVersion) || pointer != null && !current.modelVersion.equals(pointer.getString("current_version")))
                throw new IllegalStateException("active model changed during candidate fitting");
            JSONObject core = candidate.getJSONObject("model"), validation = new JSONObject(candidate.getJSONObject("validation").toString());
            if (!Boolean.FALSE.equals(candidate.opt("candidate_activated"))
                    || !current.modelVersion.equals(core.getString("parent_model_version"))
                    || !Boolean.TRUE.equals(core.getJSONObject("potential").opt("available")))
                throw new IllegalArgumentException("parent-bound available candidate potential required");
            validation.put("candidate_centers", core.getJSONArray("centers")).put("candidate_velocity", core.getJSONArray("center_velocity"))
                .put("candidate_grid", core.getJSONArray("grid_coordinates")).put("potential", core.getJSONObject("potential"));
            JSONObject checked = current.candidateBundle(validation);
            if (!core.has("cold_state_namespace") && !core.has("token_migration")) {
                checked.getJSONObject("model").remove("cold_state_namespace");
                checked.getJSONObject("model").remove("token_migration");
            }

            if (!ModelJson.equal(checked.getJSONObject("model"), core))
                throw new IllegalArgumentException("candidate changed fixed normalization or model parameters");
            if (history != null && android.database.DatabaseUtils.queryNumEntries(history,"cold_models") >= ColdLineage.modelLimit(history))
                ColdLineage.pruneVersionsInTransaction(history,current.modelVersion,pointer == null ? null : pointer.getString("previous_version"));
            Set<String> protectedFiles = protectedFiles(pointer);
            if (retainedVersions.size() > 8) throw new IllegalArgumentException("bounded retained registry versions required");
            Set<String> missing = new HashSet<>(retainedVersions);
            for (String version : missing)
                if (version == null || !version.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("retained model version required");
            for (File file : snapshots()) {
                String reference = file.getName().substring(0,64);
                String version = model(reference).modelVersion;
                if (retainedVersions.contains(version)) { missing.remove(version); protectedFiles.add(reference); }
            }
            missing.remove(fallback.modelVersion);
            if (!missing.isEmpty()) throw new IllegalArgumentException("retained model snapshot unavailable");
            String previous = pointer == null ? save(current.registrySnapshot(), current.modelVersion, current.modelDigest, "asset_snapshot", protectedFiles, maximum)
                : pointer.getString("current");
            protectedFiles.add(previous);
            String coreJson = core.toString(), digest = sha(coreJson.getBytes(StandardCharsets.UTF_8));
            String version = sha(("traceform.native-model-version.v1\n" + digest + "\n" + current.normalizerDigest).getBytes(StandardCharsets.US_ASCII));
            String registered = save(core, version, digest, "calibrated_native", protectedFiles, maximum);
            ModelEngine admitted = model(registered);
            if (history != null) {
                ColdTrajectory.register(history,current.modelVersion,current.normalizerDigest,current.tokens,current.coldBundle());
                ColdTrajectory.register(history,admitted.modelVersion,admitted.normalizerDigest,admitted.tokens,admitted.coldBundle());
                ColdLineage.snapshot(history);
                NativeColdAudit.run(history);
                File checkpoint = File.createTempFile("cold-admission-", ".json.gz", directory);
                try { ColdTrajectory.export(history,checkpoint); }
                finally {
                    java.nio.file.Files.deleteIfExists(checkpoint.toPath());
                    java.nio.file.Files.deleteIfExists(new File(checkpoint.getPath() + ".tmp").toPath());
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("model admission cancelled");
                history.setTransactionSuccessful();
                historyTransaction = false; history.endTransaction();
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("model publication cancelled");
            JSONObject updated = carryWeeklyActivation(pointer,new JSONObject().put("current",registered).put("previous",previous)
                .put("current_version",version).put("previous_version",current.modelVersion));
            if (window != null) updated.put("weekly_window",window).put("weekly_candidate_sha256",candidateDigest)
                .put("weekly_activated_version",version);
            write(new File(directory,"current.json"),updated);
            return version;
            } finally { if (historyTransaction) history.endTransaction(); }
        }
    }
    String rollback(String expectedVersion) throws Exception {
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquireModelRegistry(directory)) {
            JSONObject pointer = pointer();
            if (pointer == null || !pointer.getString("current_version").equals(expectedVersion))
                throw new IllegalStateException("active model changed or rollback is unavailable");
            String previous = pointer.getString("previous");
            ModelEngine restored = model(previous);
            if (!restored.modelVersion.equals(pointer.getString("previous_version"))) throw new IllegalArgumentException("rollback pointer version mismatch");
            write(new File(directory, "current.json"), carryWeeklyActivation(pointer,new JSONObject().put("current", previous).put("previous", pointer.getString("current"))
                .put("current_version", restored.modelVersion).put("previous_version", pointer.getString("current_version"))));
            return restored.modelVersion;
        }
    }
    private JSONObject pointer() throws Exception {
        File path = new File(directory, "current.json");
        if (!path.exists() && !new File(directory, "current.json.bak").exists()) return null;
        JSONObject pointer = new JSONObject(new String(read(path, 1024), StandardCharsets.UTF_8));
        for (String field : new String[]{"current", "previous", "current_version", "previous_version"})
            if (!(pointer.get(field) instanceof String) || !pointer.getString(field).matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("bounded registry references required");
        boolean weekly = pointer.has("weekly_window");
        if (weekly != pointer.has("weekly_candidate_sha256") || weekly != pointer.has("weekly_activated_version"))
            throw new IllegalArgumentException("complete weekly publication binding required");
        if (weekly) {
            java.time.LocalDate.parse(pointer.getString("weekly_window"));
            for (String field : new String[]{"weekly_candidate_sha256","weekly_activated_version"})
                if (!(pointer.get(field) instanceof String) || !pointer.getString(field).matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("bounded weekly publication identity required");
        }
        return pointer;
    }
    private ModelEngine model(String reference) throws Exception {
        if (!reference.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("model snapshot digest required");
        byte[] encoded = read(new File(directory, reference + ".json"), 256000);
        if (!sha(encoded).equals(reference)) throw new IllegalArgumentException("model snapshot digest mismatch");
        JSONObject snapshot = new JSONObject(new String(encoded, StandardCharsets.UTF_8));
        if (!"traceform.native-model-registry.v1".equals(snapshot.getString("protocol"))) throw new IllegalArgumentException("registry protocol mismatch");
        String coreJson = snapshot.getString("core_json"), digest = snapshot.getString("model_sha256"), version = snapshot.getString("model_version");
        JSONObject core = new JSONObject(coreJson);
        if (snapshot.getString("basis").equals("calibrated_native")) {
            if (!sha(coreJson.getBytes(StandardCharsets.UTF_8)).equals(digest)
                    || !sha(("traceform.native-model-version.v1\n" + digest + "\n" + core.getString("normalizer_sha256")).getBytes(StandardCharsets.US_ASCII)).equals(version))
                throw new IllegalArgumentException("registered native core identity mismatch");
        } else if (!snapshot.getString("basis").equals("asset_snapshot")
                || !digest.equals(core.getString("model_sha256")) || !version.equals(core.getString("model_version")))
            throw new IllegalArgumentException("asset snapshot identity mismatch");
        return ModelEngine.fromRegistry(core, version, digest);
    }
    private String save(JSONObject core, String version, String digest, String basis, Set<String> protectedFiles, int maximum) throws Exception {
        JSONObject snapshot = new JSONObject().put("protocol", "traceform.native-model-registry.v1").put("core_json", core.toString())
            .put("model_version", version).put("model_sha256", digest).put("basis", basis);
        byte[] encoded = snapshot.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 256000) throw new IllegalArgumentException("bounded model snapshot required");
        String reference = sha(encoded); File destination = new File(directory, reference + ".json");
        if (destination.exists() || new File(directory, reference + ".json.bak").exists()) { model(reference); return reference; }
        File[] snapshots = snapshots();
        java.util.Arrays.sort(snapshots, java.util.Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        int remaining = snapshots.length;
        for (File file : snapshots) {
            if (remaining < maximum) break;
            if (protectedFiles.contains(file.getName().substring(0, 64))) continue;
            if (!file.delete()) throw new java.io.IOException("registry retention deletion failed");
            remaining--;
        }
        if (remaining >= maximum) throw new IllegalStateException("registry retention cannot reclaim an unreferenced snapshot");
        write(destination, snapshot); return reference;
    }
    private void removeAbandonedWrites() throws java.io.IOException {
        File[] temporary = directory.listFiles((parent,name) -> name.matches("[0-9a-f]{64}\\.json\\.new")
            || name.matches("cold-admission-[0-9]+\\.json\\.gz(?:\\.tmp)?"));
        if (temporary == null) throw new java.io.IOException("registry listing unavailable");
        for (File file : temporary) java.nio.file.Files.deleteIfExists(file.toPath());
    }
    private File[] snapshots() throws Exception {
        File[] entries = directory.listFiles((parent, name) -> name.matches("[0-9a-f]{64}\\.json(?:\\.bak)?"));
        if (entries == null) throw new java.io.IOException("registry listing unavailable");
        Set<String> identities = new java.util.TreeSet<>();
        for (File entry : entries) identities.add(entry.getName().substring(0, 64));
        File[] snapshots = new File[identities.size()]; int index = 0;
        for (String identity : identities) {
            model(identity);
            snapshots[index++] = new File(directory, identity + ".json");
        }
        return snapshots;
    }
    private static Set<String> protectedFiles(JSONObject pointer) throws Exception {
        Set<String> result = new HashSet<>();
        if (pointer != null) { result.add(pointer.getString("current")); result.add(pointer.getString("previous")); }
        return result;
    }
    private static byte[] read(File path, int maximum) throws Exception {
        try (FileInputStream input = new AtomicFile(path).openRead()) {
            long length = input.getChannel().size();
            if (length < 1 || length > maximum) throw new IllegalArgumentException("bounded registry file required");
            byte[] result = new byte[(int) length]; int offset = 0;
            while (offset < result.length) { int count = input.read(result, offset, result.length - offset); if (count < 0) throw new java.io.IOException("registry file truncated"); offset += count; }
            if (input.read() != -1) throw new java.io.IOException("registry file changed during read");
            return result;
        }
    }
    private static void write(File path, JSONObject value) throws Exception {
        AtomicFile file = new AtomicFile(path); FileOutputStream output = file.startWrite();
        try { output.write(value.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(output); }
        catch (Exception error) { file.failWrite(output); throw error; }
    }
    private static String sha(byte[] value) throws Exception { return HexDigest.encode(MessageDigest.getInstance("SHA-256").digest(value)); }
}
