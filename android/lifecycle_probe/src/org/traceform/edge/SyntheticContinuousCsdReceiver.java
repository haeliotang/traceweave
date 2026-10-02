package org.traceform.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import org.json.JSONObject;

/** Controlled detector check only; no service, sensors or actuators started. */
public final class SyntheticContinuousCsdReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context receiverContext, Intent intent) {
        Context context = receiverContext.getApplicationContext();
        String nonce = intent.getStringExtra("nonce");
        java.util.UUID.fromString(nonce);
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                JSONObject report;
                long started = android.os.SystemClock.elapsedRealtimeNanos();
                try {
                    CriticalSlowingDetector detector = new CriticalSlowingDetector();
                    CriticalSlowingDetector legacy = new CriticalSlowingDetector();
                    int warnings = 0;
                    for (int index = 0; index < 450; index++) {
                        double value = 0.5 + (index < 100 ? 0.001 * Math.sin(index * 2.1) : 0.3 * Math.sin((index - 100) / 4.0));
                        double[] vector = new double[128]; java.util.Arrays.fill(vector, value);
                        CriticalSlowingDetector.Result signal = detector.updateContinuous("M01", vector, index > 0);
                        if (signal.tipping && signal.dwellSteps >= 60) warnings++;
                        if (legacy.update("M01", 1, 1, index > 0).tipping)
                            throw new IllegalStateException("constant grid control warned");
                    }
                    if (warnings == 0) throw new IllegalStateException("within-token variation lost");
                    CriticalSlowingDetector.Result changed = detector.updateContinuous("M01", new double[]{0.5, 0.5}, true);
                    if (changed.ready || changed.dwellSteps != 1) throw new IllegalStateException("dimension warmup failed");
                    detector.reset();
                    for (int index = 0; index < 350; index++) {
                        double[] vector = new double[128]; java.util.Arrays.fill(vector, 0.5);
                        if (detector.updateContinuous("M01", vector, index > 0).tipping)
                            throw new IllegalStateException("flat continuous control warned");
                    }
                    boolean rejected = false;
                    try { detector.updateContinuous("M01", new double[]{Double.NaN}, true); }
                    catch (IllegalArgumentException expected) { rejected = true; }
                    if (!rejected) throw new IllegalStateException("invalid input accepted");
                    report = new JSONObject().put("passed", true).put("within_token_warnings", warnings)
                        .put("continuous_frames", 800).put("input_dimension", 128)
                        .put("constant_grid_and_continuous_controls_passed", true)
                        .put("dimension_reset_and_invalid_input_passed", true);
                } catch (Exception error) {
                    report = new JSONObject().put("passed", false).put("error", error.toString());
                }
                report.put("nonce", nonce).put("fixture_elapsed_ms", (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1e6)
                    .put("collection_started", false).put("actuator_calls", 0)
                    .put("scope", "isolated_synthetic_Android_continuous_CSD_detector_not_service_latency_biological_CSD_or_intervention_effect");
                NativeVerificationJob.write(new File(context.getFilesDir(), "continuous_csd.json"), report);
            } catch (Exception error) { android.util.Log.e("TraceformProbe", "continuous CSD receipt failed", error); }
            finally { pending.finish(); }
        }, "synthetic-continuous-csd").start();
    }
}
