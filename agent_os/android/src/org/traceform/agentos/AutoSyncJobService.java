package org.traceform.agentos;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

/** One background sync attempt per scheduled window; never runs the model. */
public final class AutoSyncJobService extends JobService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;

    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        new Thread(() -> {
            String result;
            try {
                result = AutoSync.runOnce(this);
            } catch (Exception error) {
                result = "error: " + error.getMessage();
            }
            final String outcome = result;
            handler.post(() -> {
                if (!stopped) jobFinished(params, false);
                android.util.Log.i("AgentOsAutoSync", "auto_sync " + outcome);
            });
        }, "auto-sync").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true;
    }
}
