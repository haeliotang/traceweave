package com.inceptless.traceweave;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentJobService extends JobService {
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<Integer, Run> runs = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final class Run {
        final JobParameters parameters;
        final String taskId;
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicBoolean systemStopped = new AtomicBoolean();

        Run(JobParameters parameters, String taskId) {
            this.parameters = parameters;
            this.taskId = taskId;
        }
    }

    @Override
    public boolean onStartJob(JobParameters parameters) {
        PersistableBundle extras = parameters.getExtras();
        String taskId = extras == null ? null : extras.getString(AgentScheduler.EXTRA_TASK_ID);
        if (taskId == null || taskId.isEmpty()) return false;

        Run run = new Run(parameters, taskId);
        if (runs.putIfAbsent(parameters.getJobId(), run) != null) return false;
        workers.execute(() -> runTask(run));
        return true;
    }

    private void runTask(Run run) {
        AgentStore store = new AgentStore(this);
        try {
            new AgentExecutor(store, new CapabilityBroker(this, store), new HybridReasoningEngine(this, store)).execute(run.taskId, run.stopped);
        } catch (Exception error) {
            if (store.isTaskActive(run.taskId)) store.markExecutionFailed(run.taskId);
        } finally {
            store.close();
            runs.remove(run.parameters.getJobId(), run);
            if (!run.systemStopped.get()) {
                mainHandler.post(() -> {
                    if (!run.systemStopped.get()) jobFinished(run.parameters, false);
                });
            }
        }
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        Run run = runs.get(parameters.getJobId());
        if (run != null) {
            run.systemStopped.set(true);
            run.stopped.set(true);
        }
        return true;
    }

    @Override
    public void onDestroy() {
        for (Run run : runs.values()) {
            run.systemStopped.set(true);
            run.stopped.set(true);
        }
        workers.shutdownNow();
        super.onDestroy();
    }
}
