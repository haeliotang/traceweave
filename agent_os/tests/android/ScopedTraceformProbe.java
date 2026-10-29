package org.traceform.agentos;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Isolated synthetic Edge service probe. No production data or components are used. */
public final class ScopedTraceformProbe extends Activity {
    private static final String PERMISSION = "org.traceform.edge.validation.READ_HISTORY_SUMMARY";
    private static final String DESCRIPTOR = "org.traceform.edge.HistorySummary";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                Intent target = new Intent().setComponent(new ComponentName("org.traceform.edge.validation", "org.traceform.edge.HistorySummaryService"));
                if ("org.traceform.agentos.scopeduntrusted".equals(getPackageName())) {
                    if (checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_DENIED)
                        throw new IllegalStateException("unknown signer received summary permission");
                    try {
                        ServiceConnection denied = new ServiceConnection() {
                            @Override public void onServiceConnected(ComponentName name, IBinder binder) {}
                            @Override public void onServiceDisconnected(ComponentName name) {}
                        };
                        if (bindService(target, denied, BIND_AUTO_CREATE)) {
                            unbindService(denied);
                            throw new IllegalStateException("unknown signer bound summary service");
                        }
                    } catch (SecurityException expected) { /* Android rejected the unauthorized binding. */ }
                    android.util.Log.i("AgentOsScopedProbe", "PASS unknown_signer_denied");
                    return;
                }
                if (checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
                    throw new IllegalStateException("validation signer was not granted the narrow permission");
                CountDownLatch connected = new CountDownLatch(1);
                IBinder[] binder = new IBinder[1];
                ServiceConnection connection = new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder service) { binder[0] = service; connected.countDown(); }
                    @Override public void onServiceDisconnected(ComponentName name) { binder[0] = null; }
                };
                if (!bindService(target, connection, BIND_AUTO_CREATE)) throw new IllegalStateException("isolated service unavailable");
                try {
                    if (!connected.await(10, TimeUnit.SECONDS) || binder[0] == null) throw new IllegalStateException("isolated service did not connect");
                    boolean allowed = "org.traceform.agentos.actionsvalidation".equals(getPackageName());
                    if (!allowed) {
                        if (!rejected(binder[0], DESCRIPTOR, false)) throw new IllegalStateException("wrong package accessed summary");
                    } else {
                        if (!rejected(binder[0], "wrong.interface", false) || !rejected(binder[0], DESCRIPTOR, true))
                            throw new IllegalStateException("malformed request was accepted");
                        Parcel input = Parcel.obtain(), output = Parcel.obtain();
                        try {
                            input.writeInterfaceToken(DESCRIPTOR);
                            if (!binder[0].transact(IBinder.FIRST_CALL_TRANSACTION, input, output, 0)) throw new IllegalStateException("summary transaction missing");
                            output.readException();
                            JSONObject summary = new JSONObject(output.readString());
                            if ("AVAILABLE_HISTORY".equals(getIntent().getAction())) {
                                TraceformProvider.validateScopedSummary(summary.toString());
                                if (!summary.getBoolean("available") || summary.getInt("history_records") != 6
                                        || summary.getInt("eligible_pairs") != 5
                                        || summary.getJSONArray("sampled_occupancy_probabilities").length() != 4
                                        || summary.getBoolean("eligible_for_intervention"))
                                    throw new IllegalStateException("synthetic history summary was not available or bounded");
                            } else {
                                String reason = "EMPTY_DB".equals(getIntent().getAction()) ? "no_model_bound_history" : "no_retained_history";
                                if (summary.optBoolean("available") || !reason.equals(summary.optString("reason")))
                                    throw new IllegalStateException("empty history did not return the expected reason");
                            }
                            if (summary.has("tokens") || summary.has("occupancy_probabilities") || summary.has("source_sha256"))
                                throw new IllegalStateException("isolated response exceeded its summary scope");
                        } finally { input.recycle(); output.recycle(); }
                    }
                    android.util.Log.i("AgentOsScopedProbe", "PASS " + (allowed
                        ? ("AVAILABLE_HISTORY".equals(getIntent().getAction()) ? "authorized_synthetic_history" : "authorized_no_history")
                        : "wrong_package_denied"));
                } finally { unbindService(connection); }
            } catch (Exception error) {
                android.util.Log.e("AgentOsScopedProbe", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }, "scoped-summary-probe").start();
    }

    private static boolean rejected(IBinder binder, String descriptor, boolean extra) throws Exception {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken(descriptor);
            if (extra) input.writeInt(42);
            try { binder.transact(IBinder.FIRST_CALL_TRANSACTION, input, output, 0); output.readException(); return false; }
            catch (SecurityException expected) { return true; }
        } finally { input.recycle(); output.recycle(); }
    }
}
