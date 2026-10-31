package com.inceptless.traceweave.edge;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;

/** Weekly isolated calibration followed by database and registry admission. */
public final class WeeklyMaintenanceJob extends JobService {
    static final int JOB_ID = 34716;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Task current;
    private static final class Task { boolean stopped, retry; Thread worker; }

    static void schedule(Context context) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject receipt = receipt(context);
        WeeklyMaintenanceClock.Window window = WeeklyMaintenanceClock.next(now, ZoneId.systemDefault(), receipt.optString("completed_window", null));
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo pending = scheduler.getPendingJob(JOB_ID);
        String zone = ZoneId.systemDefault().getId();
        if (pending != null && window.key.equals(pending.getExtras().getString("window"))
                && zone.equals(pending.getExtras().getString("zone"))) return;
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putString("window", window.key); extras.putString("zone", zone);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, WeeklyMaintenanceJob.class))
            .setMinimumLatency(Math.max(0, window.startMillis - now)).setPersisted(true).setExtras(extras).build();
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("weekly maintenance scheduling failed");
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        Task task = new Task(); current = task;
        task.worker = new Thread(() -> {
            try (AlignmentQueryLease lease = AlignmentQueryLease.acquireWeeklyMaintenance(getFilesDir())) {
                long now = System.currentTimeMillis();
                WeeklyMaintenanceClock.Window window = WeeklyMaintenanceClock.next(now, ZoneId.systemDefault(), receipt(this).optString("completed_window", null));
                if (window.contains(now)) {
                    JSONObject report;
                    try { report = calibrate(now,window.key); }
                    catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return; }
                    catch (SecurityException | android.database.sqlite.SQLiteDatabaseLockedException busy) {
                        task.retry = true;
                        android.util.Log.e("Traceform", "weekly calibration access unavailable; retrying", busy);
                        return;
                    }
                    catch (Exception error) { report = new JSONObject().put("accepted", false).put("reason", error.toString()); }
                    synchronized (task) {
                        if (task.stopped || Thread.currentThread().isInterrupted()) return;
                        report.put("completed_window", window.key).put("timezone", ZoneId.systemDefault().getId())
                            .put("completed_epoch_ms", System.currentTimeMillis());
                        if (!report.has("candidate_activated")) report.put("candidate_activated", false);
                        NativeVerificationJob.write(new File(getFilesDir(), "weekly_maintenance.json"), report);
                    }
                }
            } catch (Exception error) { task.retry = true; android.util.Log.e("Traceform", "weekly maintenance failed", error); }
            finally {
                handler.post(() -> {
                    if (current != task) return;
                    current = null; jobFinished(parameters, task.retry);
                    if (task.retry) return;
                    try { schedule(this); }
                    catch (Exception error) { android.util.Log.e("Traceform", "weekly rescheduling failed", error); }
                });
            }
        }, "traceform-weekly-maintenance");
        task.worker.start(); return true;
    }

    private JSONObject calibrate(long asOf,String window) throws Exception {
        File sourcePath = getDatabasePath("edge.sqlite");
        if (!sourcePath.exists()) return new JSONObject().put("accepted", false).put("reason", "no_retained_history");
        NativeModelRegistry registry = new NativeModelRegistry(new File(getFilesDir(), "model_registry"), new ModelEngine(this));
        JSONObject published = registry.completedWeeklyActivation(window);
        if (published != null) return published;
        ModelEngine model = registry.active();
        JSONObject report;
        try (SQLiteDatabase source = SQLiteDatabase.openDatabase(sourcePath.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                WeeklySnapshot snapshot = WeeklySnapshot.copy(source, getCacheDir(), asOf)) {
            if (!snapshot.warmIncluded) return new JSONObject().put("accepted", false).put("reason", "warm_source_table_missing");
            JSONObject coldAudit = NativeColdAudit.run(snapshot.database);
            JSONObject warmAudit = NativeWarmAudit.run(snapshot.database);
            if (!warmAudit.getBoolean("valid")) return new JSONObject().put("accepted", false).put("reason", "invalid_retained_warm_source")
                .put("warm_audit", warmAudit);
            report = NativeCandidateCalibration.run(snapshot.database, model, getCacheDir());
            report.put("warm_audit", warmAudit).put("cold_audit", coldAudit);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("weekly calibration cancelled");
        if (report.getBoolean("accepted")) {
            String digest = NativeCandidateStore.save(new File(getFilesDir(), "model_candidates"), model, report);
            report.put("candidate_bundle_sha256", digest);
            report.remove("candidate_centers"); report.remove("candidate_velocity"); report.remove("candidate_grid");
            report.remove("potential");
            try (SQLiteDatabase history = SQLiteDatabase.openDatabase(sourcePath.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                ColdLineage.migrateOnce(history);
                String activated = registry.activateWeeklyWithHistory(new File(getFilesDir(), "model_candidates"), digest, model.modelVersion, history,window);
                report.put("candidate_activated", true).put("activated_model_version", activated);
            } catch (InterruptedException | SecurityException | android.database.sqlite.SQLiteDatabaseLockedException retry) {
                throw retry;
            } catch (Exception unavailable) {
                report.put("candidate_activated", false).put("activation_pending", "lifecycle_admission_failed")
                    .put("activation_error", unavailable.toString());
            }
        }
        return report.put("as_of_epoch_ms", asOf).put("model_version", model.modelVersion);
    }

    private static JSONObject receipt(Context context) throws Exception {
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "weekly_maintenance.json"));
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return new JSONObject();
        try (FileInputStream input = file.openRead()) {
            long length = input.getChannel().size();
            if (length < 1 || length > 256000) throw new IllegalArgumentException("bounded weekly receipt required");
            byte[] bytes = new byte[(int) length]; int offset = 0;
            while (offset < bytes.length) { int count = input.read(bytes, offset, bytes.length - offset); if (count < 0) throw new java.io.IOException("weekly receipt truncated"); offset += count; }
            if (input.read() != -1) throw new java.io.IOException("weekly receipt changed");
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Override public boolean onStopJob(JobParameters parameters) { stopTask(); return true; }
    private void stopTask() {
        Task task = current; current = null;
        if (task != null) synchronized (task) { task.stopped = true; task.worker.interrupt(); }
    }
    @Override public void onDestroy() { stopTask(); super.onDestroy(); }
}
