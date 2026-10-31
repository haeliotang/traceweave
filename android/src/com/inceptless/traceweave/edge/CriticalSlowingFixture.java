package com.inceptless.traceweave.edge;

import android.content.Context;
import org.json.JSONObject;

final class CriticalSlowingFixture {
    static JSONObject verify(Context context) throws Exception {
        CriticalSlowingDetector detector = new CriticalSlowingDetector();
        int warnings = 0, gates = 0;
        for (int index = 0; index < 700; index++) {
            int stage = index < 400 ? index : index - 400;
            double u = 1 + (stage < 140 ? 0.02 * Math.sin(stage * 2.1) : 3 * Math.sin(stage * 0.05));
            double v = 1 + (stage < 140 ? 0.02 * Math.cos(stage * 2.6) : 2 * Math.cos(stage * 0.04));
            CriticalSlowingDetector.Result signal = detector.update("M01", u, v, index > 0 && index != 400);
            if (index == 400 && (signal.ready || signal.dwellSteps != 1))
                throw new IllegalStateException("CSD gap reset failed");
            if (signal.tipping) warnings++;
            if (signal.tipping && signal.dwellSteps > 30) gates++;
        }
        if (warnings == 0 || gates == 0) throw new IllegalStateException("CSD positive synthetic case failed");
        detector.reset();
        for (int index = 0; index < 100; index++) {
            CriticalSlowingDetector.Result signal = detector.update("M01", 1 + index * 0.01, 1 + index * 0.01, index > 0);
            if (signal.tipping || signal.variance > 1e-20)
                throw new IllegalStateException("linear trend mistaken for CSD");
        }
        CriticalSlowingDetector.Result changed = detector.update("M02", 2, 2, true);
        if (changed.dwellSteps != 1) throw new IllegalStateException("CSD token dwell reset failed");
        return new JSONObject().put("passed", true).put("synthetic_frames", 801).put("warnings", warnings)
            .put("csd_and_dwell_gates", gates).put("gap_and_token_reset_verified", true)
            .put("linear_detrending_verified", true).put("model_grid_parity", new ModelEngine(context).verifyParity(context))
            .put("collection_started", false).put("actuator_calls", 0)
            .put("scope", "synthetic_Android_CSD_and_grid_mapping_not_live_collection_or_MC_actuation_policy");
    }
}
