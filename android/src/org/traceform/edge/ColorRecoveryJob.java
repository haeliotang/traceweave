package org.traceform.edge;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

public final class ColorRecoveryJob extends JobService {
    static final int JOB_ID = 34713;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Task current;
    private static final class Task {
        volatile boolean stopped;
        Thread worker;
    }
    static JobInfo schedule(Context context, String nonce) {
        return schedule(context, nonce, false);
    }
    static JobInfo schedule(Context context, String nonce, boolean failureFixture) {
        PersistableBundle extras = new PersistableBundle(); extras.putString("nonce", nonce);
        extras.putBoolean("failure_fixture", failureFixture);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, ColorRecoveryJob.class))
            .setMinimumLatency(2000).setOverrideDeadline(30000)
            .setBackoffCriteria(10000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true).setExtras(extras).build();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS)
            throw new IllegalStateException("color recovery scheduling failed");
        return job;
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        Task task = new Task(); current = task;
        task.worker = new Thread(() -> {
            boolean retry = true;
            JSONObject receipt;
            try {
                if (parameters.getExtras().getBoolean("failure_fixture")
                        && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                    throw new java.io.IOException("synthetic recovery failure before settings access");
                receipt = NightDisplay.recover(this);
                retry = new ColorTemperatureJournal(new File(getFilesDir(), "color_temperature_pending.bin")).exists();
            } catch (Exception error) {
                android.util.Log.w("Traceform", "Color recovery remains pending", error);
                receipt = new JSONObject();
                try {
                    receipt.put("delivered", false).put("eligible_for_outcome", false)
                        .put("reason", "recovery_exception").put("error_type", error.getClass().getSimpleName())
                        .put("settings_write_attempts", JSONObject.NULL);
                } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            }
            try {
                receipt.put("nonce", parameters.getExtras().getString("nonce"))
                    .put("job_id", parameters.getJobId()).put("service_process_id", android.os.Process.myPid())
                    .put("retry", retry).put("failure_fixture", parameters.getExtras().getBoolean("failure_fixture"))
                    .put("completed_epoch_ms", System.currentTimeMillis());
                AtomicFile file = new AtomicFile(new File(getFilesDir(), "color_recovery_job.json"));
                FileOutputStream output = file.startWrite();
                try {
                    output.write(receipt.toString().getBytes("UTF-8")); file.finishWrite(output);
                } catch (Exception error) { file.failWrite(output); throw error; }
            } catch (Exception error) {
                retry = true;
                android.util.Log.w("Traceform", "Color recovery receipt could not be saved", error);
            }
            final boolean reschedule = retry;
            handler.post(() -> {
                if (current == task && !task.stopped) {
                    current = null; jobFinished(parameters, reschedule);
                }
            });
        }, "traceform-color-recovery");
        task.worker.start(); return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        stopTask(); return true;
    }
    private void stopTask() {
        if (current != null) {
            current.stopped = true; current.worker.interrupt(); current = null;
        }
    }
    @Override public void onDestroy() { stopTask(); super.onDestroy(); }
}
