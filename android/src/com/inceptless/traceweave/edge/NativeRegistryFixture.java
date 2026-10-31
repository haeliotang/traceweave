package com.inceptless.traceweave.edge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Storage-only synthetic candidate, whose metrics are not measured calibration results. */
final class NativeRegistryFixture {
    static JSONObject verify(ModelEngine model, File cache) throws Exception {
        File root = new File(cache, "registry-fixture-" + java.util.UUID.randomUUID());
        File candidates = new File(root, "candidates"), directory = new File(root, "registry");
        try {
            JSONObject report = report(model, String.format("%064d", 1));
            String digest = NativeCandidateStore.save(candidates, model, report);
            NativeModelRegistry registry = new NativeModelRegistry(directory, model);
            boolean stale = false;
            try { registry.activate(candidates, digest, String.format("%064d", 9)); }
            catch (IllegalStateException expected) { stale = true; }
            if (!stale || new File(directory, "current.json").exists()) throw new IllegalStateException("stale activation changed pointer");
            String activated = registry.activate(candidates, digest, model.modelVersion);
            ModelEngine loaded = new NativeModelRegistry(directory, model).active();
            if (!loaded.modelVersion.equals(activated) || activated.equals(model.modelVersion)
                    || !loaded.normalizerDigest.equals(model.normalizerDigest)
                    || !loaded.coldStateNamespace().equals(model.coldStateNamespace())) throw new IllegalStateException("activation identity mismatch");
            JSONObject invalidLineage = loaded.registrySnapshot();
            invalidLineage.getJSONObject("token_migration").put(loaded.tokens[0], "foreign");
            boolean lineageRejected = false;
            try { ModelEngine.fromRegistry(invalidLineage, loaded.modelVersion, loaded.modelDigest); }
            catch (IllegalArgumentException expected) { lineageRejected = true; }
            if (!lineageRejected) throw new IllegalStateException("foreign token migration accepted");
            stale = false;
            try { registry.activate(candidates, digest, model.modelVersion); }
            catch (IllegalStateException expected) { stale = true; }
            if (!stale) throw new IllegalStateException("stale replay accepted");
            JSONObject pointer = new JSONObject(new String(Files.readAllBytes(new File(directory, "current.json").toPath()), StandardCharsets.UTF_8));
            Files.write(new File(directory, pointer.getString("current") + ".json").toPath(), new byte[]{123});
            boolean corrupt = false;
            try { registry.active(); } catch (IllegalArgumentException expected) { corrupt = true; }
            if (!corrupt || !registry.rollback(activated).equals(model.modelVersion)) throw new IllegalStateException("corrupt snapshot rollback failed");
            ModelEngine restored = new NativeModelRegistry(directory, model).active();
            if (!Arrays.equals(restored.medians, model.medians) || !Arrays.equals(restored.iqrs, model.iqrs)
                    || !Arrays.equals(restored.centers, model.centers) || !Arrays.equals(restored.centerVelocity, model.centerVelocity))
                throw new IllegalStateException("rollback changed baseline array bits");
            return new JSONObject().put("passed", true).put("stale_activation_and_replay_rejected", true)
                .put("cold_lineage_inherited_and_foreign_mapping_rejected", true).put("reopened_activation_identity_verified", true).put("corrupt_snapshot_rejected", true)
                .put("rollback_baseline_array_bits_preserved", true)
                .put("scope", "private_synthetic_registry_storage_fixture_not_measured_calibration_or_production_activation");
        } finally {
            for (File child : new File[]{candidates, directory}) {
                File[] files = child.listFiles(); if (files != null) for (File file : files) file.delete(); child.delete();
            }
            root.delete();
        }
    }
    private static JSONObject report(ModelEngine model, String source) throws Exception {
        JSONArray centers = new JSONArray(), drift = new JSONArray(), energy = new JSONArray(), order = new JSONArray();
        for (int node = 0; node < model.tokens.length; node++) {
            JSONArray center = new JSONArray(), velocity = new JSONArray();
            for (int index = 0; index < model.names.length; index++) {
                center.put(model.centers[node * model.names.length + index]); velocity.put(0.0);
            }
            centers.put(center); drift.put(velocity); energy.put((double) node); order.put(model.tokens[node]);
        }
        // The energy values increase in token order; asset vocabulary must match this fixture assumption.
        for (int node = 1; node < model.tokens.length; node++)
            if (model.tokens[node - 1].compareTo(model.tokens[node]) >= 0)
                throw new IllegalStateException("ordered asset tokens required by registry fixture");
        JSONObject potential = new JSONObject().put("available", true).put("protocol", "traceform.graph-potential.v1")
            .put("parent_model_sha256", model.modelDigest).put("normalizer_sha256", model.normalizerDigest)
            .put("source_sha256", source).put("training_end_epoch_ms", 69000L).put("fit_pair_count", 69)
            .put("bandwidth", 1.0).put("mobility", 1.0).put("gauge_anchor", 0).put("centers", centers)
            .put("empirical_drift", drift).put("relative_energy", energy).put("energy_ordered_tokens", order)
            .put("edge_residual_rms", 0.0).put("conservative_fraction", JSONObject.NULL);
        JSONObject report = new JSONObject().put("training_records", 70).put("holdout_records", 30)
            .put("training_end_epoch_ms", 69000L).put("holdout_start_epoch_ms", 70000L)
            .put("baseline_states", 2).put("candidate_states", 2).put("baseline_nonadjacent_count", 0L)
            .put("candidate_nonadjacent_count", 0L).put("baseline_mean_distance", 2.0).put("candidate_mean_distance", 1.0)
            .put("accepted", true).put("candidate_activated", false).put("parent_model_sha256", model.modelDigest)
            .put("normalizer_sha256", model.normalizerDigest).put("source_sha256", source)
            .put("audit", new JSONObject().put("source_sha256", source).put("invalid_records", 0L))
            .put("candidate_centers", new JSONArray(model.centers)).put("candidate_velocity", new JSONArray(model.centerVelocity))
            .put("candidate_grid", model.registrySnapshot().getJSONArray("grid_coordinates")).put("potential", potential)
            .put("fixture_scope", "synthetic_metrics_not_measured_fit_or_scientific_acceptance");
        return report;
    }
    static JSONObject verifyTemporaryRetention(ModelEngine model, File cache) throws Exception {
        File root = new File(cache,"registry-temporary-fixture-" + java.util.UUID.randomUUID());
        File candidates = new File(root,"candidates"), directory = new File(root,"registry");
        try {
            NativeModelRegistry registry = new NativeModelRegistry(directory,model);
            File[] abandoned = {new File(directory,String.format("%064d",555) + ".json.new"),
                new File(directory,"cold-admission-123456789.json.gz"),
                new File(directory,"cold-admission-123456789.json.gz.tmp")};
            for (File file : abandoned) Files.write(file.toPath(),new byte[]{1,2,3});
            File unrelated = new File(directory,"unrelated.new");
            Files.write(unrelated.toPath(),new byte[]{4,5,6});
            String digest = NativeCandidateStore.save(candidates,model,report(model,String.format("%064d",1)));
            String version = registry.activate(candidates,digest,model.modelVersion);
            for (File file : abandoned) if (file.exists()) throw new IllegalStateException("abandoned registry write escaped cleanup: " + file.getName());
            if (!Arrays.equals(Files.readAllBytes(unrelated.toPath()),new byte[]{4,5,6})
                    || !registry.active().modelVersion.equals(version) || !registry.rollback(version).equals(model.modelVersion))
                throw new IllegalStateException("temporary cleanup changed unrelated file or active/rollback identity");
            return new JSONObject().put("passed",true).put("abandoned_owned_writes_reclaimed",true)
                .put("unrelated_file_preserved",true).put("activation_and_rollback_preserved",true)
                .put("scope","synthetic_abandoned_files_not_real_process_kill_at_each_write_boundary");
        } finally {
            for (File child : new File[]{candidates,directory}) {
                File[] files = child.listFiles(); if (files != null) for (File file : files) file.delete(); child.delete();
            }
            root.delete();
        }
    }
    static JSONObject verifyBackupRetention(ModelEngine model, File cache) throws Exception {
        File root = new File(cache, "registry-retention-fixture-" + java.util.UUID.randomUUID());
        File candidates = new File(root, "candidates"), directory = new File(root, "registry");
        try {
            NativeModelRegistry registry = new NativeModelRegistry(directory, model);
            ModelEngine current = model;
            String oldest = null;
            for (int index = 1; index <= 6; index++) {
                String digest = NativeCandidateStore.save(candidates, current, report(current, String.format("%064d", index)));
                registry.activate(candidates, digest, current.modelVersion);
                current = registry.active();
                JSONObject pointer = new JSONObject(new String(Files.readAllBytes(new File(directory, "current.json").toPath()), StandardCharsets.UTF_8));
                if (index == 1) oldest = pointer.getString("previous");
                if (index == 3) {
                    File original = new File(directory, oldest + ".json");
                    if (!original.renameTo(new File(original.getPath() + ".bak"))) throw new IllegalStateException("backup fixture unavailable");
                }
                java.util.Set<String> snapshots = new java.util.HashSet<>();
                for (File file : directory.listFiles())
                    if (file.getName().matches("[0-9a-f]{64}\\.json(?:\\.bak)?")) snapshots.add(file.getName().substring(0,64));
                if (snapshots.size() > 4) throw new IllegalStateException("backup-only registry snapshot escaped capacity: " + snapshots.size());
            }
            return new JSONObject().put("passed",true).put("backup_only_snapshots_counted_and_reclaimed",true)
                .put("scope","synthetic_registry_file_retention_not_database_reference_coordinator");
        } finally {
            for (File child : new File[]{candidates,directory}) {
                File[] files = child.listFiles(); if (files != null) for (File file : files) file.delete(); child.delete();
            }
            root.delete();
        }
    }

