package org.traceform.edge;

import org.json.JSONObject;

final class CounterfactualFixture {
    static JSONObject verify() throws Exception {
        int states = 2, horizon = 12, runs = 300;
        double[] prior = {1, 0, 0, 1}, cdf = new double[horizon * states * states];
        for (int step = 0; step < horizon; step++) {
            cdf[step * 4] = cdf[step * 4 + 1] = cdf[step * 4 + 3] = 1;
        }
        double[] baseline = NativeCore.randomDraws(42, runs * (horizon - 1));
        double[] scenario = NativeCore.randomDraws(43, baseline.length);
        long start = System.nanoTime();
        long[] counts = NativeCore.counterfactual(cdf, prior, states, horizon, runs, 0, 3, baseline, scenario);
        double elapsed = (System.nanoTime() - start) / 1e6;
        long[] reference = NativeCore.rollouts(cdf, states, horizon, runs, 0, baseline);
        for (int step = 0; step < horizon; step++) {
            int forced = (step / 3) % 2;
            for (int state = 0; state < states; state++) {
                if (counts[step * states + state] != reference[step * states + state]
                        || counts[horizon * states + step * states + state] != (state == forced ? runs : 0))
                    throw new IllegalStateException("counterfactual occupancy mismatch");
            }
        }
        CounterfactualDecision.Result decision = CounterfactualDecision.compare(counts, states, horizon, runs, new boolean[]{true, false});
        if (!decision.recommendation.equals("RECOMMEND_INTERVENTION") || decision.terminalRiskChange != -1)
            throw new IllegalStateException("counterfactual recommendation mismatch");
        CounterfactualDecision.Result boundary = CounterfactualDecision.compare(new long[]{100,0,95,5}, 2,1,100,new boolean[]{true,false});
        if (boundary.recommendation.equals("RECOMMEND_INTERVENTION"))
            throw new IllegalStateException("exact five percent recommended");
        long[] one = NativeCore.counterfactual(new double[]{1,1,0,1}, prior, 2,1,300,0,3,new double[0],new double[0]);
        if (!CounterfactualDecision.compare(one,2,1,300,new boolean[]{true,false}).recommendation.equals("NEUTRAL"))
            throw new IllegalStateException("single-step counterfactual mismatch");
        boolean rejected = false;
        scenario[0] = Double.NaN;
        try { NativeCore.counterfactual(cdf, prior, states, horizon, runs, 0,3,baseline,scenario); }
        catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new IllegalStateException("invalid scenario draw accepted");
        return new JSONObject().put("passed", true).put("runs_per_branch", runs).put("horizon_steps", horizon)
            .put("native_compute_ms", elapsed).put("baseline_parity", true).put("clamp_fallback_verified", true)
            .put("single_step_and_invalid_draw_verified", true).put("exact_five_percent_not_recommended", true)
            .put("recommendation", decision.recommendation).put("terminal_risk_change", decision.terminalRiskChange)
            .put("mean_jsd", decision.meanJsd).put("collection_started", false).put("actuator_calls", 0)
            .put("scope", "synthetic_forced_escape_scenario_not_physical_actuator_effect_or_live_policy");
    }
}
