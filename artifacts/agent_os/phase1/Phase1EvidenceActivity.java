package org.traceform.agentos;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Phase1EvidenceActivity extends Activity {
    static final String ACTION_SEED = "org.traceform.agentos.validation.PHASE1_SEED";
    static final String ACTION_VERIFY = "org.traceform.agentos.validation.PHASE1_VERIFY";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView resultView = new TextView(this);
        resultView.setTextSize(18);
        resultView.setGravity(Gravity.CENTER_VERTICAL);
        resultView.setPadding(32, 32, 32, 32);
        setContentView(resultView);
        new Thread(() -> {
            String result;
            try {
                JSONObject report = ACTION_SEED.equals(getIntent().getAction()) ? seed() : verify();
                result = report.toString(2);
                writeFile("phase1_report.json", result);
            } catch (Exception error) {
                result = "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage();
                try { writeFile("phase1_report.json", new JSONObject().put("status", "FAIL").put("error", result).toString(2)); }
                catch (Exception ignored) {}
            }
            String displayed = result;
            runOnUiThread(() -> resultView.setText(displayed));
        }, "phase1-evidence").start();
    }

    private JSONObject seed() throws Exception {
        AgentStore store = new AgentStore(this);
        try {
            if (!store.listTasks().isEmpty()) throw new IllegalStateException("Validation package must start with an empty task store");
            JSONArray expected = new JSONArray();
            expected.put(createScript(store, "SYNTHETIC phase1 pause-resume-complete", new String[]{
                AgentStore.ACTIVE, AgentStore.PAUSED, AgentStore.ACTIVE, AgentStore.COMPLETED
            }));
            expected.put(createScript(store, "SYNTHETIC phase1 pause-resume-cancel", new String[]{
                AgentStore.ACTIVE, AgentStore.PAUSED, AgentStore.ACTIVE, AgentStore.CANCELLED
            }));
            expected.put(createScript(store, "SYNTHETIC phase1 restart-resume-cancel", new String[]{
                AgentStore.ACTIVE, AgentStore.PAUSED
            }));
            JSONObject seed = new JSONObject().put("tasks", expected);
            writeFile("phase1_expected.json", seed.toString());
            return new JSONObject().put("status", "SEEDED")
                .put("syntheticOnly", true).put("taskCount", expected.length())
                .put("note", "Task journal transitions only; no Agent execution was performed.");
        } finally {
            store.close();
        }
    }

    private JSONObject createScript(AgentStore store, String title, String[] transitions) throws Exception {
        AgentStore.Task task = store.createTask(title);
        for (String transition : transitions) store.transition(task.id, transition);
        AgentStore.Task finalTask = findTask(store.listTasks(), task.id);
        return new JSONObject().put("id", task.id).put("title", task.title)
            .put("initialStatus", AgentStore.QUEUED).put("statusBeforeRestart", finalTask.status)
            .put("createdAt", task.createdAt).put("updatedAtBeforeRestart", finalTask.updatedAt)
            .put("auditCountBeforeRestart", auditCount(store.getReadableDatabase(), task.id));
    }

    private JSONObject verify() throws Exception {
        JSONObject expected = new JSONObject(readFile("phase1_expected.json"));
        JSONArray tasks = expected.getJSONArray("tasks");
        AgentStore store = new AgentStore(this);
        JSONArray checked = new JSONArray();
        try {
            Map<String, AgentStore.Task> actualTasks = new HashMap<>();
            List<AgentStore.Task> current = store.listTasks();
            for (AgentStore.Task task : current) actualTasks.put(task.id, task);
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject item = tasks.getJSONObject(i);
                String id = item.getString("id");
                AgentStore.Task actual = actualTasks.get(id);
                if (actual == null || !item.getString("statusBeforeRestart").equals(actual.status)
                        || item.getLong("createdAt") != actual.createdAt
                        || item.getLong("updatedAtBeforeRestart") != actual.updatedAt
                        || item.getInt("auditCountBeforeRestart") != auditCount(store.getReadableDatabase(), id)) {
                    throw new IllegalStateException("Task journal did not survive force-stop/relaunch: " + id);
                }
                JSONObject check = new JSONObject().put("id", id).put("statusBeforeRestart", actual.status)
                    .put("createdAtPreserved", true).put("updatedAtPreserved", true)
                    .put("auditCountPreserved", true);
                if (i == 2) {
                    store.transition(id, AgentStore.ACTIVE);
                    store.transition(id, AgentStore.CANCELLED);
                    AgentStore.Task resumed = findTask(store.listTasks(), id);
                    if (!AgentStore.CANCELLED.equals(resumed.status)
                            || auditCount(store.getReadableDatabase(), id) != item.getInt("auditCountBeforeRestart") + 2) {
                        throw new IllegalStateException("Paused task could not be resumed and cancelled after restart");
                    }
                    check.put("resumedAfterRestart", true).put("finalStatus", resumed.status)
                        .put("auditCountAfterResume", auditCount(store.getReadableDatabase(), id));
                }
                checked.put(check);
            }
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            boolean internet = false;
            if (packageInfo.requestedPermissions != null) {
                for (String permission : packageInfo.requestedPermissions) {
                    if ("android.permission.INTERNET".equals(permission)) internet = true;
                }
            }
            if (internet) throw new IllegalStateException("The validation package unexpectedly requests INTERNET");
            return new JSONObject().put("status", "PASS")
                .put("device", new JSONObject().put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL).put("device", Build.DEVICE)
                    .put("androidRelease", Build.VERSION.RELEASE).put("apiLevel", Build.VERSION.SDK_INT))
                .put("package", getPackageName()).put("taskJournalSurvivedForceStop", true)
                .put("pauseResumeCancelJournalVerified", true).put("internetPermissionDeclared", false)
                .put("syntheticOnly", true).put("tasks", checked)
                .put("agentExecutionVerified", false).put("backgroundContinuationVerified", false)
                .put("limitation", "This verifies durable task records and state/audit transitions only. It does not verify that an Agent performed the tasks or continued work in the background.");
        } finally {
            store.close();
        }
    }

    private void writeFile(String name, String content) throws Exception {
        try (FileOutputStream output = openFileOutput(name, MODE_PRIVATE)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private String readFile(String name) throws Exception {
        try (java.io.InputStream input = openFileInput(name);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int auditCount(SQLiteDatabase database, String taskId) {
        try (Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM audit WHERE task_id=?", new String[]{taskId})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static AgentStore.Task findTask(List<AgentStore.Task> tasks, String id) {
        for (AgentStore.Task task : tasks) if (id.equals(task.id)) return task;
        return null;
    }
}
