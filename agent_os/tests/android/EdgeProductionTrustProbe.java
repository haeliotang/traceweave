package com.inceptless.traceweave;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Same signer but different package: production Edge must reject before reading history. */
public final class EdgeProductionTrustProbe extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            ServiceConnection connection = null;
            try {
                if (checkSelfPermission("org.traceform.edge.READ_HISTORY_SUMMARY") != PackageManager.PERMISSION_GRANTED)
                    throw new IllegalStateException("Expected known-signer permission was not granted");
                CountDownLatch connected = new CountDownLatch(1); IBinder[] remote = new IBinder[1];
                connection = new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder binder) { remote[0] = binder; connected.countDown(); }
                    @Override public void onServiceDisconnected(ComponentName name) { remote[0] = null; }
                };
                Intent intent = new Intent().setComponent(new ComponentName("org.traceform.edge", "org.traceform.edge.HistorySummaryService"));
                if (!bindService(intent, connection, BIND_AUTO_CREATE)) throw new IllegalStateException("Summary service was unavailable");
                if (!connected.await(10, TimeUnit.SECONDS) || remote[0] == null) throw new IllegalStateException("Summary service did not connect");
                if (!"org.traceform.edge.HistorySummary".equals(remote[0].getInterfaceDescriptor()))
                    throw new IllegalStateException("Unexpected service descriptor");
                Parcel input = Parcel.obtain(), output = Parcel.obtain();
                try {
                    input.writeInterfaceToken("org.traceform.edge.HistorySummary");
                    boolean rejected = false;
                    try { remote[0].transact(IBinder.FIRST_CALL_TRANSACTION, input, output, 0); output.readException(); }
                    catch (SecurityException expected) { rejected = true; }
                    if (!rejected) throw new IllegalStateException("Wrong package was not rejected");
                } finally { input.recycle(); output.recycle(); }
                Log.i("AgentOsEdgeTrust", "PASS known_signer_wrong_package_rejected_before_history");
            } catch (Exception error) {
                Log.e("AgentOsEdgeTrust", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            } finally {
                if (connection != null) try { unbindService(connection); } catch (IllegalArgumentException ignored) {}
            }
        }, "edge-trust-probe").start();
    }
}
