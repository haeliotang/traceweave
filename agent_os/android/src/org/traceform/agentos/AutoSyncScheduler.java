package org.traceform.agentos;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/** Schedules the periodic background sync job; disabled by default. */
final class AutoSyncScheduler {
    static final int JOB_ID = 4711;
    static final long DEFAULT_INTERVAL_MS = AutoSyncPolicy.DEFAULT_INTERVAL_MS;

    private AutoSyncScheduler() {}

    static long clampInterval(long intervalMs) {
        return AutoSyncPolicy.clampInterval(intervalMs);
    }

    static void apply(Context context, boolean enabled, long intervalMs) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        if (!enabled || !SyncTransport.available()) {
            scheduler.cancel(JOB_ID);
            return;
        }
        long interval = clampInterval(intervalMs);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, AutoSyncJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(interval)
            .setPersisted(true)
            .build();
        scheduler.schedule(job);
    }

    static boolean isScheduled(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return false;
        for (JobInfo info : scheduler.getAllPendingJobs()) {
            if (info.getId() == JOB_ID) return true;
        }
        return false;
    }
}
