package org.traceform.agentos;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import java.util.HashSet;
import java.util.Set;

final class AgentScheduler {
    static final String EXTRA_TASK_ID = "org.traceform.agentos.task_id";
    private static final long RETRY_BACKOFF_MILLIS = 30_000;

    private AgentScheduler() {}

    static void scheduleTask(Context context, AgentStore.Task task) {
        scheduleTask(context, task, 0);
    }

    static void scheduleTask(Context context, AgentStore.Task task, long minimumLatencyMillis) {
        if (!AgentStore.ACTIVE.equals(task.status))
            throw new IllegalArgumentException("Only active tasks can be scheduled");
        if (minimumLatencyMillis < 0) throw new IllegalArgumentException("The job delay cannot be negative");
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_TASK_ID, task.id);
        JobInfo.Builder builder = new JobInfo.Builder(task.jobId, new ComponentName(context, AgentJobService.class))
            .setExtras(extras)
            .setPersisted(true)
            .setBackoffCriteria(RETRY_BACKOFF_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if (minimumLatencyMillis > 0) builder.setMinimumLatency(minimumLatencyMillis);
        JobInfo job = builder.build();
        if (scheduler == null || scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS)
            throw new IllegalStateException("Android could not schedule this task");
    }

    static void cancelTask(Context context, int jobId) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(jobId);
    }

    static int reconcileActiveTasks(Context context, AgentStore store) throws Exception {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) throw new IllegalStateException("Android job scheduling is unavailable");
        Set<Integer> scheduledIds = new HashSet<>();
        for (JobInfo job : scheduler.getAllPendingJobs()) scheduledIds.add(job.getId());
        int restored = 0;
        for (AgentStore.Task listed : store.listTasks()) {
            if (!AgentStore.ACTIVE.equals(listed.status) || scheduledIds.contains(listed.jobId)) continue;
            AgentStore.Task current = store.getTask(listed.id);
            if (current == null || !AgentStore.ACTIVE.equals(current.status) || scheduledIds.contains(current.jobId)) continue;
            scheduleTask(context, current);
            store.appendTaskEvent(current.id, "task_job_reconciled");
            scheduledIds.add(current.jobId);
            restored++;
        }
        return restored;
    }
}
