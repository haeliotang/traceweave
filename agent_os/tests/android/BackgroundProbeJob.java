package org.traceform.agentos;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Isolated measurement only. No foreground service, network, user files or sensor collection. */
public final class BackgroundProbeJob extends JobService {
    static final int JOB_ID = 9990001;
    static final long PHASE_MS = 10 * 60 * 1000;
    static File file(Context context) { return new File(context.getFilesDir(), "background-probe.json"); }
    static JSONObject read(Context context) throws Exception {
        return new JSONObject(new String(new AtomicFile(file(context)).readFully(), StandardCharsets.UTF_8));
    }
    static void write(Context context, JSONObject state) throws Exception {
        AtomicFile file = new AtomicFile(file(context)); FileOutputStream stream = file.startWrite();
        try { stream.write(state.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
        catch (Exception error) { file.failWrite(stream); throw error; }
    }
    static void schedule(Context context, long delay) {
        JobScheduler jobs = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, BackgroundProbeJob.class))
            .setMinimumLatency(Math.max(1000, delay)).setOverrideDeadline(Math.max(1000, delay) + 15000).build();
        if (jobs.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Probe scheduling failed");
    }
    static JSONObject sample(Context context, String phase) throws Exception {
        BatteryManager battery = (BatteryManager) context.getSystemService(BATTERY_SERVICE);
        PowerManager power = (PowerManager) context.getSystemService(POWER_SERVICE);
        UsageStatsManager usage = (UsageStatsManager) context.getSystemService(USAGE_STATS_SERVICE);
        Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int charge = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        int current = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        return new JSONObject().put("phase", phase).put("wallTimeMs", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("pid", android.os.Process.myPid())
            .put("processCpuMs", android.os.Process.getElapsedCpuTime())
            .put("chargeCounter_uAh", charge == Integer.MIN_VALUE ? JSONObject.NULL : charge)
            .put("currentNow_uA", current == Integer.MIN_VALUE ? JSONObject.NULL : current)
            .put("capacityPercent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("plugged", status == null ? -1 : status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
            .put("interactive", power.isInteractive()).put("powerSave", power.isPowerSaveMode())
            .put("deviceIdle", power.isDeviceIdleMode()).put("standbyBucket", usage.getAppStandbyBucket())
            .put("ignoringBatteryOptimizations", power.isIgnoringBatteryOptimizations(context.getPackageName()));
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        new Thread(() -> {
            long delay = 0;
            try (AgentStore store = new AgentStore(this)) {
                JSONObject state = read(this);
                long now = SystemClock.elapsedRealtime(); String phase = state.getString("status");
                if ("await_unplug".equals(phase)) {
                    JSONObject sample = sample(this, "baseline_start");
                    if (sample.getInt("plugged") != 0) delay = 30000;
                    else {
                        state.getJSONArray("samples").put(sample); state.put("status", "baseline").put("phaseStartMs", now);
                        delay = 60000;
                    }
                } else if ("baseline".equals(phase)) {
                    long remaining = state.getLong("phaseStartMs") + PHASE_MS - now;
                    if (remaining > 0) { state.getJSONArray("samples").put(sample(this, "baseline_tick")); delay = Math.min(60000, remaining); }
                    else {
                        state.getJSONArray("samples").put(sample(this, "baseline_end"));
                        state.getJSONArray("samples").put(sample(this, "work_start"));
                        state.put("status", "work").put("phaseStartMs", now);
                        enqueueWork(store); state.put("tasksSubmitted", 1); delay = 60000;
                    }
                } else if ("work".equals(phase)) {
                    long remaining = state.getLong("phaseStartMs") + PHASE_MS - now;
                    if (remaining <= 0) {
                        state.getJSONArray("samples").put(sample(this, "work_end")); state.put("status", "complete");
                        int complete = 0, active = 0;
                        for (AgentStore.Task task : store.listTasks()) {
                            if (AgentStore.COMPLETED.equals(task.status)) complete++;
                            if (AgentStore.ACTIVE.equals(task.status)) active++;
                        }
                        state.put("tasksCompleted", complete).put("tasksStillActive", active);
                    } else {
                        state.getJSONArray("samples").put(sample(this, "work_tick"));
                        enqueueWork(store); state.put("tasksSubmitted", state.getInt("tasksSubmitted") + 1);
                        delay = Math.min(60000, remaining);
                    }
                }
                write(this, state);
                android.util.Log.i("AgentOsBackgroundProbe", "Recorded status=" + state.getString("status"));
            } catch (Exception error) { android.util.Log.e("AgentOsBackgroundProbe", "Probe failed", error); }
            jobFinished(parameters, false);
            if (delay > 0) schedule(this, delay);
        }, "synthetic-background-probe").start();
        return true;
    }
    static AgentStore.Task createWork(AgentStore store) throws Exception {
        AgentStore.Task task = store.createTask("SYNTHETIC background local task");
        store.savePlan(task.id, new ExecutionPlan(task.id, task.title, Collections.singletonList(new ExecutionPlan.Step(0,
            "SYNTHETIC local summary", "builtin.synthesis", "local_inference", "", ExecutionPlan.STEP_PENDING, "", 0)),
            System.currentTimeMillis(), 0).withContext(TaskContext.selected(Collections.emptyList(), true)));
        store.transition(task.id, AgentStore.ACTIVE);
        return store.getTask(task.id);
    }
    private void enqueueWork(AgentStore store) throws Exception { AgentScheduler.scheduleTask(this, createWork(store)); }
    @Override public boolean onStopJob(JobParameters parameters) { return true; }
}
