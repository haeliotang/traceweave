package org.traceform.agentos;

import android.app.Activity;
import android.os.Bundle;
import android.net.Uri;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Collections;

/** Synthetic-only checks; installed exclusively in the isolated validation package. */
public final class WorkflowValidationActivity extends Activity {
    private int checks;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.widget.TextView output = new android.widget.TextView(this); setContentView(output);
        new Thread(() -> {
            String result;
            try (AgentStore store = new AgentStore(this)) {
                if ("MEMORY_CHOICES".equals(getIntent().getAction())) memoryChoices(store);
                else if ("TASK_CENTER".equals(getIntent().getAction())) taskCenter(store);
                else if ("WORKFLOW_GUARD".equals(getIntent().getAction())) workflowGuard(store);
                else if ("SENSOR_CONTEXT".equals(getIntent().getAction())) sensorContext();
                else if ("SEED_WORKFLOW_UI".equals(getIntent().getAction())) {
                    reset(store); getSharedPreferences("agent_os_prefs", 0).edit().putString("app_lang", "en").commit();
                    store.createMemory(AgentStore.MEMORY_PREFERENCE, "SYNTHETIC camping preference: keep checklists concise");
                    store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC camping Traceform fact", AgentStore.MEMORY_SOURCE_TRACEFORM, "synthetic:ui");
                }
                else if ("OPEN_WORKFLOW_UI".equals(getIntent().getAction())) check(true, "open existing synthetic workflows");
                else if ("VERIFY_WORKFLOW_UI".equals(getIntent().getAction())) verifyWorkflowUi(store, true);
                else if ("VERIFY_IMPORTED_WORKFLOWS".equals(getIntent().getAction())) verifyWorkflowUi(store, false);
                else if ("SEED_CENTER_UI".equals(getIntent().getAction())) {
                    AgentStore.Memory recipe = uiRecipe(store);
                    AgentStore.Task run = WorkflowTemplate.createRun(store, recipe.id, "SYNTHETIC center recovery");
                    store.prepareReviewedDocument(run.id, "# SYNTHETIC draft recovered after process death", TaskContext.fingerprint(run), "");
                    getSharedPreferences("workflow_validation", 0).edit().putString("centerTask", run.id).commit();
                }
                else if ("OPEN_CENTER_UI".equals(getIntent().getAction())) check(true, "open task center after restart");
                else if ("VERIFY_CENTER_UI".equals(getIntent().getAction())) {
                    AgentStore.Task task = store.getTask(getSharedPreferences("workflow_validation", 0).getString("centerTask", ""));
                    check(task != null && AgentStore.CANCELLED.equals(task.status), "restored draft cancelled via actual UI");
                    check(ExecutionPlan.STEP_CANCELLED.equals(task.plan.steps.get(0).status), "cancelled restored draft cannot dispatch");
                }
                else workflows(store);
                result = "PASS workflow checks=" + checks + " mode=" + getIntent().getAction();
            } catch (Exception error) {
                result = "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage();
                android.util.Log.e("AgentOsWorkflowValidation", result, error);
            }
            String message = result;
            android.util.Log.i("AgentOsWorkflowValidation", message);
            runOnUiThread(() -> {
                output.setText(message);
                if (message.startsWith("PASS") && ("SEED_WORKFLOW_UI".equals(getIntent().getAction()) || "OPEN_WORKFLOW_UI".equals(getIntent().getAction()))) {
                    startActivity(new android.content.Intent(this, WorkflowActivity.class)); finish();
                }
                if (message.startsWith("PASS") && "OPEN_CENTER_UI".equals(getIntent().getAction())) {
                    startActivity(new android.content.Intent(this, TaskCenterActivity.class)); finish();
                }
            });
        }, "synthetic-workflow-validation").start();
    }

    private void reset(AgentStore store) {
        for (String table : new String[]{"audit", "tasks", "memories", "tombstones"}) store.getWritableDatabase().delete(table, null, null);
    }
    private void sensorContext() throws Exception {
        PhysicalSensoriumManager sensors = PhysicalSensoriumManager.getInstance(this);
        sensors.stopMonitoring();
        JSONObject value = sensors.evaluateCurrentContext();
        check("Unknown".equals(value.optString("motion_state")), "disabled sensors must not claim stationary motion");
        check(value.isNull("light_lux"), "disabled sensors must not invent light readings");
        JSONObject readings = new JSONObject().put("light_lux", 0).put("proximity_cm", 0)
            .put("temperature_c", 27.4).put("accel_x", 0).put("accel_y", 0).put("accel_z", 9.81)
            .put("gyro_x", 0).put("gyro_y", 0).put("gyro_z", 0);
        JSONObject dark = SensorContext.fallback(readings, true);
        check(dark.getDouble("light_lux") == 0 && dark.getDouble("temperature_c") == 27.4, "fallback preserves measured zero lux and battery temperature");
        check(!dark.getBoolean("voice_wake_allowed"), "measured near and dark context gates voice wake");
        readings.put("light_lux", 700).put("gyro_x", 1);
        JSONObject moving = SensorContext.fallback(readings, true);
        check(moving.getDouble("light_lux") == 700 && "MotionDetected".equals(moving.getString("motion_state")), "new telemetry changes context without native library");
        check(moving.getBoolean("voice_wake_allowed"), "updated measured light clears the covering estimate");
        JSONObject unknown = SensorContext.fallback(new JSONObject(), true);
        check("Unknown".equals(unknown.getString("motion_state")) && unknown.isNull("in_pocket"), "missing readings remain unknown");
        check(!SensorContext.describe(unknown, true).contains("150") && !SensorContext.describe(unknown, false).contains("31.5"), "bilingual display does not invent numeric defaults");
        sensors.startMonitoring();
        try {
            long deadline = android.os.SystemClock.elapsedRealtime() + 4000;
            JSONObject live;
            do { Thread.sleep(100); live = sensors.evaluateCurrentContext(); }
            while (sensors.isMonitoring() && live.isNull("light_lux") && android.os.SystemClock.elapsedRealtime() < deadline);
            check(!sensors.isMonitoring() || !live.isNull("light_lux"), "available Pixel light sensor delivers actual callback data");
        } finally { sensors.stopMonitoring(); }
        check(sensors.evaluateCurrentContext().isNull("light_lux"), "stop invalidates previous session readings");
    }
    private void check(boolean value, String message) { checks++; if (!value) throw new IllegalStateException(message); }
    private interface Checked { void run() throws Exception; }
    private void rejects(Checked action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | SecurityException | org.json.JSONException expected) { rejected = true; }
        check(rejected, message);
    }
    private void workflows(AgentStore store) throws Exception {
        reset(store);
        WorkflowTemplate recipe = new WorkflowTemplate("SYNTHETIC checklist", "Prepare a local Markdown checklist for {{input}}.");
        AgentStore.Memory template = store.createMemory(AgentStore.MEMORY_WORKFLOW, recipe.encode());
        rejects(() -> new WorkflowTemplate("name", "No input placeholder"), "missing parameter rejected");
        rejects(() -> new WorkflowTemplate("name", "{{input}} {{grant}}"), "unsupported parameter rejected");
        AgentStore.Memory note = store.createMemory(AgentStore.MEMORY_WORKFLOW, "SYNTHETIC legacy workflow note");
        rejects(() -> WorkflowTemplate.decode(note), "legacy notes cannot execute");
        JSONObject unauthorized = new JSONObject(recipe.encode()).put("uri", "content://unapproved/file");
        AgentStore.Memory bad = store.createMemory(AgentStore.MEMORY_WORKFLOW, unauthorized.toString());
        rejects(() -> WorkflowTemplate.createRun(store, bad.id, "SYNTHETIC"), "recipe cannot import a file target");
        AgentStore.Memory version = store.createMemory(AgentStore.MEMORY_WORKFLOW, new JSONObject(recipe.encode()).put("version", 1.5).toString());
        rejects(() -> WorkflowTemplate.decode(version), "fractional workflow version rejected");
        AgentStore.Task first = WorkflowTemplate.createRun(store, template.id, "SYNTHETIC trip A");
        AgentStore.Task second = WorkflowTemplate.createRun(store, template.id, "SYNTHETIC trip B");
        check(!first.id.equals(second.id) && !first.title.equals(second.title), "two independent parameterized runs");
        check(TaskContext.localOnly(first.plan) && TaskContext.memories(first.plan).isEmpty(), "fresh run has no selected memories or cloud grant");
        check(TaskContext.isDocument(first.plan) && new JSONObject(first.plan.context).getJSONObject("workflow").getString("id").equals(template.id), "run retains workflow identity");
        String[] hashes = new String[2]; int index = 0;
        for (AgentStore.Task task : new AgentStore.Task[]{first, second}) {
            AgentStore.Task draft = store.prepareReviewedDocument(task.id, DocumentAction.markdown(task.title, Collections.emptyList()), TaskContext.fingerprint(task), "");
            check(AgentStore.WAITING_FOR_USER.equals(draft.status), "each run stops for file confirmation");
            rejects(() -> DocumentAction.writeOnce(getContentResolver(), store, task.id), "recipe confers no file write grant");
            JSONObject value = new JSONObject(draft.plan.steps.get(0).observation);
            String attempt = store.claimExternalAction(task.id, 0, value.getString("draftId"), value);
            Uri uri = Uri.parse("content://" + getPackageName() + ".documents.fixture/workflow_" + index);
            store.bindDocumentTarget(task.id, attempt, uri.toString());
            DocumentAction.writeOnce(getContentResolver(), store, task.id);
            AgentStore.Task done = store.getTask(task.id);
            check(DocumentAction.locallyVerified(done), "independent run readback verified");
            hashes[index++] = new JSONObject(done.plan.steps.get(0).observation).getString("sha256");
        }
        check(!hashes[0].equals(hashes[1]), "different parameters create different verified contents");
        byte[] archive = store.exportArchive("synthetic-workflow-archive".toCharArray());
        reset(store);
        store.importArchive(new java.io.ByteArrayInputStream(archive), "synthetic-workflow-archive".toCharArray());
        AgentStore.Task imported = store.getTask(first.id);
        JSONObject receipt = new JSONObject(imported.plan.steps.get(0).observation);
        check(!receipt.has("uri") && !receipt.getBoolean("localAccess"), "workflow migration does not inherit file permission");
        check(TaskContext.localOnly(imported.plan), "imported workflow run has local-only policy");
        AgentStore.Task third = WorkflowTemplate.createRun(store, template.id, "SYNTHETIC migrated trip C");
        check(TaskContext.memories(third.plan).isEmpty() && AgentStore.QUEUED.equals(third.status), "migrated recipe runs anew with no implicit context");
        rejects(() -> new CloudModelClient().prepareDocument(third, new CloudModelConfig(true, "https://example.invalid", "", "synthetic", 15000, .1)), "fresh migrated run has no cloud authorization");
        store.deleteMemory(template.id);
        rejects(() -> WorkflowTemplate.createRun(store, template.id, "SYNTHETIC"), "deleted recipe cannot run again");
        check(store.getTask(first.id) != null && new JSONObject(store.getTask(first.id).plan.context).has("workflow"), "deleting template preserves historical run snapshot");
    }

    private void memoryChoices(AgentStore store) throws Exception {
        reset(store);
        AgentStore.Memory user = store.createMemory(AgentStore.MEMORY_PREFERENCE, "SYNTHETIC 露营清单简洁");
        AgentStore.Memory trace = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC bounded local source", AgentStore.MEMORY_SOURCE_TRACEFORM, "synthetic:source");
        AgentStore.Memory suggestion = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC user confirmed preference", AgentStore.MEMORY_SOURCE_CONSOLIDATION, "confirmed:synthetic");
        store.createMemory(AgentStore.MEMORY_WORKFLOW, new WorkflowTemplate("SYNTHETIC recipe", "Plan {{input}}").encode());
        MemorySelection selection = new MemorySelection(store.listPlanningMemories(), null);
        check(selection.ids().isEmpty(), "no automatic selection");
        check(selection.visible("露营", null, false, "").size() == 1, "Chinese text search");
        check(selection.visible("", AgentStore.MEMORY_SOURCE_TRACEFORM, false, "").get(0).id.equals(trace.id), "source filter");
        check(selection.visible("", null, false, "").size() == 3, "executable templates excluded from memory suggestions");
        selection.visible("", null, true, "露营清单");
        check(selection.ids().isEmpty(), "ranking never selects a memory");
        selection.choose(user.id, true);
        selection.visible("bounded", AgentStore.MEMORY_SOURCE_TRACEFORM, false, "");
        check(selection.chosen(store.listPlanningMemories()).size() == 1, "filter changes retain explicit selection");
        AgentStore.Task task = store.createTask("SYNTHETIC memory reference");
        store.savePlan(task.id, new ExecutionPlan(task.id, task.title, Collections.emptyList(), 1, 0)
            .withContext(TaskContext.selected(selection.chosen(store.listPlanningMemories()))));
        check(store.memoryReferences(user.id).size() == 1 && store.memoryReferences(trace.id).isEmpty(), "reference list tracks stable selected IDs");
        store.deleteMemory(user.id);
        rejects(() -> selection.chosen(store.listPlanningMemories()), "deleted canonical selection rejected before new request");
        check(TaskContext.memories(store.getTask(task.id).plan).get(0).id.equals(user.id), "historical snapshot remains explicitly separate from canonical deletion");
        MemorySelection changed = new MemorySelection(store.listPlanningMemories(), Collections.singletonList(suggestion.id));
        store.updateMemory(suggestion.id, suggestion.kind, "SYNTHETIC edited after selection", suggestion.source, suggestion.sourceReference);
        rejects(() -> changed.chosen(store.listPlanningMemories()), "changed selected content requires new review");
        changed.clear(); check(changed.ids().isEmpty(), "clear selection removes hidden choices too");
    }

    private void taskCenter(AgentStore store) throws Exception {
        reset(store);
        AgentStore.Memory recipe = store.createMemory(AgentStore.MEMORY_WORKFLOW, new WorkflowTemplate("SYNTHETIC center recipe", "Plan {{input}}").encode());
        AgentStore.Task run = WorkflowTemplate.createRun(store, recipe.id, "SYNTHETIC recovery");
        check("planning".equals(TaskCenterState.group(run)) && "review".equals(TaskCenterState.destination(run)), "pending request needs fresh preview");
        AgentStore.Task draft = store.prepareReviewedDocument(run.id, "# SYNTHETIC center draft", TaskContext.fingerprint(run), "");
        check("draft".equals(TaskCenterState.group(draft)) && "document".equals(TaskCenterState.destination(draft)), "draft opens editable document review");
        JSONObject value = new JSONObject(draft.plan.steps.get(0).observation);
        store.claimExternalAction(run.id, 0, value.getString("draftId"), value);
        store.recoverExternalActions();
        AgentStore.Task unknown = store.getTask(run.id);
        check("unknown".equals(TaskCenterState.group(unknown)) && "document".equals(TaskCenterState.destination(unknown)), "dispatched recovery routes to read-only verification");
        rejects(() -> DocumentAction.writeOnce(getContentResolver(), store, run.id), "task center recovery cannot redispatch a write");
        AgentStore.Task local = store.createTask("SYNTHETIC task center lifecycle");
        ExecutionPlan.Step step = new ExecutionPlan.Step(0, "local summary", "builtin.synthesis", "local_inference", "", ExecutionPlan.STEP_PENDING, "", 0);
        store.savePlan(local.id, new ExecutionPlan(local.id, local.title, Collections.singletonList(step), 1, 0));
        check("ready".equals(TaskCenterState.group(store.getTask(local.id))), "planned task is ready, not yet running");
        store.transition(local.id, AgentStore.ACTIVE); check("running".equals(TaskCenterState.group(store.getTask(local.id))), "active group");
        store.transition(local.id, AgentStore.PAUSED); check("paused".equals(TaskCenterState.group(store.getTask(local.id))), "paused group");
        store.transition(local.id, AgentStore.CANCELLED); check("history".equals(TaskCenterState.group(store.getTask(local.id))), "cancelled remains in history");
        AgentStore.Task permission = store.createTask("SYNTHETIC missing capability");
        store.savePlan(permission.id, new ExecutionPlan(permission.id, permission.title, Collections.singletonList(new ExecutionPlan.Step(0,
            "read note", CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE, "", ExecutionPlan.STEP_PENDING, "", 0)), 1, 0));
        store.transition(permission.id, AgentStore.ACTIVE);
        new AgentExecutor(store, new CapabilityBroker(this, store)).execute(permission.id, new java.util.concurrent.atomic.AtomicBoolean());
        check("permission".equals(TaskCenterState.group(store.getTask(permission.id))), "missing grant is distinct from a file draft");
        check("details".equals(TaskCenterState.destination(store.getTask(permission.id))), "capability approval routes to original task controls");
    }

    private AgentStore.Memory uiRecipe(AgentStore store) throws Exception {
        for (AgentStore.Memory memory : store.listMemories())
            if (WorkflowTemplate.isTemplate(memory) && "SYNTHETIC reusable checklist".equals(WorkflowTemplate.decode(memory).name)) return memory;
        throw new IllegalStateException("Synthetic UI template not found");
    }
    private void workflowGuard(AgentStore store) throws Exception {
        reset(store);
        AgentStore.Memory recipe = store.createMemory(AgentStore.MEMORY_WORKFLOW, new WorkflowTemplate("SYNTHETIC execution guard", "Prepare {{input}}").encode());
        AgentStore.Task run = WorkflowTemplate.createRun(store, recipe.id, "SYNTHETIC document");
        rejects(() -> store.transition(run.id, AgentStore.ACTIVE), "unreviewed document workflow cannot enter the generic executor");
        check(AgentStore.QUEUED.equals(store.getTask(run.id).status), "rejected start preserves pending review");
        AgentStore.Task draft = store.prepareReviewedDocument(run.id, "# SYNTHETIC reviewed document", TaskContext.fingerprint(run), "");
        check(AgentStore.WAITING_FOR_USER.equals(draft.status) && "document".equals(TaskCenterState.destination(draft)), "reviewed workflow still reaches file confirmation");
        AgentStore.Task oldRun = WorkflowTemplate.createRun(store, recipe.id, "SYNTHETIC old active journal");
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("status", AgentStore.ACTIVE);
        store.getWritableDatabase().update("tasks", values, "id=?", new String[]{oldRun.id});
        new AgentExecutor(store, new CapabilityBroker(this, store)).execute(oldRun.id, new java.util.concurrent.atomic.AtomicBoolean());
        check(AgentStore.FAILED.equals(store.getTask(oldRun.id).status) && store.getTask(oldRun.id).plan.steps.isEmpty(),
            "old unreviewed active journal fails closed without generic planning");
    }
    private void verifyWorkflowUi(AgentStore store, boolean local) throws Exception {
        AgentStore.Memory recipe = uiRecipe(store);
        int completed = 0; java.util.Set<String> hashes = new java.util.HashSet<>();
        for (AgentStore.Task task : store.listTasks()) {
            if (!AgentStore.COMPLETED.equals(task.status) || task.plan == null || task.plan.context.isEmpty()) continue;
            JSONObject context = new JSONObject(task.plan.context);
            if (!context.has("workflow") || !recipe.id.equals(context.getJSONObject("workflow").optString("id"))) continue;
            completed++; JSONObject receipt = new JSONObject(task.plan.steps.get(0).observation);
            check(DocumentAction.hasReceipt(receipt), "UI workflow file has a verified receipt");
            check(local == receipt.optBoolean("localAccess"), "migration does not confer file grants");
            if (!local) check(!receipt.has("uri"), "migrated UI file URI is historical only");
            hashes.add(receipt.getString("sha256"));
            if (task.title.contains("trip A")) check(TaskContext.memories(task.plan).size() == 1
                && AgentStore.MEMORY_SOURCE_USER.equals(TaskContext.memories(task.plan).get(0).source), "first UI run uses only manually selected filtered memory");
            if (task.title.contains("trip B")) check(TaskContext.memories(task.plan).isEmpty(), "second run does not inherit first run's memory selection");
        }
        check(completed == 2 && hashes.size() == 2, "same UI-created template produced two independently verified files");
        if (!local) {
            check(getContentResolver().getPersistedUriPermissions().isEmpty(), "fresh target has no inherited Android file grant");
            check(!CloudModelConfig.load(this).isConfigured() && CloudModelConfig.load(this).apiKey.isEmpty(), "archive does not configure a model endpoint or key");
            AgentStore.Task run = WorkflowTemplate.createRun(store, recipe.id, "SYNTHETIC migrated repeat");
            check(TaskContext.localOnly(run.plan) && TaskContext.memories(run.plan).isEmpty(), "restored template creates a fresh local-only run");
        }
    }
}
