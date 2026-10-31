package com.inceptless.traceweave.edge;

import org.json.JSONArray;
import org.json.JSONObject;

final class SpectrumSdkFixture {
    private static JSONObject threadEnvironment() throws Exception {
        JSONObject result = new JSONObject().put("thread_id", android.os.Process.myTid())
            .put("thread_priority", android.os.Process.getThreadPriority(android.os.Process.myTid()));
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(
                "/proc/self/task/" + android.os.Process.myTid() + "/status"))) {
            if (line.startsWith("Cpus_allowed_list:")) result.put("cpus_allowed", line.substring(line.indexOf(':') + 1).trim());
        }
        return result;
    }
    static JSONObject verify() throws Exception {
        JSONObject environmentBefore = threadEnvironment();
        int pairs = 86400, states = 16, horizon = 120;
        double[] observed = new double[pairs * 2], queries = new double[horizon * 2], prior = new double[states * states];
        int[] sources = new int[pairs], targets = new int[pairs];
        for (int pair = 0; pair < pairs; pair++) {
            int seconds = pair * 30;
            double fraction = (seconds % 86400) / 86400.0;
            observed[pair * 2] = 2 * Math.PI * fraction;
            observed[pair * 2 + 1] = 2 * Math.PI * ((seconds / 86400) % 7 + fraction) / 7;
            sources[pair] = pair % states; targets[pair] = (pair + pair / states + 1) % states;
            prior[sources[pair] * states + targets[pair]]++;
        }
        for (int source = 0; source < states; source++) {
            double total = 0;
            for (int target = 0; target < states; target++) total += prior[source * states + target];
            for (int target = 0; target < states; target++) prior[source * states + target] /= total;
        }
        for (int step = 0; step < horizon; step++) {
            double fraction = 0.25 + step * 30.0 / 86400;
            queries[step * 2] = 2 * Math.PI * fraction; queries[step * 2 + 1] = 2 * Math.PI * fraction / 7;
        }
        long start = System.nanoTime();
        FittedPeriodicSpectrum snapshot = new FittedPeriodicSpectrum(states, observed, sources, targets);
        double fitMillis = (System.nanoTime() - start) / 1e6, maximum = 0, freshFitMillis = 0, freshPipelineMillis = 0;
        JSONArray trials = new JSONArray(), profiles = new JSONArray(), pipelineTrials = new JSONArray(), pipelineProfiles = new JSONArray(), fusedTrials = new JSONArray();
        try (FittedPeriodicSpectrum owned = snapshot) {
            double[] exact = NativeCore.periodicCdf(prior, queries, observed, sources, targets, states);
            for (int trial = 0; trial < 5; trial++) {
                start = System.nanoTime(); double[] actual = owned.cdf(prior, queries);
                trials.put((System.nanoTime() - start) / 1e6);
                double[] timing = owned.timings();
                profiles.put(new JSONObject().put("lock_wait_ms", timing[0]).put("input_copy_allocation_ms", timing[1])
                    .put("native_cdf_wall_ms", timing[2]).put("output_copy_ms", timing[3])
                    .put("native_total_ms", timing[4]).put("native_cdf_thread_cpu_ms", timing[5]));
                for (int index = 0; index < exact.length; index++) maximum = Math.max(maximum, Math.abs(actual[index] - exact[index]));
            }
            double[] raw = new double[128], median = new double[128], iqr = new double[128], velocity = new double[128];
            double[] centers = new double[states * 128], centerVelocity = new double[centers.length];
            double[] draws = new double[300 * (horizon - 1)];
            java.util.Random random = new java.util.Random(42);
            for (int index = 0; index < raw.length; index++) {
                raw[index] = random.nextDouble() * 100 - 50; median[index] = 0.2; iqr[index] = 2;
                velocity[index] = random.nextDouble() * 2 - 1;
            }
            for (int index = 0; index < centers.length; index++) {
                centers[index] = random.nextDouble(); centerVelocity[index] = random.nextDouble();
            }
            for (int index = 0; index < draws.length; index++) draws[index] = random.nextDouble();
            for (int trial = 0; trial < 5; trial++) {
                start = System.nanoTime();
                double[] position = NativeCore.normalize(raw, median, iqr);
                long normalized = System.nanoTime();
                double[] node = NativeCore.quantize(position, velocity, centers, centerVelocity, states, 0.35);
                long quantized = System.nanoTime();
                double[] cdf = owned.cdf(prior, queries);
                long predicted = System.nanoTime();
                long[] occupancy = NativeCore.rollouts(cdf, states, horizon, 300, (int) node[0], draws);
                long completed = System.nanoTime();
                pipelineTrials.put((completed - start) / 1e6);
                pipelineProfiles.put(new JSONObject().put("normalization_ms", (normalized - start) / 1e6)
                    .put("quantization_ms", (quantized - normalized) / 1e6)
                    .put("cdf_ms", (predicted - quantized) / 1e6)
                    .put("rollouts_ms", (completed - predicted) / 1e6));
                for (int step = 0; step < horizon; step++) {
                    long total = 0;
                    for (int state = 0; state < states; state++) total += occupancy[step * states + state];
                    if (total != 300) throw new IllegalStateException("spectrum pipeline mass mismatch");
                }
                start = System.nanoTime();
                double[] fusedPosition = NativeCore.normalize(raw, median, iqr);
                double[] fusedNode = NativeCore.quantize(fusedPosition, velocity, centers, centerVelocity, states, 0.35);
                long[] fused = owned.rollouts(prior, queries, 300, (int) fusedNode[0], draws);
                fusedTrials.put((System.nanoTime() - start) / 1e6);
                if (!java.util.Arrays.equals(occupancy, fused))
                    throw new IllegalStateException("fused spectrum rollout parity mismatch");
            }
            start = System.nanoTime();
            FittedPeriodicSpectrum fresh = new FittedPeriodicSpectrum(states, observed, sources, targets);
            freshFitMillis = (System.nanoTime() - start) / 1e6;
            try (FittedPeriodicSpectrum firstQuery = fresh) {
                start = System.nanoTime();
                double[] firstPosition = NativeCore.normalize(raw, median, iqr);
                double[] firstNode = NativeCore.quantize(firstPosition, velocity, centers, centerVelocity, states, 0.35);
                long[] firstOccupancy = firstQuery.rollouts(prior, queries, 300, (int) firstNode[0], draws);
                freshPipelineMillis = (System.nanoTime() - start) / 1e6;
                long[] reference = NativeCore.rollouts(exact, states, horizon, 300, (int) firstNode[0], draws);
                if (!java.util.Arrays.equals(reference, firstOccupancy))
                    throw new IllegalStateException("fresh fused pipeline reference mismatch");
            }
            for (double[] invalidDraws : new double[][]{new double[0], draws.clone()}) {
                if (invalidDraws.length > 0) invalidDraws[0] = Double.NaN;
                boolean rejected = false;
                try { owned.rollouts(prior, queries, 300, 0, invalidDraws); }
                catch (IllegalArgumentException expected) { rejected = true; }
                if (!rejected) throw new IllegalStateException("invalid fused draws accepted");
            }
            long[] single = owned.rollouts(prior, new double[]{0, 0}, 300, 0, new double[0]);
            if (single[0] != 300 || java.util.Arrays.stream(single).sum() != 300)
                throw new IllegalStateException("one-step fused rollout mismatch");
        }
        if (maximum > 1e-12) throw new IllegalStateException("spectrum SDK CDF mismatch");
        boolean closed = false, stale = false, forged = false, malformed = false;
        try { snapshot.cdf(prior, queries); } catch (IllegalStateException expected) { closed = true; }
        boolean fusedClosed = false, fusedForged = false;
        try { snapshot.rollouts(prior, queries, 1, 0, new double[horizon - 1]); }
        catch (IllegalStateException expected) { fusedClosed = true; }
        try { NativeCore.spectrumRollouts(Long.MAX_VALUE, prior, queries, 1, 0, new double[horizon - 1]); }
        catch (IllegalArgumentException expected) { fusedForged = true; }
        if (!fusedClosed || !fusedForged) throw new IllegalStateException("fused spectrum handle guard mismatch");
        snapshot.close();
        long raw = NativeCore.fitSpectrum(1, new double[0], new int[0], new int[0]);
        NativeCore.closeSpectrum(raw);
        try { NativeCore.spectrumCdf(raw, new double[]{1}, new double[]{0, 0}); } catch (IllegalArgumentException expected) { stale = true; }
        try { NativeCore.spectrumCdf(Long.MAX_VALUE, new double[]{1}, new double[]{0, 0}); } catch (IllegalArgumentException expected) { forged = true; }
        try (FittedPeriodicSpectrum invalid = new FittedPeriodicSpectrum(1, new double[]{0.1, 0}, new int[]{0}, new int[]{0})) { }
        catch (IllegalArgumentException expected) { malformed = true; }
        if (!closed || !stale || !forged || !malformed) throw new IllegalStateException("spectrum lifecycle guard mismatch");
        return new JSONObject().put("passed", true).put("history_pairs", pairs).put("states", states).put("horizon", horizon)
            .put("thread_environment_before", environmentBefore).put("thread_environment_after", threadEnvironment()).put("native_profiles", profiles).put("fit_ms", fitMillis).put("cdf_trials_ms", trials).put("maximum_cdf_error", maximum)
            .put("pipeline_trials_ms", pipelineTrials).put("pipeline_rollouts", 300)
            .put("pipeline_profiles", pipelineProfiles)
            .put("fused_pipeline_trials_ms", fusedTrials).put("fused_occupancy_parity", true)
            .put("fresh_snapshot_fit_ms", freshFitMillis).put("fresh_fused_pipeline_ms", freshPipelineMillis)
            .put("fused_draw_and_handle_guards", true)
            .put("pipeline_scope", "128D_normalization_16_state_quantization_fitted_CDF_300_rollouts_including_JNI_copy_not_fit_history_scan_or_phase_draw_preparation")
            .put("closed_wrapper_rejected", closed).put("stale_handle_rejected", stale).put("forged_handle_rejected", forged)
            .put("malformed_fit_rejected", malformed).put("scope", "synthetic_monthly_JNI_fitted_snapshot_not_live_window_cache_or_full_memory_gate");
    }
}
