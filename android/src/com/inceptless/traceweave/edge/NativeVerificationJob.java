// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;

/** Synthetic debug verification with job lifetime instead of broadcast lifetime. */
public final class NativeVerificationJob extends JobService {
    static final int JOB_ID = 34715;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Task current;
    private static final class Task {
        volatile boolean stopped;
        Thread worker;
        String fixture, nonce;
    }
    static void schedule(Context context, String nonce) {
        schedule(context, nonce, "weekly");
    }
    static void scheduleLineage(Context context, String nonce) {
        schedule(context, nonce, "cold_lineage");
    }
    private static void schedule(Context context, String nonce, String fixture) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB_ID) != null) throw new IllegalStateException("debug verification already pending");
        PersistableBundle extras = new PersistableBundle(); extras.putString("nonce", nonce); extras.putString("fixture", fixture);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, NativeVerificationJob.class))
            .setMinimumLatency(0).setOverrideDeadline(1000).setExtras(extras).build();
        if (context.getSystemService(JobScheduler.class).schedule(job) != JobScheduler.RESULT_SUCCESS)
            throw new IllegalStateException("debug weekly verification scheduling failed");
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return false;
        if (current != null) return false;
        Task task = new Task(); current = task;
        task.nonce = parameters.getExtras().getString("nonce");
        task.fixture = parameters.getExtras().getString("fixture", "weekly");
        task.worker = new Thread(() -> {
            long started = System.nanoTime();
            try {
                JSONObject report;
                try {
                    if ("cold_lineage".equals(task.fixture)) report = ColdLineage.verify(new ModelEngine(this), getCacheDir());
                    else if ("weekly".equals(task.fixture)) report = NativeWeeklyAuditFixture.verify(new ModelEngine(this), getCacheDir());
                    else throw new IllegalArgumentException("unknown debug verification fixture");
                }
                catch (Exception error) { report = new JSONObject().put("passed", false).put("error", error.toString()); }
                synchronized (task) {
                if (!task.stopped && !Thread.currentThread().isInterrupted()) {
                    report.put("completed_epoch_ms", System.currentTimeMillis()).put("verification_entry", "DUMP_protected_debug_JobService")
                        .put("execution_ms_before_receipt_encoding", (System.nanoTime() - started) / 1e6)
                        .put("nonce", parameters.getExtras().getString("nonce")).put("job_id", parameters.getJobId());
                    report.put("fixture", task.fixture);
                    write(new File(getFilesDir(), "cold_lineage".equals(task.fixture) ? "cold_lineage_job_test.json" : "weekly_snapshot_receiver_test.json"), report);
                }
                }
            } catch (Exception error) { android.util.Log.e("Traceform", "debug weekly verification failed", error); }
            finally {
                handler.post(() -> {
                    if (current == task && !task.stopped) { current = null; jobFinished(parameters, false); }
                });
            }
        }, "traceform-debug-weekly");
        task.worker.start(); return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        Task task = current;
        stopTask();
        if (task != null) try {
            write(new File(getFilesDir(), "verification_stop.json"), new JSONObject().put("nonce", task.nonce)
                .put("fixture", task.fixture).put("job_id", parameters.getJobId())
                .put("stop_reason", android.os.Build.VERSION.SDK_INT >= 31 ? parameters.getStopReason() : JSONObject.NULL)
                .put("stop_reason_available", android.os.Build.VERSION.SDK_INT >= 31)
                .put("stopped_epoch_ms", System.currentTimeMillis()).put("scope", "system_stop_not_algorithm_result"));
        } catch (Exception error) { android.util.Log.e("Traceform", "debug stop receipt failed", error); }
        return false;
    }
    private void stopTask() {
        Task task = current; current = null;
        if (task != null) synchronized (task) { task.stopped = true; task.worker.interrupt(); }
    }
    @Override public void onDestroy() { stopTask(); super.onDestroy(); }
    static void write(File destination, JSONObject report) throws Exception {
        AtomicFile file = new AtomicFile(destination);
        FileOutputStream output = file.startWrite();
        try { output.write(report.toString().getBytes("UTF-8")); file.finishWrite(output); }
        catch (Exception error) { file.failWrite(output); throw error; }
    }
}