    static JSONObject verifyReferencedRetention(ModelEngine model, File cache) throws Exception {
        File root = new File(cache, "registry-references-fixture-" + java.util.UUID.randomUUID());
        File candidates = new File(root, "candidates"), directory = new File(root, "registry");
        try {
            NativeModelRegistry registry = new NativeModelRegistry(directory, model);
            ModelEngine current = model;
            java.util.Set<String> retained = new java.util.HashSet<>();
            for (int index = 1; index <= 7; index++) {
                retained.add(current.modelVersion);
                String digest = NativeCandidateStore.save(candidates,current,report(current,String.format("%064d",index)));
                registry.activate(candidates,digest,current.modelVersion,retained);
                current = registry.active();
            }
            retained.add(current.modelVersion);
            byte[] oldPointer = Files.readAllBytes(new File(directory,"current.json").toPath());
            String digest = NativeCandidateStore.save(candidates,current,report(current,String.format("%064d",8)));
            boolean full = false;
            try { registry.activate(candidates,digest,current.modelVersion,retained); }
            catch (IllegalStateException expected) { full = true; }
            if (!full || !Arrays.equals(oldPointer,Files.readAllBytes(new File(directory,"current.json").toPath()))
                    || !current.modelVersion.equals(registry.active().modelVersion))
                throw new IllegalStateException("referenced registry capacity changed active pointer");
            java.util.Set<String> invalid = new java.util.HashSet<>(retained);
            invalid.remove(model.modelVersion); invalid.add(String.format("%064d",999));
            boolean missing = false;
            try { registry.activate(candidates,digest,current.modelVersion,invalid); }
            catch (IllegalArgumentException expected) { missing = true; }
            if (!missing || !Arrays.equals(oldPointer,Files.readAllBytes(new File(directory,"current.json").toPath())))
                throw new IllegalStateException("unknown retained registry reference permitted activation");
            retained.remove(model.modelVersion);
            registry.activate(candidates,digest,current.modelVersion,retained);
            java.util.Set<String> found = new java.util.HashSet<>();
            int snapshots = 0;
            for (File file : directory.listFiles()) if (file.getName().matches("[0-9a-f]{64}\\.json")) {
                JSONObject snapshot = new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
                found.add(snapshot.getString("model_version")); snapshots++;
            }
            if (snapshots != 8 || !found.containsAll(retained) || found.contains(model.modelVersion))
                throw new IllegalStateException("released reference reclamation changed protected registry versions");
            return new JSONObject().put("passed",true).put("eight_referenced_versions_protected",true)
                .put("capacity_rejection_preserves_active_pointer",true).put("unknown_reference_rejected",true)
                .put("released_reference_reclaimed",true)
                .put("scope","synthetic_explicit_version_set_not_atomic_database_and_filesystem_coordinator");
        } finally {
            for (File child : new File[]{candidates,directory}) {
                File[] files = child.listFiles(); if (files != null) for (File file : files) file.delete(); child.delete();
            }
            root.delete();
        }
    }

