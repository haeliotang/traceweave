package com.inceptless.traceweave.edge;

import android.content.Context;
import android.content.ContextWrapper;
import org.json.JSONObject;

final class BoundServiceFixture {
    static JSONObject verify(Context context) throws Exception { return verify(context, false); }
    static JSONObject verify(Context context, boolean failInitialization) throws Exception {
        java.io.File absentHistory = new java.io.File(context.getCacheDir(), "absent-history-" + java.util.UUID.randomUUID());
        java.io.File fixtureFiles = new java.io.File(context.getCacheDir(), "bound-stop-" + java.util.UUID.randomUUID());
        if (!fixtureFiles.mkdir()) throw new IllegalStateException("fixture directory creation failed");
        java.util.concurrent.atomic.AtomicBoolean databaseFailureTriggered = new java.util.concurrent.atomic.AtomicBoolean();
        ContextWrapper readOnly = new ContextWrapper(context) {
            @Override public java.io.File getFilesDir() { return fixtureFiles; }
            @Override public java.io.File getDatabasePath(String name) { return absentHistory; }
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    android.database.sqlite.SQLiteDatabase.CursorFactory factory) {
                databaseFailureTriggered.set(true);
                throw new IllegalStateException("synthetic_database_open_failure");
            }
            @Override public Object getSystemService(String name) {
                throw new IllegalStateException("bound_service_requested_capture_resource:" + name);
            }
        };
        EdgeService service = new EdgeService();
        java.lang.reflect.Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        attach.setAccessible(true); attach.invoke(service, readOnly);
        try {
            service.onCreate();
            android.os.IBinder binder = service.onBind(new android.content.Intent());
            JSONObject absent = CanonicalClient.forecast(binder, "UTC", System.currentTimeMillis(), 30000, 120, 300, 42);
            if (absent.getBoolean("available") || !absent.getString("reason").equals("no_retained_history")
                    || absent.getBoolean("service_collection_started") || absentHistory.exists())
                throw new IllegalStateException("absent history did not remain unavailable and read-only");
            android.os.HandlerThread worker = new android.os.HandlerThread("synthetic-bound-stop");
            worker.start();
            android.os.Handler workerHandler = new android.os.Handler(worker.getLooper());
            java.util.concurrent.atomic.AtomicInteger pendingSamples = new java.util.concurrent.atomic.AtomicInteger();
            workerHandler.postDelayed(() -> pendingSamples.incrementAndGet(), 60000);
            for (String name : new String[]{"thread", "handler"}) {
                java.lang.reflect.Field field = EdgeService.class.getDeclaredField(name);
                field.setAccessible(true); field.set(service, name.equals("thread") ? worker : workerHandler);
            }
            java.lang.reflect.Method stopCollection = EdgeService.class.getDeclaredMethod("stopCollection");
            stopCollection.setAccessible(true);
            if (failInitialization) {
                java.lang.reflect.Method initialize = EdgeService.class.getDeclaredMethod("initializeCollection");
                initialize.setAccessible(true);
                try { initialize.invoke(service); }
                catch (java.lang.reflect.InvocationTargetException error) {
                    // A manually attached Service has no ActivityManager for stopSelf().
                    if (!(error.getCause() instanceof NullPointerException)) throw error;
                }
            } else stopCollection.invoke(service);
            if (failInitialization && !databaseFailureTriggered.get())
                throw new IllegalStateException("database failure injection was not reached");
            worker.join(3000);
            if (worker.isAlive() || pendingSamples.get() != 0)
                throw new IllegalStateException("bound stop did not release worker and cancel samples");
            JSONObject stoppedStatus = new JSONObject(new String(java.nio.file.Files.readAllBytes(
                new java.io.File(fixtureFiles, "status.json").toPath()), "UTF-8"));
            if (stoppedStatus.getBoolean("running")) throw new IllegalStateException("bound stop status still running");
            JSONObject afterStop = CanonicalClient.forecast(binder, "UTC", System.currentTimeMillis(), 30000, 120, 300, 42);
            if (afterStop.getBoolean("service_collection_started") || absentHistory.exists())
                throw new IllegalStateException("stopped bound service acquired capture resources");
            for (String timezone : new String[]{"UTC", "invalid/traceform-zone"}) {
                boolean rejected = false;
                try { CanonicalClient.forecast(binder, timezone, System.currentTimeMillis(), 30000,
                    timezone.equals("UTC") ? 0 : 120, 300, 42); }
                catch (IllegalArgumentException expected) { rejected = true; }
                if (!rejected || absentHistory.exists()) throw new IllegalStateException("empty-history request validation failed");
            }
        }
        finally {
            service.onDestroy();
            java.io.File[] files = fixtureFiles.listFiles();
            if (files != null) for (java.io.File file : files) file.delete();
            fixtureFiles.delete();
        }
        return new JSONObject().put("passed", true).put("database_failure_injected", databaseFailureTriggered.get())
            .put("scope", failInitialization ? "android_database_open_failure_releases_synthetic_worker_no_capture_resources"
                : "android_bound_stop_synthetic_worker_cancelled_history_sdk_available_no_capture_resources");
    }
}
