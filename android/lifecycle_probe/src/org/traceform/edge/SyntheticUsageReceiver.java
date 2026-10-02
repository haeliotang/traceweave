package org.traceform.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import org.json.JSONObject;

/** Permission/caching probe; exports no usage events, identities or counts. */
public final class SyntheticUsageReceiver extends BroadcastReceiver {
    private static final NativeUsageSource source = new NativeUsageSource();
    private static boolean previousFinite;
    @Override public void onReceive(Context context, Intent intent) {
        String nonce = intent.getStringExtra("nonce");
        java.util.UUID.fromString(nonce);
        try {
            boolean expectedAllowed = intent.getBooleanExtra("expected_allowed", false);
            boolean expectedPrevious = intent.getBooleanExtra("expected_previous_finite", false);
            long now = System.currentTimeMillis(), nanos = android.os.SystemClock.elapsedRealtimeNanos();
            NativeUsageSource.Counts result = source.sample(context, now, nanos);
            boolean finite = Double.isFinite(result.resumes) && Double.isFinite(result.interactiveChecks);
            if (finite != expectedAllowed || (expectedPrevious && !previousFinite))
                throw new IllegalStateException("permission or same-process cache precondition failed");
            NativeUsageSource.Counts repeated = source.sample(context, now + 1, nanos + 1000000L);
            boolean cached = !finite || repeated.timestampNanos == result.timestampNanos;
            if (!cached) throw new IllegalStateException("cache timestamp changed before expiry");
            source.reset();
            NativeUsageSource.Counts reset = source.sample(context, now + 2, nanos + 2000000L);
            if (reset.timestampNanos != nanos + 2000000L) throw new IllegalStateException("reset retained old cache");
            JSONObject report = new JSONObject().put("passed", true).put("nonce", nonce)
                .put("process_id", android.os.Process.myPid()).put("source_available", finite)
                .put("previous_result_finite", previousFinite).put("cache_and_reset_passed", true)
                .put("collection_started", false).put("actuator_calls", 0)
                .put("scope", "isolated_usage_permission_and_cache_not_behavioral_validation");
            previousFinite = finite;
            NativeVerificationJob.write(new File(context.getFilesDir(), "usage_source.json"), report);
        } catch (Exception error) {
            try { NativeVerificationJob.write(new File(context.getFilesDir(), "usage_source.json"),
                new JSONObject().put("passed", false).put("nonce", nonce).put("error", error.toString())); }
            catch (Exception ignored) { android.util.Log.e("TraceformProbe", "usage receipt failed", error); }
        }
    }
}
