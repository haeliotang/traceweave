package com.inceptless.traceweave.edge;

import android.content.*;
import android.os.IBinder;
import org.json.JSONObject;

public final class SyntheticBindingReceiver extends BroadcastReceiver {
    public void onReceive(Context receiverContext, Intent intent) {
        Context context = receiverContext.getApplicationContext();
        PendingResult pending = goAsync();
        new Thread(() -> {
            JSONObject receipt = new JSONObject();
            java.util.concurrent.CountDownLatch connected = new java.util.concurrent.CountDownLatch(1);
            IBinder[] remote = new IBinder[1];
            ServiceConnection connection = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder binder) { remote[0] = binder; connected.countDown(); }
                public void onServiceDisconnected(ComponentName name) {}
            };
            boolean bound = false;
            try {
                bound = context.bindService(new Intent(context, EdgeService.class), connection, Context.BIND_AUTO_CREATE);
                if (!bound || !connected.await(8, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("binding timed out");
                ModelEngine model = new ModelEngine(context);
                JSONObject canonical = CanonicalClient.process(remote[0], model.medians.clone(), new double[128]);
                JSONObject forecast = CanonicalClient.forecast(remote[0], "UTC", System.currentTimeMillis(), 30000, 120, 300, 42);
                if (forecast.getBoolean("service_collection_started") || !forecast.getString("reason").equals("no_retained_history"))
                    throw new IllegalStateException("unexpected collection/history");
                if (forecast.getInt("service_process_id") == android.os.Process.myPid()
                        || context.getDatabasePath("edge.sqlite").exists())
                    throw new IllegalStateException("process isolation/history absence failed");
                receipt.put("canonical", canonical).put("forecast", forecast);
                context.unbindService(connection); bound = false;
                boolean destroyed = false;
                for (int n = 0; n < 50; n++) {
                    try { CanonicalClient.nightDisplayCapabilities(remote[0]); }
                    catch (IllegalArgumentException expected) { destroyed = true; break; }
                    Thread.sleep(50);
                }
                if (!destroyed) throw new IllegalStateException("destruction not observed");
                boolean rejected = false;
                try { CanonicalClient.process(remote[0], model.medians.clone(), new double[128]); }
                catch (IllegalArgumentException expected) { rejected = true; }
                if (!rejected) throw new IllegalStateException("destroyed service accepted canonical input");
                receipt.put("passed", true).put("destroyed_input_rejected", true)
                    .put("probe_process_id", android.os.Process.myPid())
                    .put("sdk_int", android.os.Build.VERSION.SDK_INT)
                    .put("completed_epoch_ms", System.currentTimeMillis())
                    .put("scope", "actual_android_service_binding_synthetic_vectors_no_collection");
            } catch (Throwable error) {
                try { receipt.put("passed", false).put("error", error.toString()); } catch (Exception ignored) {}
            } finally {
                if (bound) context.unbindService(connection);
                try { java.nio.file.Files.write(new java.io.File(context.getFilesDir(), "binding.json").toPath(), receipt.toString().getBytes("UTF-8")); }
                catch (Exception error) { android.util.Log.e("TraceformProbe", "receipt failed", error); }
                pending.finish();
            }
        }, "synthetic-binding").start();
    }
}
