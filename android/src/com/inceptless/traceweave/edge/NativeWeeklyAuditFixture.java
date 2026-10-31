package com.inceptless.traceweave.edge;

import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

final class NativeWeeklyAuditFixture {
    static JSONObject verify(ModelEngine model, java.io.File directory) throws Exception {
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(database);
            ColdTrajectory.register(database, model.modelVersion, model.normalizerDigest, model.tokens, model.coldBundle());
            database.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY, payload TEXT)");
            JSONArray zeros = new JSONArray(new double[128]);
            for (int index = 0; index < 4; index++) {
                JSONObject frame = new JSONObject().put("model_version", model.modelVersion).put("model_sha256", model.modelDigest)
                    .put("normalizer_sha256", model.normalizerDigest).put("session_id", "synthetic_weekly")
                    .put("source_mode", "synthetic_fixture").put("state_token", model.tokens[0])
                    .put("continuous_vector", zeros).put("continuous_velocity", zeros)
                    .put("timestamp_epoch_ms", index * 1000L).put("elapsed_realtime_nanos", index * 1000000000L)
                    .put("continuous_from_previous", index > 0);
                database.execSQL("INSERT INTO frames VALUES (?,?)", new Object[]{index * 1000L, frame.toString()});
            }
            JSONObject clean = NativeWeeklyAudit.run(database, model), repeated = NativeWeeklyAudit.run(database, model);
            TopologyStrain.Result reference = model.assessTopology(new double[128], new double[128]);
            if (clean.getLong("topology_assessed_records") != 4
                    || Math.abs(clean.getDouble("mean_energy_distance") - reference.energy) > 1e-9
                    || clean.getDouble("topographic_error") != (reference.nonadjacent ? 1 : 0)
                    || !clean.getBoolean("candidate_input_available") || clean.getLong("continuous_pairs") != 3
                    || !clean.getString("source_sha256").equals(repeated.getString("source_sha256")))
                throw new IllegalStateException("clean audit or source binding mismatch");
            try (android.database.Cursor row = database.rawQuery("SELECT payload FROM frames WHERE ts=1000", null)) {
                row.moveToFirst();
                JSONObject changed = new JSONObject(row.getString(0)).put("model_sha256", "different");
                database.execSQL("UPDATE frames SET payload=? WHERE ts=1000", new Object[]{changed.toString()});
            }
            JSONObject changed = NativeWeeklyAudit.run(database, model);
            if (changed.getBoolean("candidate_input_available") || changed.getLong("invalid_records") != 1
                    || changed.getLong("continuous_pairs") != 1 || changed.getString("source_sha256").equals(clean.getString("source_sha256")))
                throw new IllegalStateException("changed binding failed to break audit continuity");
            database.execSQL("UPDATE frames SET payload='{' WHERE ts=1000");
            JSONObject malformed = NativeWeeklyAudit.run(database, model);
            if (malformed.getLong("records") != 4 || malformed.getLong("invalid_records") != 1
                    || malformed.getLong("continuous_pairs") != 1 || malformed.getBoolean("candidate_activation_authorized")
                    || android.database.DatabaseUtils.queryNumEntries(database, "frames") != 4)
                throw new IllegalStateException("malformed source audit or read-only mismatch");
            try (android.database.Cursor row = database.rawQuery("SELECT payload FROM frames WHERE ts=2000", null)) {
                row.moveToFirst();
                JSONObject invalidVector = new JSONObject(row.getString(0));
                invalidVector.getJSONArray("continuous_vector").put(0, "NaN");
                database.execSQL("UPDATE frames SET payload=? WHERE ts=2000", new Object[]{invalidVector.toString()});
            }
            JSONObject coordinates = NativeWeeklyAudit.run(database, model);
            if (coordinates.getLong("invalid_records") != 2 || coordinates.getLong("continuous_pairs") != 0)
                throw new IllegalStateException("nonnumeric coordinate entered candidate input");
            Thread.currentThread().interrupt();
            boolean cancelled = false;
            try { NativeWeeklyAudit.run(database, model); }
            catch (InterruptedException expected) { cancelled = true; }
            finally { Thread.interrupted(); }
            if (!cancelled || database.inTransaction()) throw new IllegalStateException("audit cancellation leaked transaction");
            double[] originalCenters = model.centers.clone(), originalVelocity = model.centerVelocity.clone();
            IncrementalManifoldFit fit = model.incrementalFit(12);
            double[] invalidPosition = new double[128];
            invalidPosition[0] = Double.NaN;
            boolean invalidRejected = false;
            try { fit.update(invalidPosition, new double[128]); }
            catch (IllegalArgumentException expected) { invalidRejected = true; }
            if (!invalidRejected || !java.util.Arrays.equals(fit.centers(), originalCenters))
                throw new IllegalStateException("invalid fit input changed candidate");
            for (int epoch = 0; epoch < 6; epoch++) for (int sample = 0; sample < 12; sample++) {
                double[] position = new double[128], velocity = new double[128];
                for (int index = 0; index < 128; index++) position[index] = 0.2 * Math.sin(sample + index * 0.1);
                fit.update(position, velocity);
            }
            if (!fit.complete() || java.util.Arrays.equals(fit.centers(), originalCenters)
                    || !java.util.Arrays.equals(model.centers, originalCenters)
                    || !java.util.Arrays.equals(model.centerVelocity, originalVelocity))
                throw new IllegalStateException("warm-start fit completion or baseline isolation mismatch");
            for (double value : fit.centers()) if (!Double.isFinite(value)) throw new IllegalStateException("nonfinite fitted center");
            database.execSQL("DELETE FROM frames");
            for (int sample = 0; sample < 100; sample++) {
                double[] position = new double[128];
                for (int index = 0; index < 128; index++) position[index] = 0.2 * Math.sin(sample % 12 + index * 0.1);
                JSONObject frame = new JSONObject().put("model_version", model.modelVersion).put("model_sha256", model.modelDigest)
                    .put("normalizer_sha256", model.normalizerDigest).put("session_id", "candidate_fixture")
                    .put("source_mode", "synthetic_fixture").put("state_token", model.tokens[0])
                    .put("continuous_vector", new JSONArray(position)).put("continuous_velocity", zeros)
                    .put("timestamp_epoch_ms", sample * 1000L).put("elapsed_realtime_nanos", sample * 1000000000L)
                    .put("continuous_from_previous", sample > 0);
                database.execSQL("INSERT INTO frames VALUES (?,?)", new Object[]{sample * 1000L, frame.toString()});
            }
            JSONObject candidate = NativeCandidateCalibration.run(database, model, directory);
            int[][] fittedGrid = new int[model.tokens.length][2];
            for (int node = 0; node < fittedGrid.length; node++) for (int axis = 0; axis < 2; axis++)
                fittedGrid[node][axis] = candidate.getJSONArray("candidate_grid").getJSONArray(node).getInt(axis);
            IncrementalManifoldFit directFit = model.incrementalFit(70, fittedGrid);
            int[] directOrder = new int[70];
            for (int index = 0; index < directOrder.length; index++) directOrder[index] = index;
            java.util.Random shuffle = new java.util.Random(42);
            for (int epoch = 0; epoch < 6; epoch++) {
                for (int index = 69; index > 0; index--) {
                    int other = shuffle.nextInt(index + 1), swap = directOrder[index];
                    directOrder[index] = directOrder[other]; directOrder[other] = swap;
                }
                for (int sample : directOrder) {
                    double[] position = new double[128];
                    for (int index = 0; index < 128; index++) position[index] = 0.2 * Math.sin(sample % 12 + index * 0.1);
                    directFit.update(position, new double[128]);
                }
            }
            double[] directCenters = directFit.centers(), directVelocity = directFit.velocities();
            for (int index = 0; index < directCenters.length; index++)
                if (Double.doubleToLongBits(directCenters[index]) != Double.doubleToLongBits(candidate.getJSONArray("candidate_centers").getDouble(index))
                        || Double.doubleToLongBits(directVelocity[index]) != Double.doubleToLongBits(candidate.getJSONArray("candidate_velocity").getDouble(index)))
                    throw new IllegalStateException("spooled fit differs from direct synthetic coordinates");
            if (candidate.getInt("fit_preparation_json_decodes") != 70)
                throw new IllegalStateException("training frame decoded more than once");
            if (candidate.getJSONObject("potential").getInt("fit_pair_count") != 69)
                throw new IllegalStateException("training potential pair count mismatch");
            String originalTrainingFrame;
            try (android.database.Cursor rows = database.rawQuery("SELECT payload FROM frames WHERE ts=20000", null)) {
                rows.moveToFirst(); originalTrainingFrame = rows.getString(0);
            }
            JSONObject sessionBoundary = new JSONObject(originalTrainingFrame).put("session_id", "other_training_session");
            database.execSQL("UPDATE frames SET payload=? WHERE ts=20000", new Object[]{sessionBoundary.toString()});
            JSONObject boundaryCandidate = NativeCandidateCalibration.run(database, model, directory);
            if (boundaryCandidate.getJSONObject("potential").getInt("fit_pair_count") != 67)
                throw new IllegalStateException("potential crossed training session boundary");
            database.execSQL("UPDATE frames SET payload=? WHERE ts=20000", new Object[]{originalTrainingFrame});
            try (android.database.Cursor rows = database.rawQuery("SELECT payload FROM frames WHERE ts=99000", null)) {
                rows.moveToFirst();
                JSONObject frame = new JSONObject(rows.getString(0));
                frame.getJSONArray("continuous_vector").put(0, 20);
                database.execSQL("UPDATE frames SET payload=? WHERE ts=99000", new Object[]{frame.toString()});
            }
            JSONObject alteredHoldout = NativeCandidateCalibration.run(database, model, directory);
            if (candidate.getInt("training_records") != 70 || candidate.getInt("holdout_records") != 30
                    || candidate.getLong("training_end_epoch_ms") >= candidate.getLong("holdout_start_epoch_ms")
                    || !candidate.getJSONArray("candidate_centers").toString().equals(alteredHoldout.getJSONArray("candidate_centers").toString())
                    || !candidate.getJSONArray("candidate_velocity").toString().equals(alteredHoldout.getJSONArray("candidate_velocity").toString())
                    || !candidate.getJSONArray("candidate_grid").toString().equals(alteredHoldout.getJSONArray("candidate_grid").toString())
                    || !candidate.getJSONObject("potential").getJSONArray("relative_energy").toString().equals(alteredHoldout.getJSONObject("potential").getJSONArray("relative_energy").toString())
                    || candidate.getString("source_sha256").equals(alteredHoldout.getString("source_sha256"))
                    || candidate.getBoolean("candidate_activated") || database.inTransaction())
                throw new IllegalStateException("candidate split, holdout leakage or isolation mismatch");
            JSONObject snapshotReport;
            database.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)");
            database.execSQL("INSERT INTO warm_trajectory VALUES (90000,99000,'synthetic_warm_copy',1)");
            database.execSQL("UPDATE warm_trajectory SET n=1.5");
            boolean fractionalWarmRejected = false;
            try (WeeklySnapshot invalidWarm = WeeklySnapshot.copy(database, directory, 99000)) {}
            catch (IllegalArgumentException expected) { fractionalWarmRejected = true; }
            if (!fractionalWarmRejected || database.inTransaction())
                throw new IllegalStateException("fractional warm snapshot accepted or source transaction retained");
            database.execSQL("UPDATE warm_trajectory SET n=1");
            database.execSQL("INSERT INTO cold_edges VALUES (?,?,?,7)", new Object[]{model.modelVersion, model.tokens[0], model.tokens[0]});
            try (WeeklySnapshot isolated = WeeklySnapshot.copy(database, directory, 99000)) {
                JSONObject copiedAudit = NativeWeeklyAudit.run(isolated.database, model);
                if (!copiedAudit.getString("source_sha256").equals(alteredHoldout.getString("source_sha256")) || database.inTransaction())
                    throw new IllegalStateException("snapshot source identity or source lock release mismatch");
                database.execSQL("DELETE FROM frames WHERE ts=99000");
                database.execSQL("DELETE FROM warm_trajectory");
                database.execSQL("DELETE FROM cold_edges");
                if (android.database.DatabaseUtils.queryNumEntries(isolated.database, "cold_states") != model.tokens.length
                        || ColdTrajectory.transitions(isolated.database) != 7)
                    throw new IllegalStateException("cold snapshot state/count preservation or source isolation mismatch");
                if (!isolated.warmIncluded || android.database.DatabaseUtils.queryNumEntries(isolated.database, "warm_trajectory") != 1
                        || !"synthetic_warm_copy".equals(android.database.DatabaseUtils.stringForQuery(isolated.database,
                            "SELECT payload FROM warm_trajectory WHERE bucket=90000", null)))
                    throw new IllegalStateException("warm snapshot copy or source isolation mismatch");
                JSONObject isolatedCandidate = NativeCandidateCalibration.run(isolated.database, model, directory);
                if (!isolatedCandidate.getString("source_sha256").equals(alteredHoldout.getString("source_sha256"))
                        || isolatedCandidate.getInt("holdout_records") != 30)
                    throw new IllegalStateException("source modification changed isolated candidate input");
                boolean writeRejected = false;
                try { isolated.database.execSQL("DELETE FROM frames"); }
                catch (android.database.sqlite.SQLiteException expected) { writeRejected = true; }
                if (!writeRejected) throw new IllegalStateException("snapshot allowed writes after copy");
                snapshotReport = new JSONObject().put("source_identity_preserved", true).put("source_lock_released", true)
                    .put("warm_copy_source_isolation_verified", true)
                    .put("fractional_warm_copy_rejected", true)
                    .put("cold_state_count_source_isolation_verified", true)
                    .put("later_source_change_isolated", true).put("snapshot_write_rejected", true)
                    .put("calibration_timing_ms", isolatedCandidate.getJSONObject("timing_ms"));
            }
            java.io.File candidates = new java.io.File(directory, "candidates-" + java.util.UUID.randomUUID());
            JSONObject persistence;
            try {
                String digest = NativeCandidateStore.save(candidates, model, candidate);
                JSONObject reopened = NativeCandidateStore.read(candidates, digest);
                if (!reopened.getJSONObject("validation").getString("source_sha256").equals(candidate.getString("source_sha256"))
                        || !reopened.getJSONObject("model").getString("parent_model_sha256").equals(model.modelDigest)
                        || reopened.getJSONObject("model").has("model_sha256")
                        || !reopened.getJSONObject("model").getJSONArray("grid_coordinates").toString().equals(candidate.getJSONArray("candidate_grid").toString())
                        || !reopened.getJSONObject("model").getJSONObject("potential").toString().equals(candidate.getJSONObject("potential").toString())
                        || !reopened.getJSONObject("model").getBoolean("potential_inferred")
                        || !digest.equals(NativeCandidateStore.save(candidates, model, candidate)))
                    throw new IllegalStateException("candidate persistence binding or idempotence mismatch");
                JSONObject rejected = new JSONObject(candidate.toString()).put("accepted", false);
                boolean rejection = false;
                try { NativeCandidateStore.save(candidates, model, rejected); }
                catch (IllegalArgumentException expected) { rejection = true; }
                if (!rejection) throw new IllegalStateException("rejected candidate saved");
                JSONObject wrongPotential = new JSONObject(candidate.toString());
                wrongPotential.getJSONObject("potential").put("normalizer_sha256", String.format("%064d", 3));
                rejection = false;
                try { NativeCandidateStore.save(candidates, model, wrongPotential); }
                catch (IllegalArgumentException expected) { rejection = true; }
                if (!rejection) throw new IllegalStateException("unbound potential saved");
                java.io.File registryDirectory = new java.io.File(directory, "registry-" + java.util.UUID.randomUUID());
                try {
                    NativeModelRegistry registry = new NativeModelRegistry(registryDirectory, model);
                    if (!registry.active().modelVersion.equals(model.modelVersion))
                        throw new IllegalStateException("registry fallback changed");
                    boolean stale = false;
                    try { registry.activate(candidates, digest, String.format("%064d", 9)); }
                    catch (IllegalStateException expected) { stale = true; }
                    if (!stale || new java.io.File(registryDirectory, "current.json").exists())
                        throw new IllegalStateException("stale activation changed pointer");
                    String activated = registry.activate(candidates, digest, model.modelVersion);
                    ModelEngine loaded = new NativeModelRegistry(registryDirectory, model).active();
                    JSONObject quantified = loaded.process(loaded.medians, new double[loaded.names.length]);
                    int potentialNode = java.util.Arrays.asList(loaded.tokens).indexOf(quantified.getString("state_token"));
                    if (!quantified.getBoolean("state_potential_available") || quantified.getDouble("state_relative_potential")
                            != candidate.getJSONObject("potential").getJSONArray("relative_energy").getDouble(potentialNode)
                            || model.process(model.medians, new double[model.names.length]).getBoolean("state_potential_available"))
                        throw new IllegalStateException("state potential availability or token binding mismatch");
                    if (activated.equals(model.modelVersion) || !loaded.modelVersion.equals(activated)
                            || !loaded.normalizerDigest.equals(model.normalizerDigest)
                            || !java.util.Arrays.equals(loaded.centers, numeric(candidate.getJSONArray("candidate_centers")))
                            || !loaded.registrySnapshot().getJSONObject("potential").toString().equals(candidate.getJSONObject("potential").toString()))
                        throw new IllegalStateException("registered candidate identity or values changed");
                    stale = false;
                    try { registry.activate(candidates, digest, model.modelVersion); }
                    catch (IllegalStateException expected) { stale = true; }
                    if (!stale) throw new IllegalStateException("stale candidate replay activated");
                    JSONObject pointer = new JSONObject(new String(java.nio.file.Files.readAllBytes(
                        new java.io.File(registryDirectory, "current.json").toPath()), java.nio.charset.StandardCharsets.UTF_8));
                    java.nio.file.Files.write(new java.io.File(registryDirectory, pointer.getString("current") + ".json").toPath(), new byte[]{123});
                    boolean corrupt = false;
                    try { registry.active(); }
                    catch (IllegalArgumentException expected) { corrupt = true; }
                    if (!corrupt || !registry.rollback(activated).equals(model.modelVersion)
                            || !java.util.Arrays.equals(registry.active().centers, model.centers))
                        throw new IllegalStateException("corrupt active snapshot rollback failed");
                } finally {
                    java.io.File[] registryFiles = registryDirectory.listFiles();
                    if (registryFiles != null) for (java.io.File file : registryFiles) file.delete();
                    registryDirectory.delete();
                }
                java.io.File candidatePath = new java.io.File(candidates, digest + ".json");
                java.io.File candidateBackup = new java.io.File(candidates, digest + ".json.bak");
                if (!candidatePath.renameTo(candidateBackup)) throw new IllegalStateException("candidate backup fixture creation failed");
                for (int index = 1; index <= 5; index++) {
                    JSONObject variant = new JSONObject(candidate.toString()).put("fixture_retention_sequence", index);
                    String saved = NativeCandidateStore.save(candidates, model, variant);
                    NativeCandidateStore.read(candidates, saved);
                    if (index == 1 && (!candidatePath.exists() || candidateBackup.exists()))
                        throw new IllegalStateException("candidate backup recovery not included in retention");
                    java.io.File[] retained = candidates.listFiles((parent, name) -> name.matches("[0-9a-f]{64}\\.json"));
                    if (retained == null || retained.length > 4) throw new IllegalStateException("rolling candidate retention failed");
                }
                digest = NativeCandidateStore.save(candidates, model, candidate);
                java.nio.file.Files.write(new java.io.File(candidates, digest + ".json").toPath(), new byte[]{123});
                boolean corruption = false;
                try { NativeCandidateStore.read(candidates, digest); }
                catch (IllegalArgumentException expected) { corruption = true; }
                if (!corruption) throw new IllegalStateException("candidate corruption accepted");
                persistence = new JSONObject().put("reopened_source_parent_binding", true).put("idempotent_save", true)
                    .put("rejected_candidate_not_saved", true).put("corruption_rejected", true).put("candidate_activated", false);
                persistence.put("unbound_potential_rejected", true);
                persistence.put("registry_activation_and_corrupt_snapshot_rollback_verified", true);
                persistence.put("candidate_rolling_retention_verified", true);
                persistence.put("candidate_backup_retention_verified", true);
            } finally {
                java.io.File[] files = candidates.listFiles();
                if (files != null) for (java.io.File file : files) file.delete();
                candidates.delete();
            }
            return new JSONObject().put("passed", true).put("clean", clean).put("changed_binding", changed)
                .put("malformed", malformed).put("invalid_coordinates_verified", true).put("cancellation_verified", true)
                .put("incremental_fit", new JSONObject().put("six_epochs_completed", true).put("updates", 72)
                    .put("baseline_unchanged", true).put("invalid_input_rejected", true).put("candidate_activated", false))
                .put("candidate_calibration", new JSONObject().put("chronological_70_30_verified", true)
                    .put("holdout_change_does_not_change_fit", true).put("source_change_detected", true)
                    .put("holdout_change_does_not_change_grid", true).put("persisted_grid_matches_candidate", true)
                    .put("structural_change", candidate.getJSONObject("structural_change"))
                    .put("training_potential_pair_count", candidate.getJSONObject("potential").getInt("fit_pair_count"))
                    .put("fit_preparation_json_decodes", candidate.getInt("fit_preparation_json_decodes"))
                    .put("training_vector_spool_bytes", candidate.getLong("training_vector_spool_bytes"))
                    .put("spooled_fit_matches_direct_bits", true)
                    .put("potential_session_boundaries_excluded", true)
                    .put("holdout_change_does_not_change_potential", true).put("persisted_potential_matches_candidate", true)
                    .put("accepted", candidate.getBoolean("accepted")).put("candidate_activated", false))
                .put("calibration_timing_ms", candidate.getJSONObject("timing_ms"))
                .put("session_boundary_calibration_timing_ms", boundaryCandidate.getJSONObject("timing_ms"))
                .put("changed_holdout_calibration_timing_ms", alteredHoldout.getJSONObject("timing_ms"))
                .put("snapshot", snapshotReport)
                .put("persistence", persistence)
                .put("scope", "synthetic_SQLite_full_audit_not_live_calibration");
        }
    }

    private static double[] numeric(org.json.JSONArray values) throws Exception {
        double[] result = new double[values.length()];
        for (int index = 0; index < result.length; index++) result[index] = values.getDouble(index);
        return result;
    }
}
