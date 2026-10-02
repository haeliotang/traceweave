package org.traceform.edge;

import org.json.JSONArray;
import org.json.JSONObject;

final class SpectrumBackendFixture {
    static JSONObject verify() throws Exception {
        JSONArray workloads = new JSONArray();
        for (int pairs : new int[]{3, 78, 256, 512, 1024, 2048, 4096}) {
            double[] prior = new double[256], observed = new double[pairs * 2], queries = new double[240];
            int[] sources = new int[pairs], targets = new int[pairs];
            java.util.Arrays.fill(prior, 1.0 / 16);
            for (int pair = 0; pair < pairs; pair++) {
                int seconds = pair * 30; double fraction = (seconds % 86400) / 86400.0;
                observed[pair * 2] = 2 * Math.PI * fraction;
                observed[pair * 2 + 1] = 2 * Math.PI * ((seconds / 86400) % 7 + fraction) / 7;
                sources[pair] = pair % 16; targets[pair] = (pair + pair / 16 + 1) % 16;
            }
            for (int step = 0; step < 120; step++) {
                double fraction = 0.25 + step * 30.0 / 86400;
                queries[step * 2] = 2 * Math.PI * fraction; queries[step * 2 + 1] = 2 * Math.PI * fraction / 7;
            }
            JSONArray exactTimes = new JSONArray(), spectrumTimes = new JSONArray();
            long start = System.nanoTime();
            FittedPeriodicSpectrum snapshot = new FittedPeriodicSpectrum(16, observed, sources, targets);
            double fitMillis = (System.nanoTime() - start) / 1e6, maximum = 0;
            try (FittedPeriodicSpectrum owned = snapshot) {
                for (int trial = 0; trial < 3; trial++) {
                    start = System.nanoTime();
                    double[] exact = NativeCore.periodicCdf(prior, queries, observed, sources, targets, 16);
                    exactTimes.put((System.nanoTime() - start) / 1e6);
                    start = System.nanoTime(); double[] fitted = owned.cdf(prior, queries);
                    spectrumTimes.put((System.nanoTime() - start) / 1e6);
                    for (int index = 0; index < exact.length; index++) maximum = Math.max(maximum, Math.abs(exact[index] - fitted[index]));
                }
            }
            if (maximum > 1e-12) throw new IllegalStateException("backend comparison exceeded probability tolerance");
            workloads.put(new JSONObject().put("pairs", pairs).put("fit_ms", fitMillis).put("exact_ms", exactTimes)
                .put("fitted_query_ms", spectrumTimes).put("maximum_cdf_error", maximum));
        }
        return new JSONObject().put("passed", true).put("workloads", workloads).put("states", 16).put("horizon", 120)
            .put("scope", "synthetic_APK_JNI_backend_comparison_precomputed_phases_not_history_scan_or_full_pipeline");
    }
}