    static JSONObject verifyHistoryAdmission(ModelEngine model, File cache) throws Exception {
        File root = new File(cache,"registry-history-fixture-" + java.util.UUID.randomUUID());
        File candidates = new File(root,"candidates"), directory = new File(root,"registry");
        try (android.database.sqlite.SQLiteDatabase history = android.database.sqlite.SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(history);
            ColdTrajectory.register(history,model.modelVersion,model.normalizerDigest,model.tokens,model.coldBundle());
            ColdLineage.migrateOnce(history);
            history.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY,payload TEXT)");
            history.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY,ts INTEGER,payload TEXT,n INTEGER)");
            NativeModelRegistry registry = new NativeModelRegistry(directory,model);
            String digest = NativeCandidateStore.save(candidates,model,report(model,String.format("%064d",1)));
            String first = registry.activateWithHistory(candidates,digest,model.modelVersion,history);
            ModelEngine current = registry.active();
            if (registry.activeIfChanged(first,history) != null || !first.equals(registry.activeIfChanged(model.modelVersion,history).modelVersion))
                throw new IllegalStateException("admitted model reload mismatch");
            history.execSQL("DELETE FROM cold_model_lineages WHERE version=?",new Object[]{first});
            history.execSQL("DELETE FROM cold_states WHERE namespace=?",new Object[]{first});
            history.execSQL("DELETE FROM cold_models WHERE namespace=?",new Object[]{first});
            boolean reloadRejected = false;
            try { registry.activeIfChanged(model.modelVersion,history); } catch (IllegalArgumentException expected) { reloadRejected = true; }
            if (!reloadRejected || history.inTransaction()) throw new IllegalStateException("unadmitted model accepted by reload");
            ColdTrajectory.register(history,current.modelVersion,current.normalizerDigest,current.tokens,current.coldBundle());
            if (!first.equals(current.modelVersion) || android.database.DatabaseUtils.queryNumEntries(history,"cold_models") != 2)
                throw new IllegalStateException("published model missing history admission");
            byte[] oldPointer = Files.readAllBytes(new File(directory,"current.json").toPath());
            digest = NativeCandidateStore.save(candidates,current,report(current,String.format("%064d",2)));
            File blocked = new File(directory,"current.json.new"), blocker = new File(blocked,"blocker");
            if (!blocked.mkdir()) throw new IllegalStateException("publication failure fixture unavailable");
            Files.write(blocker.toPath(),new byte[]{1});
            boolean publicationFailed = false;
            try { registry.activateWithHistory(candidates,digest,current.modelVersion,history); }
            catch (java.io.IOException expected) { publicationFailed = true; }
            if (!publicationFailed || history.inTransaction() || !Arrays.equals(oldPointer,Files.readAllBytes(new File(directory,"current.json").toPath()))
                    || !first.equals(registry.active().modelVersion) || android.database.DatabaseUtils.queryNumEntries(history,"cold_models") != 3)
                throw new IllegalStateException("failed publication did not preserve admitted history and old pointer");
            if (!blocker.delete() || !blocked.delete()) throw new IllegalStateException("publication fixture cleanup failed");
            String second = registry.activateWithHistory(candidates,digest,current.modelVersion,history);
            if (second.equals(first) || !registry.active().modelVersion.equals(second)
                    || android.database.DatabaseUtils.queryNumEntries(history,"cold_models") != 3)
                throw new IllegalStateException("publication retry duplicated history admission");
            current = registry.active(); oldPointer = Files.readAllBytes(new File(directory,"current.json").toPath());
            digest = NativeCandidateStore.save(candidates,current,report(current,String.format("%064d",3)));
            history.execSQL("CREATE TRIGGER reject_admission BEFORE INSERT ON cold_models BEGIN SELECT RAISE(ABORT,'synthetic admission rejected'); END");
            boolean admissionFailed = false;
            try { registry.activateWithHistory(candidates,digest,current.modelVersion,history); }
            catch (android.database.sqlite.SQLiteConstraintException expected) { admissionFailed = true; }
            history.execSQL("DROP TRIGGER reject_admission");
            if (!admissionFailed || history.inTransaction() || !Arrays.equals(oldPointer,Files.readAllBytes(new File(directory,"current.json").toPath()))
                    || android.database.DatabaseUtils.queryNumEntries(history,"cold_models") != 3)
                throw new IllegalStateException("failed admission published registry pointer");
            history.execSQL("INSERT INTO frames VALUES (1000,'{}')");
            boolean unbound = false;
            try { registry.activateWithHistory(candidates,digest,current.modelVersion,history); }
            catch (IllegalArgumentException expected) { unbound = true; }
            if (!unbound || history.inTransaction() || !Arrays.equals(oldPointer,Files.readAllBytes(new File(directory,"current.json").toPath())))
                throw new IllegalStateException("unbound history allowed coordinated activation");
            history.execSQL("DELETE FROM frames");
            String window = "2026-10-04";
            String weekly = registry.activateWeeklyWithHistory(candidates,digest,current.modelVersion,history,window);
            NativeModelRegistry reopened = new NativeModelRegistry(directory,model);
            JSONObject recovered = reopened.completedWeeklyActivation(window);
            if (recovered == null || !recovered.getString("activated_model_version").equals(weekly)
                    || !recovered.getString("candidate_bundle_sha256").equals(digest))
                throw new IllegalStateException("published weekly window not recovered after reopen");
            byte[] publishedPointer = Files.readAllBytes(new File(directory,"current.json").toPath());
            if (!weekly.equals(reopened.activateWeeklyWithHistory(candidates,String.format("%064d",999),current.modelVersion,history,window))
                    || !Arrays.equals(publishedPointer,Files.readAllBytes(new File(directory,"current.json").toPath()))
                    || android.database.DatabaseUtils.queryNumEntries(history,"cold_models") != 4)
                throw new IllegalStateException("same weekly window repeated activation");
            reopened.rollback(weekly);
            byte[] rollbackPointer = Files.readAllBytes(new File(directory,"current.json").toPath());
            if (reopened.completedWeeklyActivation(window) == null
                    || !weekly.equals(reopened.activateWeeklyWithHistory(candidates,digest,current.modelVersion,history,window))
                    || !Arrays.equals(rollbackPointer,Files.readAllBytes(new File(directory,"current.json").toPath())))
                throw new IllegalStateException("rollback erased weekly idempotence");
            return new JSONObject().put("passed",true).put("database_admission_precedes_pointer_publication",true).put("reload_requires_admitted_model",true).put("weekly_publication_reopen_and_replay_idempotent",true).put("weekly_marker_survives_rollback",true)
                .put("publication_failure_preserves_old_pointer",true).put("publication_retry_idempotent",true)
                .put("database_admission_failure_does_not_publish",true).put("unbound_reference_rejected",true)
                .put("scope","synthetic_android_database_file_failure_boundaries_not_process_kill_or_live_service_reload");
        } finally {
            for (File child : new File[]{candidates,directory}) {
                File[] files = child.listFiles(); if (files != null) for (File file : files) {
                    if (file.isDirectory()) { File[] nested = file.listFiles(); if (nested != null) for (File entry : nested) entry.delete(); }
                    file.delete();
                }
                child.delete();
            }
            root.delete();
        }
    }

    static JSONObject verifyServiceReload(ModelEngine model, File cache) throws Exception {
        File root = new File(cache,"service-reload-fixture-" + java.util.UUID.randomUUID());
        File candidates = new File(root,"candidates"), directory = new File(root,"model_registry");
        try (android.database.sqlite.SQLiteDatabase history = android.database.sqlite.SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(history);
            ColdTrajectory.register(history,model.modelVersion,model.normalizerDigest,model.tokens,model.coldBundle());
            ColdLineage.migrateOnce(history);
            history.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY,payload TEXT)");
            history.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY,ts INTEGER,payload TEXT,n INTEGER)");
            NativeModelRegistry registry = new NativeModelRegistry(directory,model);
            String digest = NativeCandidateStore.save(candidates,model,report(model,String.format("%064d",1)));
            String activated = registry.activateWithHistory(candidates,digest,model.modelVersion,history);
            EdgeService service = new EdgeService();
            android.content.Context context = new android.content.ContextWrapper(null) {
                @Override public File getFilesDir() { return root; }
            };
            java.lang.reflect.Method attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext",android.content.Context.class);
            attach.setAccessible(true); attach.invoke(service,context);
            for (String field : new String[]{"engine","assetEngine","database","previousCommitted","shadowToken"}) {
                java.lang.reflect.Field target = EdgeService.class.getDeclaredField(field); target.setAccessible(true);
                target.set(service,field.equals("database") ? history : field.equals("previousCommitted") ? new JSONObject().put("old",true)
                    : field.equals("shadowToken") ? model.tokens[0] : model);
            }
            java.lang.reflect.Field motionField = EdgeService.class.getDeclaredField("kinematics"); motionField.setAccessible(true);
            KinematicState motion = (KinematicState)motionField.get(service);
            double[] vector = new double[128]; boolean[] coverage = new boolean[128]; Arrays.fill(coverage,true);
            motion.update(vector,coverage,1000000000L,model.alpha,1000);
            if (!motion.update(vector,coverage,2000000000L,model.alpha,2000).continuous) throw new IllegalStateException("motion fixture not continuous");
            java.lang.reflect.Field slowingField = EdgeService.class.getDeclaredField("slowing"); slowingField.setAccessible(true);
            CriticalSlowingDetector slowing = (CriticalSlowingDetector)slowingField.get(service);
            for (int index = 0; index < 40; index++) slowing.update(model.tokens[0],0,0,true);
            java.lang.reflect.Field warmField = EdgeService.class.getDeclaredField("previousWarm"); warmField.setAccessible(true);
            warmField.set(service,WarmBucket.next(1000,false,null));
            java.lang.reflect.Method refresh = EdgeService.class.getDeclaredMethod("refreshModel"); refresh.setAccessible(true); refresh.invoke(service);
            java.lang.reflect.Field engineField = EdgeService.class.getDeclaredField("engine"); engineField.setAccessible(true);
            if (!activated.equals(((ModelEngine)engineField.get(service)).modelVersion) || warmField.get(service) != null
                    || motion.update(vector,coverage,3000000000L,model.alpha,3000).continuous
                    || slowing.update(model.tokens[0],0,0,true).dwellSteps != 1)
                throw new IllegalStateException("service model switch retained old continuity");
            for (String field : new String[]{"previousCommitted","shadowToken"}) {
                java.lang.reflect.Field target = EdgeService.class.getDeclaredField(field); target.setAccessible(true);
                if (target.get(service) != null) throw new IllegalStateException("service model switch retained " + field);
            }
            JSONObject receipt = new JSONObject(new String(Files.readAllBytes(new File(root,"active_model.json").toPath()),StandardCharsets.UTF_8));
            if (!activated.equals(receipt.getString("model_version")) || receipt.getBoolean("loaded_at_service_creation"))
                throw new IllegalStateException("service reload receipt mismatch");
            refresh.invoke(service);
            if (!motion.update(vector,coverage,4000000000L,model.alpha,4000).continuous)
                throw new IllegalStateException("unchanged model reset continuity");
            double[] durations = new double[128]; double total = 0;
            for (int index = 0; index < durations.length; index++) {
                long start = System.nanoTime(); refresh.invoke(service);
                durations[index] = (System.nanoTime() - start) / 1e6; total += durations[index];
            }
            Arrays.sort(durations);
            return new JSONObject().put("passed",true).put("actual_service_refresh_method_executed",true)
                .put("unchanged_refresh_measurements",durations.length).put("unchanged_refresh_mean_ms",total / durations.length)
                .put("unchanged_refresh_p95_ms",durations[(int)Math.ceil(durations.length * 0.95) - 1])
                .put("unchanged_refresh_max_ms",durations[durations.length - 1])
                .put("model_identity_and_reload_receipt_verified",true).put("motion_csd_warm_and_previous_frame_reset",true)
                .put("unchanged_model_preserves_continuity",true)
                .put("scope","attached_synthetic_service_object_not_onCreate_bound_service_or_sensor_lifecycle");
        } finally {
            File[] children = root.listFiles(); if (children != null) for (File child : children) {
                if (child.isDirectory()) { File[] files = child.listFiles(); if (files != null) for (File file : files) file.delete(); }
                child.delete();
            }
            root.delete();
        }
    }

    static JSONObject verifyHistoryCapacity(ModelEngine model, File cache) throws Exception {
        File root = new File(cache,"history-capacity-fixture-" + java.util.UUID.randomUUID());
        File candidates = new File(root,"candidates"), directory = new File(root,"registry");
        try (android.database.sqlite.SQLiteDatabase history = android.database.sqlite.SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(history);
            ColdTrajectory.register(history,model.modelVersion,model.normalizerDigest,model.tokens,model.coldBundle());
            history.execSQL("INSERT INTO cold_edges VALUES (?,?,?,11)",new Object[]{model.modelVersion,model.tokens[0],model.tokens[0]});
            ColdLineage.migrateOnce(history);
            history.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY,payload TEXT)");
            history.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY,ts INTEGER,payload TEXT,n INTEGER)");
            NativeModelRegistry registry = new NativeModelRegistry(directory,model);
            ModelEngine current = model;
            for (int index = 1; index <= 7; index++) {
                history.execSQL("INSERT INTO warm_trajectory VALUES (?,?,?,1)",new Object[]{index,index * 30000L,new JSONObject().put("model_version",current.modelVersion).toString()});
                String digest = NativeCandidateStore.save(candidates,current,report(current,String.format("%064d",index)));
                registry.activateWithHistory(candidates,digest,current.modelVersion,history);
                current = registry.active();
            }
            history.execSQL("INSERT INTO warm_trajectory VALUES (8,240000,?,1)",new Object[]{new JSONObject().put("model_version",current.modelVersion).toString()});
            String digest = NativeCandidateStore.save(candidates,current,report(current,String.format("%064d",8)));
            byte[] oldPointer = Files.readAllBytes(new File(directory,"current.json").toPath());
            boolean full = false;
            try { registry.activateWithHistory(candidates,digest,current.modelVersion,history); }
            catch (IllegalStateException expected) { full = true; }
            if (!full || history.inTransaction() || android.database.DatabaseUtils.queryNumEntries(history,"cold_models") != 8
                    || !Arrays.equals(oldPointer,Files.readAllBytes(new File(directory,"current.json").toPath())))
                throw new IllegalStateException("fully referenced history capacity did not safely refuse activation");
            history.execSQL("DELETE FROM warm_trajectory WHERE bucket=1");
            history.execSQL("CREATE TRIGGER reject_capacity_admission BEFORE INSERT ON cold_models WHEN NEW.namespace != '" + current.modelVersion + "' BEGIN SELECT RAISE(ABORT,'synthetic admission rejected'); END");
            boolean rejected = false;
            try { registry.activateWithHistory(candidates,digest,current.modelVersion,history); }
            catch (android.database.sqlite.SQLiteConstraintException expected) { rejected = true; }
            history.execSQL("DROP TRIGGER reject_capacity_admission");
            if (!rejected || history.inTransaction() || android.database.DatabaseUtils.queryNumEntries(history,"cold_models") != 8
                    || android.database.DatabaseUtils.longForQuery(history,"SELECT COUNT(*) FROM cold_models WHERE namespace=?",new String[]{model.modelVersion}) != 1
                    || !Arrays.equals(oldPointer,Files.readAllBytes(new File(directory,"current.json").toPath())))
                throw new IllegalStateException("admission failure did not roll back version reclamation");
            registry.activateWithHistory(candidates,digest,current.modelVersion,history);
            if (android.database.DatabaseUtils.queryNumEntries(history,"cold_models") != 8
                    || android.database.DatabaseUtils.longForQuery(history,"SELECT COUNT(*) FROM cold_models WHERE namespace=?",new String[]{model.modelVersion}) != 0
                    || android.database.DatabaseUtils.longForQuery(history,"SELECT SUM(n) FROM cold_lineage_edges",null) != 11)
                throw new IllegalStateException("capacity reclamation lost bound or cumulative counts");
            return new JSONObject().put("passed",true).put("fully_referenced_capacity_rejected",true)
                .put("admission_failure_rolls_back_reclamation",true).put("released_reference_reclaimed_before_admission",true)
                .put("cumulative_lineage_counts_preserved",true)
                .put("scope","synthetic_eight_version_database_and_registry_capacity_not_full_model_disk_bound_or_real_weekly_cadence");
        } finally {
            for (File child : new File[]{candidates,directory}) {
                File[] files = child.listFiles(); if (files != null) for (File file : files) file.delete(); child.delete();
            }
            root.delete();
        }
    }

    static JSONObject verifyArchiveAdmissionGate(ModelEngine model, File cache) throws Exception {
        File root = new File(cache,"archive-admission-fixture-" + java.util.UUID.randomUUID());
        File candidates = new File(root,"candidates"), directory = new File(root,"registry");
        try (android.database.sqlite.SQLiteDatabase history = android.database.sqlite.SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(history);
            ColdTrajectory.register(history,model.modelVersion,model.normalizerDigest,model.tokens,model.coldBundle());
            ColdLineage.migrateOnce(history);
            history.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY,payload TEXT)");
            history.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY,ts INTEGER,payload TEXT,n INTEGER)");
            history.execSQL("INSERT INTO frames VALUES (1000,?)",new Object[]{new JSONObject().put("model_version",model.modelVersion).toString()});
            NativeModelRegistry registry = new NativeModelRegistry(directory,model);
            ModelEngine current = model;
            for (int index = 1; index <= 2; index++) {
                String digest = NativeCandidateStore.save(candidates,current,report(current,String.format("%064d",index)));
                registry.activateWithHistory(candidates,digest,current.modelVersion,history); current = registry.active();
            }
            JSONObject bundle = new JSONObject(model.coldBundle().toString());
            byte[] random = new byte[600000]; new java.util.Random(41).nextBytes(random);
            bundle.put("size_fixture_padding",java.util.Base64.getEncoder().encodeToString(random));
            history.execSQL("UPDATE cold_models SET bundle=? WHERE namespace=?",new Object[]{bundle.toString(),model.modelVersion});
            byte[] oldPointer = Files.readAllBytes(new File(directory,"current.json").toPath());
            String digest = NativeCandidateStore.save(candidates,current,report(current,String.format("%064d",3)));
            boolean rejected = false;
            try { registry.activateWithHistory(candidates,digest,current.modelVersion,history); }
            catch (IllegalArgumentException expected) { rejected = expected.getMessage().contains("500000"); }
            if (!rejected || history.inTransaction() || android.database.DatabaseUtils.queryNumEntries(history,"cold_models") != 3
                    || !Arrays.equals(oldPointer,Files.readAllBytes(new File(directory,"current.json").toPath())))
                throw new IllegalStateException("archive admission gate accepted oversize candidate or failed rollback");
            File[] temporary = directory.listFiles((parent,name) -> name.startsWith("cold-admission-"));
            if (temporary == null || temporary.length != 0) throw new IllegalStateException("admission checkpoint temporary file leaked");
            history.execSQL("UPDATE cold_models SET bundle=? WHERE namespace=?",new Object[]{model.coldBundle().toString(),model.modelVersion});
            registry.activateWithHistory(candidates,digest,current.modelVersion,history);
            return new JSONObject().put("passed",true).put("oversize_archive_rejected_before_database_commit_and_publication",true)
                .put("database_and_pointer_preserved",true).put("temporary_checkpoint_removed",true).put("bounded_retry_accepted",true)
                .put("scope","synthetic_unreferenced_current_model_padding_archive_failure_not_all_future_model_sizes");
        } finally {
            for (File child : new File[]{candidates,directory}) {
                File[] files = child.listFiles(); if (files != null) for (File file : files) file.delete(); child.delete();
            }
            root.delete();
        }
    }

}
