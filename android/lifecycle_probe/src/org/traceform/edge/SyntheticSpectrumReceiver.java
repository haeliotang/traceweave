package com.inceptless.traceweave.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Isolated monthly SDK performance fixture; no service or sensor collection. */
public final class SyntheticSpectrumReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context receiverContext, Intent intent) {
        Context context = receiverContext.getApplicationContext();
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                JSONObject report;
                try { report = SpectrumSdkFixture.verify(); }
                catch (Exception error) { report = new JSONObject().put("passed", false).put("error", error.toString()); }
                NativeVerificationJob.write(new java.io.File(context.getFilesDir(), "spectrum.json"),
                    report.put("completed_epoch_ms", System.currentTimeMillis()).put("sdk_int", android.os.Build.VERSION.SDK_INT));
            } catch (Exception error) { android.util.Log.e("TraceformProbe", "spectrum receipt failed", error); }
            finally { pending.finish(); }
        }, "synthetic-spectrum").start();
    }
}
