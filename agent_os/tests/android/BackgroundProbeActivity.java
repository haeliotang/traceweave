package com.inceptless.traceweave;

import android.app.Activity;
import android.app.job.JobScheduler;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONArray;

public final class BackgroundProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.widget.TextView output = new android.widget.TextView(this); setContentView(output);
        if (state != null && getIntent().getAction() != null && getIntent().getAction().startsWith("ARM_")) {
            finish();
            return;
        }
        try (AgentStore store = new AgentStore(this)) {
            if ("ARM_CONTROLLED".equals(getIntent().getAction())) {
                ((JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE)).cancelAll();
                for (String table : new String[]{"audit", "tasks", "memories", "tombstones"}) store.getWritableDatabase().delete(table, null, null);
                new CloudModelConfig(false, "", "", "synthetic-offline", 15000, .1).save(this);
                new android.util.AtomicFile(ControlledProbeService.file(this)).delete();
                startForegroundService(new android.content.Intent(this, ControlledProbeService.class));
                output.setText("ARMED: controlled foreground measurement. Completion notification will ask you to reconnect USB.");
                finish();
            } else if ("ARM_BACKGROUND".equals(getIntent().getAction())) {
                ((JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE)).cancelAll();
                for (String table : new String[]{"audit", "tasks", "memories", "tombstones"}) store.getWritableDatabase().delete(table, null, null);
                new CloudModelConfig(false, "", "", "synthetic-offline", 15000, .1).save(this);
                JSONObject value = new JSONObject().put("format", "traceform.background-probe.v1").put("status", "await_unplug")
                    .put("phaseDurationMs", BackgroundProbeJob.PHASE_MS).put("tasksSubmitted", 0)
                    .put("samples", new JSONArray().put(BackgroundProbeJob.sample(this, "armed")));
                BackgroundProbeJob.write(this, value); BackgroundProbeJob.schedule(this, 15000);
                output.setText("ARMED: physical unplug, screen off; ten-minute baseline then ten-minute synthetic local tasks.");
                finish();
            } else if ("ARM_RESTRICTED_JOB".equals(getIntent().getAction())) {
                AgentStore.Task task = BackgroundProbeJob.createWork(store);
                AgentScheduler.scheduleTask(this, task, 30000);
                getSharedPreferences("background_probe", 0).edit().putString("restrictedTask", task.id).putInt("restrictedJob", task.jobId).commit();
                output.setText("ARMED restricted job");
            } else if ("VERIFY_RESTRICTED_JOB".equals(getIntent().getAction())) {
                AgentStore.Task task = store.getTask(getSharedPreferences("background_probe", 0).getString("restrictedTask", ""));
                if (task == null || !AgentStore.COMPLETED.equals(task.status)) throw new IllegalStateException("Restricted job has not recovered");
                output.setText("PASS: deferred local job recovered without any cloud or file action");
            } else if ("VERIFY_OFFLINE_LOCAL".equals(getIntent().getAction())) {
                boolean airplane = android.provider.Settings.Global.getInt(getContentResolver(), "airplane_mode_on", 0) == 1;
                android.net.ConnectivityManager network = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (!airplane || network.getActiveNetwork() != null) throw new IllegalStateException("Offline test conditions not met");
                AgentStore.Task task = BackgroundProbeJob.createWork(store);
                new AgentExecutor(store, new CapabilityBroker(this, store)).execute(task.id, new java.util.concurrent.atomic.AtomicBoolean());
                if (!AgentStore.COMPLETED.equals(store.getTask(task.id).status)) throw new IllegalStateException("Offline local task failed");
                JSONObject result = new JSONObject().put("airplaneMode", true).put("activeNetworkAbsent", true).put("localTaskCompleted", true);
                try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "offline-result.json"))) {
                    stream.write(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                output.setText("PASS: offline local task complete");
            } else output.setText(BackgroundProbeJob.read(this).toString());
        } catch (Exception error) {
            output.setText("FAIL " + error.getMessage()); android.util.Log.e("AgentOsBackgroundProbe", "Probe activity failed", error);
        }
    }
}
