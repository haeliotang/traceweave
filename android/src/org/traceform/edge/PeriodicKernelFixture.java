package org.traceform.edge;

import java.time.Instant;
import java.util.Random;
import org.json.JSONObject;
import org.json.JSONArray;

final class PeriodicKernelFixture {
    static JSONObject verify() throws Exception {
        long monday = Instant.parse("2026-10-05T06:00:00Z").toEpochMilli();
        long saturday = monday + 5L * 86400000;
        long[] epochs = new long[80];
        int[] tokens = new int[80];
        String[] segments = new String[80];
        boolean[] continuous = new boolean[80];
        for (int day = 0; day < 2; day++) for (int minute = 0; minute < 20; minute++) {
            int index = day * 40 + minute * 2;
            epochs[index] = (day == 0 ? monday : saturday) + minute * 60000;
            epochs[index + 1] = epochs[index] + 1000;
            tokens[index] = 0; tokens[index + 1] = day + 1;
            segments[index] = segments[index + 1] = "synthetic-day-" + day;
            continuous[index] = minute > 0; continuous[index + 1] = true;
        }
        PeriodicTransitionKernel kernel = new PeriodicTransitionKernel("UTC", 16, epochs, tokens, segments, continuous, 1800000);
        double mondayProbability = kernel.row(0, monday)[1], saturdayProbability = kernel.row(0, saturday)[1];
        if (kernel.eligiblePairs != 78 || mondayProbability <= saturdayProbability)
            throw new IllegalStateException("weekly conditioning mismatch");
        long boundary = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli();
        double[] before = kernel.row(0, boundary - 1), after = kernel.row(0, boundary + 1);
        for (int target = 0; target < 3; target++)
            if (Math.abs(before[target] - after[target]) > 1e-6)
                throw new IllegalStateException("weekly boundary discontinuity");
        for (String zone : new String[]{"UTC", "Asia/Kathmandu", "America/New_York", "Australia/Lord_Howe"}) {
            PeriodicTransitionKernel zoned = new PeriodicTransitionKernel(zone, 16, epochs, tokens, segments, continuous, 1800000);
            for (String text : new String[]{"2026-03-08T06:59:59.999Z", "2026-11-01T05:59:59.999Z", "1969-12-31T23:59:59.999Z"}) {
                long start = Instant.parse(text).toEpochMilli();
                for (long stepMillis : new long[]{30000, 120L * 86400000}) {
                    double[] sequence = zoned.phaseSequence(start, stepMillis, 8);
                    for (int step = 0; step < 8; step++) {
                        double[] phase = zoned.phases(start + step * stepMillis);
                        if (Math.abs(sequence[step * 2] - phase[0]) > 1e-12
                                || Math.abs(sequence[step * 2 + 1] - phase[1]) > 1e-12)
                            throw new IllegalStateException("phase sequence timezone boundary mismatch");
                    }
                }
            }
        }
        long phaseStart = System.nanoTime();
        double phaseChecksum = 0;
        for (int step = 0; step < 120; step++) for (int source = 0; source < 16; source++)
            phaseChecksum += kernel.phases(monday + step * 30000)[0];
        double phaseMillis = (System.nanoTime() - phaseStart) / 1e6;
        JSONArray builds = new JSONArray();
        for (int trial = 0; trial < 5; trial++) {
            long start = System.nanoTime();
            kernel.nativeCdf(monday, 30000, 120);
            builds.put((System.nanoTime() - start) / 1e6);
        }
        long buildStart = System.nanoTime();
        double[] cdf = kernel.nativeCdf(monday, 30000, 120);
        double buildMillis = (System.nanoTime() - buildStart) / 1e6;
        double[] reference = kernel.cdf(monday, 30000, 120);
        double maximumError = 0;
        for (int index = 0; index < reference.length; index++) maximumError = Math.max(maximumError, Math.abs(reference[index] - cdf[index]));
        if (maximumError > 1e-12) throw new IllegalStateException("native periodic CDF parity mismatch");
        double[] empty = NativeCore.periodicCdf(new double[]{1}, new double[]{0, 0}, new double[0], new int[0], new int[0], 1);
        if (empty[0] != 1) throw new IllegalStateException("empty native kernel mismatch");
        boolean invalidRejected = false;
        try { NativeCore.periodicCdf(new double[]{1}, new double[]{Double.NaN, 0}, new double[0], new int[0], new int[0], 1); }
        catch (IllegalArgumentException expected) { invalidRejected = true; }
        if (!invalidRejected) throw new IllegalStateException("invalid native phase accepted");
        double[] draws = new double[300 * 119];
        Random random = new Random(42);
        for (int index = 0; index < draws.length; index++) draws[index] = random.nextDouble();
        long rolloutStart = System.nanoTime();
        long[] occupancy = NativeCore.rollouts(cdf, 16, 120, 300, 0, draws);
        double rolloutMillis = (System.nanoTime() - rolloutStart) / 1e6;
        for (int step = 0; step < 120; step++) {
            long mass = 0;
            for (int state = 0; state < 16; state++) mass += occupancy[step * 16 + state];
            if (mass != 300) throw new IllegalStateException("conditioned rollout mass mismatch");
        }
        return new JSONObject().put("passed", true).put("eligible_pairs", kernel.eligiblePairs)
            .put("monday_target_1_probability", mondayProbability).put("saturday_target_1_probability", saturdayProbability)
            .put("cdf_backend", "cpp_exact_von_mises").put("maximum_cdf_error", maximumError).put("native_rollout_ms", rolloutMillis)
            .put("cdf_build_ms", buildMillis).put("cdf_build_trials_ms", builds)
            .put("phase_only_1920_calls_ms", phaseMillis).put("phase_checksum", phaseChecksum).put("states", 16).put("rollouts", 300).put("horizon", 120)
            .put("scope", "synthetic_daily_weekly_kernel_to_native_rollouts_not_live_forecast_validation");
    }
}
