package org.traceform.edge;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

final class HistoryForecast {
    static final class ParsedRow {
        final byte[] payloadDigest;
        final long epochMillis;
        final int token;
        final String segment;
        final boolean continuous;
        ParsedRow(byte[] payloadDigest, long epochMillis, int token, String segment, boolean continuous) {
            this.payloadDigest = payloadDigest; this.epochMillis = epochMillis; this.token = token;
            this.segment = segment; this.continuous = continuous;
        }
    }
    static void validateRequest(int states, String timezone, long epochMillis,
            long stepMillis, int horizon, int runs) {
        if (stepMillis <= 0 || horizon < 1 || horizon > 2048 || runs < 1 || runs > 10000
                || (long) runs * (horizon - 1) > 1000000
                || (long) horizon * states * states > 1000000)
            throw new IllegalArgumentException("forecast exceeds bounded SDK workload");
        java.time.ZoneId.of(timezone);
        Math.subtractExact(epochMillis, 30L * 86400000);
        Math.addExact(epochMillis, Math.multiplyExact((long) horizon - 1, stepMillis));
    }

    static JSONObject run(SQLiteDatabase database, String[] vocabulary, String modelVersion,
            String modelDigest, String normalizerDigest, String timezone, long epochMillis,
            long stepMillis, int horizon, int runs, long seed) throws Exception {
        return run(database, vocabulary, modelVersion, modelDigest, normalizerDigest, timezone,
            epochMillis, stepMillis, horizon, runs, seed, null);
    }
    static JSONObject run(SQLiteDatabase database, String[] vocabulary, String modelVersion,
            String modelDigest, String normalizerDigest, String timezone, long epochMillis,
            long stepMillis, int horizon, int runs, long seed, SpectrumForecastCache cache) throws Exception {
        return run(database, vocabulary, modelVersion, modelDigest, normalizerDigest, timezone,
            epochMillis, stepMillis, horizon, runs, seed, cache, 0, null);
    }
    static JSONObject run(SQLiteDatabase database, String[] vocabulary, String modelVersion,
            String modelDigest, String normalizerDigest, String timezone, long epochMillis,
            long stepMillis, int horizon, int runs, long seed, SpectrumForecastCache cache,
            int maxDwell, boolean[] risk) throws Exception {
        if (risk != null && (maxDwell < 1 || risk.length != vocabulary.length))
            throw new IllegalArgumentException("matching bounded clamp comparison required");
        validateRequest(vocabulary.length, timezone, epochMillis, stepMillis, horizon, runs);
        if (cache != null) cache.ensureOpen();
        long historyStart = System.nanoTime();
        ArrayList<Long> epochs = new ArrayList<>();
        ArrayList<Integer> tokens = new ArrayList<>();
        ArrayList<String> segments = new ArrayList<>();
        ArrayList<Boolean> continuity = new ArrayList<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        MessageDigest cacheDigest = MessageDigest.getInstance("SHA-256");
        MessageDigest rowDigest = cache == null ? null : MessageDigest.getInstance("SHA-256");
        String parsedBinding = new JSONArray().put(modelVersion).put(modelDigest)
            .put(normalizerDigest).put(new JSONArray(vocabulary)).toString();
        Map<Long, ParsedRow> previousRows = cache == null ? null : cache.parsedRows(parsedBinding);
        Map<Long, ParsedRow> nextRows = cache == null ? null : new HashMap<>();
        Map<String, String> segmentIdentities = new HashMap<>();
        int reusedRows = 0, parsedRows = 0;
        int excluded = 0;
        boolean barrier = true;
        long oldest = Math.subtractExact(epochMillis, 30L * 86400000);
        try (Cursor rows = database.rawQuery("SELECT ts,payload,bucket FROM warm_trajectory WHERE ts>=? AND ts<=? ORDER BY ts,bucket",
                new String[]{Long.toString(oldest), Long.toString(epochMillis)})) {
            while (rows.moveToNext()) {
                String raw = rows.getString(1);
                byte[] allBytes = raw.getBytes(StandardCharsets.UTF_8);
                cacheDigest.update(ByteBuffer.allocate(4).putInt(allBytes.length).array()); cacheDigest.update(allBytes);
                byte[] payloadDigest = rowDigest == null ? null : rowDigest.digest(allBytes);
                long bucket = rows.getLong(2);
                ParsedRow parsed = previousRows == null ? null : previousRows.get(bucket);
                if (parsed != null && Arrays.equals(parsed.payloadDigest, payloadDigest)) {
                    reusedRows++;
                } else {
                    JSONObject frame = new JSONObject(raw);
                    boolean bound = modelVersion.equals(frame.optString("model_version"))
                        && modelDigest.equals(frame.optString("model_sha256"))
                        && normalizerDigest.equals(frame.optString("normalizer_sha256"));
                    for (String field : new String[]{"session_id", "source_mode"})
                        bound &= frame.opt(field) instanceof String && !frame.getString(field).isEmpty();
                    int token = Arrays.asList(vocabulary).indexOf(frame.optString("state_token"));
                    String segment = null;
                    if (bound && token >= 0) segment = new JSONArray().put(modelVersion).put(modelDigest).put(normalizerDigest)
                        .put(frame.getString("source_mode")).put(frame.getString("session_id")).toString();
                    parsed = new ParsedRow(payloadDigest, frame.getLong("timestamp_epoch_ms"),
                        segment == null ? -1 : token, segment, frame.optBoolean("continuous_from_previous", false));
                    parsedRows++;
                }
                if (parsed.epochMillis != rows.getLong(0))
                    throw new IllegalArgumentException("history timestamp binding mismatch");
                if (parsed.segment != null) {
                    String identity = segmentIdentities.putIfAbsent(parsed.segment, parsed.segment);
                    if (identity != null && identity != parsed.segment)
                        parsed = new ParsedRow(parsed.payloadDigest, parsed.epochMillis, parsed.token,
                            identity, parsed.continuous);
                }
                if (nextRows != null) nextRows.put(bucket, parsed);
                if (parsed.token < 0) { excluded++; barrier = true; continue; }
                epochs.add(rows.getLong(0)); tokens.add(parsed.token); segments.add(parsed.segment);
                continuity.add(!barrier && parsed.continuous);
                barrier = false;
                digest.update(ByteBuffer.allocate(4).putInt(allBytes.length).array()); digest.update(allBytes);
            }
        }
        if (cache != null) cache.rememberParsedRows(parsedBinding, nextRows);
        String sourceDigest = HexDigest.encode(digest.digest());
        double historyMillis = (System.nanoTime() - historyStart) / 1e6;
        JSONObject report = new JSONObject().put("model_version", modelVersion).put("model_sha256", modelDigest)
            .put("normalizer_sha256", normalizerDigest).put("timezone", timezone).put("as_of_epoch_ms", epochMillis)
            .put("history_records", epochs.size()).put("excluded_history_records", excluded)
            .put("history_scan_ms", historyMillis)
            .put("history_parsed_rows", parsedRows).put("history_reused_rows", reusedRows)
            .put("source_sha256", sourceDigest.toString()).put("eligible_for_intervention", false)
            .put("evidence_scope", "retained_native_warm_history_conditioned_rollouts_not_validated_prediction_or_causal_effect");
        if (epochs.isEmpty()) return report.put("available", false).put("reason", "no_model_bound_history");
        long keyStart = System.nanoTime();
        String cacheKey = null;
        if (cache != null) {
            String windowDigest = HexDigest.encode(cacheDigest.digest());
            cacheKey = new JSONArray().put(windowDigest).put(modelVersion).put(modelDigest)
                .put(normalizerDigest).put(timezone).put(new JSONArray(vocabulary)).toString();
        }
        report.put("history_key_ms", (System.nanoTime() - keyStart) / 1e6);
        long kernelStart = System.nanoTime();
        long[] times = new long[epochs.size()];
        int[] states = new int[times.length];
        boolean[] continuous = new boolean[times.length];
        for (int index = 0; index < times.length; index++) {
            times[index] = epochs.get(index); states[index] = tokens.get(index); continuous[index] = continuity.get(index);
        }
        PeriodicTransitionKernel kernel = cache == null ? null : cache.kernel(cacheKey);
        boolean kernelHit = kernel != null;
        if (!kernelHit) {
            kernel = new PeriodicTransitionKernel(timezone, vocabulary.length,
                times, states, segments.toArray(new String[0]), continuous, 300000);
            if (cache != null) cache.rememberKernel(cacheKey, kernel);
        }
        report.put("kernel_cache_hit", kernelHit);
        report.put("kernel_build_ms", (System.nanoTime() - kernelStart) / 1e6);
        report.put("eligible_pairs", kernel.eligiblePairs).put("history_start_epoch_ms", times[0])
            .put("history_end_epoch_ms", times[times.length - 1])
            .put("history_age_ms", epochMillis - times[times.length - 1]);
        if (kernel.eligiblePairs == 0) return report.put("available", false).put("reason", "no_continuous_transition_pairs");
        if (epochMillis - times[times.length - 1] > 300000)
            return report.put("available", false).put("reason", "stale_initial_state");
        long start = System.nanoTime();
        double[] draws = NativeCore.randomDraws(seed, runs * (horizon - 1));
        double[] scenarioDraws = risk == null ? null : NativeCore.randomDraws(seed + 1, draws.length);
        report.put("draw_preparation_ms", (System.nanoTime() - start) / 1e6);
        int initial = states[states.length - 1];
        start = System.nanoTime();
        long[] occupancy;
        if (risk != null) {
            double[] cdf;
            if(cache!=null && vocabulary.length<=16 && kernel.eligiblePairs>=2048) {
                SpectrumForecastCache.CdfQuery query=cache.queryCdf(cacheKey,kernel,epochMillis,stepMillis,horizon);
                cdf=query.cdf;
                report.put("cdf_backend","cpp_order18_fitted_spectrum").put("spectrum_cache_hit",query.hit)
                    .put("spectrum_fit_ms",query.fitMillis).put("spectrum_phase_ms",query.phaseMillis)
                    .put("spectrum_query_ms",query.queryMillis).put("spectrum_query_scope","CDF_only_including_JNI_copies")
                    .put("rollout_backend","cpp_spectrum_cdf_counterfactual");
            } else {
                cdf=kernel.nativeCdf(epochMillis,stepMillis,horizon);
                report.put("cdf_backend","cpp_exact_von_mises").put("spectrum_cache_hit",false)
                    .put("rollout_backend","cpp_exact_cdf_counterfactual");
            }
            double cdfMillis = (System.nanoTime() - start) / 1e6;
            long rolloutStart = System.nanoTime();
            long[] branches = NativeCore.counterfactual(cdf, kernel.priorArray(), vocabulary.length,
                horizon, runs, initial, maxDwell, draws, scenarioDraws);
            report.put("native_rollout_ms", (System.nanoTime() - rolloutStart) / 1e6);
            report.put("counterfactual_core_ms",(System.nanoTime()-start)/1e6)
                .put("counterfactual_core_scope","fit_phase_CDF_two_rollouts_excluding_metrics_JSON_history_kernel_draws");
            CounterfactualDecision.Result decision = CounterfactualDecision.compare(branches, vocabulary.length, horizon, runs, risk);
            occupancy = Arrays.copyOf(branches, horizon * vocabulary.length);
            JSONArray scenario = new JSONArray(), selected = new JSONArray();
            for (int state = 0; state < vocabulary.length; state++) if (risk[state]) selected.put(vocabulary[state]);
            for (int step = 0; step < horizon; step++) {
                JSONArray row = new JSONArray();
                for (int state = 0; state < vocabulary.length; state++)
                    row.put(branches[occupancy.length + step * vocabulary.length + state] / (double) runs);
                scenario.put(row);
            }
            report.put("cdf_build_ms", cdfMillis)
                .put("counterfactual", new JSONObject().put("action_type", "CLAMP_DWELL").put("max_dwell_steps", maxDwell)
                    .put("selected_risk_states", selected).put("scenario_occupancy_probabilities", scenario)
                    .put("scenario_seed", seed + 1).put("mean_jsd", decision.meanJsd).put("peak_jsd", decision.peakJsd)
                    .put("peak_step", decision.peakStep).put("terminal_risk_change", decision.terminalRiskChange)
                    .put("recommendation", decision.recommendation)
                    .put("evidence_scope", "modeled_forced_escape_not_physical_actuator_effect_or_execution_authority"));
        } else if (cache != null && vocabulary.length <= 16 && kernel.eligiblePairs >= 2048) {
            report.put("spectrum_key_ms", report.getDouble("history_key_ms"));
            SpectrumForecastCache.Query query = cache.query(cacheKey, kernel, epochMillis, stepMillis,
                horizon, runs, initial, draws);
            occupancy = query.occupancy;
            report.put("cdf_backend", "cpp_order18_fitted_spectrum").put("spectrum_cache_hit", query.hit)
                .put("spectrum_fit_ms", query.fitMillis).put("spectrum_phase_ms", query.phaseMillis)
                .put("spectrum_query_ms", query.queryMillis).put("rollout_backend", "cpp_fused_spectrum_rollouts")
                .put("spectrum_query_scope", "CDF_and_rollouts_including_JNI_copies")
                .put("cdf_build_ms", JSONObject.NULL).put("native_rollout_ms", JSONObject.NULL);
        } else {
            double[] cdf = kernel.nativeCdf(epochMillis, stepMillis, horizon);
            double cdfMillis = (System.nanoTime() - start) / 1e6;
            long rolloutStart = System.nanoTime();
            occupancy = NativeCore.rollouts(cdf, vocabulary.length, horizon, runs, initial, draws);
            report.put("cdf_backend", "cpp_exact_von_mises").put("spectrum_cache_hit", false)
                .put("rollout_backend", "cpp_separate_cdf_rollouts").put("cdf_build_ms", cdfMillis)
                .put("native_rollout_ms", (System.nanoTime() - rolloutStart) / 1e6);
        }
        report.put("forecast_compute_ms", (System.nanoTime() - start) / 1e6)
            .put("forecast_compute_scope", risk == null
                ? "fit_phase_CDF_rollouts_excluding_history_key_kernel_and_draw_preparation"
                : "fit_phase_CDF_two_rollouts_metrics_scenario_JSON_excluding_history_key_kernel_and_draw_preparation");
        JSONArray probabilities = new JSONArray();
        for (int step = 0; step < horizon; step++) {
            JSONArray row = new JSONArray();
            long total = 0;
            for (int state = 0; state < vocabulary.length; state++) {
                long count = occupancy[step * vocabulary.length + state];
                total += count; row.put(count / (double) runs);
            }
            if (total != runs) throw new IllegalStateException("forecast mass mismatch");
            probabilities.put(row);
        }
        return report.put("available", true).put("initial_state", vocabulary[initial])
            .put("tokens", new JSONArray(vocabulary)).put("occupancy_probabilities", probabilities)
            .put("step_ms", stepMillis).put("horizon_steps", horizon).put("rollouts", runs).put("seed", seed);
    }
}
