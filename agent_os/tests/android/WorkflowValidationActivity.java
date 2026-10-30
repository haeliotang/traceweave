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
                else if ("SENSOR_LIVE_SAMPLES".equals(getIntent().getAction())) sensorLiveSamples();
                else if ("SENSOR_WINDOW".equals(getIntent().getAction())) sensorWindow();
                else if ("MEMORY_LIFECYCLE".equals(getIntent().getAction())) memoryLifecycle(store);
                else if ("DOCUMENT_CALENDAR_CHAIN".equals(getIntent().getAction())) documentCalendarChain(store);
                else if ("DOCUMENT_SHARE_CHAIN".equals(getIntent().getAction())) documentShareChain(store);
                else if ("MEMORY_BRANCH_SEED".equals(getIntent().getAction())) memoryBranchSeed(store);
                else if ("MEMORY_BRANCH_TARGET_EDIT".equals(getIntent().getAction())) memoryBranchTargetEdit(store);
                else if ("MEMORY_BRANCH_SOURCE_EDIT".equals(getIntent().getAction())) memoryBranchSourceEdit(store);
                else if ("MEMORY_BRANCH_TARGET_RESOLVE".equals(getIntent().getAction())) memoryBranchTargetResolve(store);
                else if ("MEMORY_BRANCH_SOURCE_RESOLVE".equals(getIntent().getAction())) memoryBranchSourceResolve(store);
                else if ("SHARE_ESCAPED_CONTENT".equals(getIntent().getAction())) shareEscapedContent(store);
                else if ("VERIFY_SHARE_RESTART".equals(getIntent().getAction())) verifyShareRestart(store);
                else if ("EXPORT_SHARE_MIGRATION".equals(getIntent().getAction())) exportShareMigration(store);
                else if ("IMPORT_SHARE_MIGRATION".equals(getIntent().getAction())) importShareMigration(store);
                else if ("SEED_SHARE_UI".equals(getIntent().getAction())) seedShareUi(store);
                else if ("VERIFY_CHAIN_RESTART".equals(getIntent().getAction())) verifyChainRestart(store);
                else if ("SEED_CALENDAR_RETRY_UI".equals(getIntent().getAction())) seedCalendarRetryUi(store);
                else if ("VERIFY_CALENDAR_RETRY_UI".equals(getIntent().getAction())) verifyCalendarRetryUi(store);
                else if ("CLOUD_PLAN_GUARD".equals(getIntent().getAction())) cloudPlanGuard();
                else if ("LOCAL_ENGINE_LABELS".equals(getIntent().getAction())) localEngineLabels();
                else if ("LOCAL_MODEL_DRAFT_BOUNDARY".equals(getIntent().getAction())) localModelDraftBoundary(store);
                else if ("SEED_LOCAL_MODEL_UI".equals(getIntent().getAction()) || "SEED_LOCAL_MODEL_WAIT_UI".equals(getIntent().getAction()) || "SEED_LOCAL_MODEL_FAILURE_UI".equals(getIntent().getAction())) {
                    reset(store); getSharedPreferences("agent_os_prefs", 0).edit().putString("app_lang", "en").commit();
                    if ("SEED_LOCAL_MODEL_FAILURE_UI".equals(getIntent().getAction())) {
                        java.io.File model = LocalModelActivity.modelFile(this);
                        if (!model.getParentFile().isDirectory() && !model.getParentFile().mkdirs()) throw new java.io.IOException("Cannot create synthetic model directory");
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(model)) { out.write("SYNTHETIC invalid model".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                        store.createMemory(AgentStore.MEMORY_PREFERENCE, "SYNTHETIC selected model context");
                    }
                    if ("SEED_LOCAL_MODEL_WAIT_UI".equals(getIntent().getAction())) {
                        java.util.concurrent.CountDownLatch acquired = new java.util.concurrent.CountDownLatch(1);
                        new Thread(() -> {
                            synchronized (LocalModelFiles.class) {
                                acquired.countDown();
                                try { Thread.sleep(40000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                            }
                        }, "synthetic-model-contention").start();
                        if (!acquired.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Could not prepare synthetic contention");
                    }
                    check(true, "synthetic local model UI seeded");
                }
                else if ("VERIFY_LOCAL_MODEL_UI".equals(getIntent().getAction())) verifyLocalModelUi(store, false);
                else if ("VERIFY_LOCAL_MODEL_CANCEL_UI".equals(getIntent().getAction())) verifyLocalModelUi(store, true);
                else if ("OPEN_LOCAL_MODEL_MANAGER".equals(getIntent().getAction())) check(true, "open isolated model manager");
                else if ("VERIFY_LOCAL_MODEL_MANAGER".equals(getIntent().getAction()))
                    check(LocalModelFiles.verifyQwen15(LocalModelActivity.modelFile(this), new java.util.concurrent.atomic.AtomicBoolean()),
                        "model selected through system picker verifies on readback");
                else if ("SCOPED_SUMMARY_CONTRACT".equals(getIntent().getAction())) scopedSummaryContract();
                else if ("EXPORT_LIFECYCLE".equals(getIntent().getAction())) exportLifecycle(store);
                else if ("IMPORT_LIFECYCLE".equals(getIntent().getAction())) importLifecycle(store);
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
                if (message.startsWith("PASS") && "SEED_SHARE_UI".equals(getIntent().getAction())) {
                    startActivity(new android.content.Intent(this, DocumentTaskActivity.class).putExtra(DocumentTaskActivity.EXTRA_TASK_ID,
                        getSharedPreferences("workflow_validation", 0).getString("shareUiParent", ""))); finish();
                }
                if (message.startsWith("PASS") && "SEED_CALENDAR_RETRY_UI".equals(getIntent().getAction())) {
                    startActivity(new android.content.Intent(this, DocumentTaskActivity.class).putExtra(DocumentTaskActivity.EXTRA_TASK_ID,
                        getSharedPreferences("workflow_validation", 0).getString("retryUiParent", ""))); finish();
                }
                if (message.startsWith("PASS") && ("SEED_LOCAL_MODEL_UI".equals(getIntent().getAction()) || "SEED_LOCAL_MODEL_WAIT_UI".equals(getIntent().getAction()) || "SEED_LOCAL_MODEL_FAILURE_UI".equals(getIntent().getAction()))) {
                    startActivity(new android.content.Intent(this, TaskReviewActivity.class)
                        .putExtra(TaskReviewActivity.EXTRA_DOCUMENT, true)
                        .putExtra(TaskReviewActivity.EXTRA_GOAL, "SYNTHETIC local model checklist")); finish();
                }
                if (message.startsWith("PASS") && "OPEN_LOCAL_MODEL_MANAGER".equals(getIntent().getAction())) {
                    startActivity(new android.content.Intent(this, LocalModelActivity.class)); finish();
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
    private void sensorWindow() throws Exception {
        MotionWindow window = new MotionWindow();
        check("Unknown".equals(window.state(1000)), "empty window is unknown");
        window.add(1000, 100000, 9.81, 0);
        window.add(1500, 100500, 9.81, 0);
        check("Unknown".equals(window.state(1500)), "short window cannot assert a stable state");
        window.add(2300, 101300, 9.81, 0);
        check("LowMotion".equals(window.state(2300)), "sustained low motion is estimated from a window");
        check(window.lastChange() == null, "first classification is not a fabricated transition");
        for (int i = 0; i < 6; i++) window.add(2800 + i * 500, 101800 + i * 500, 10.9, 1);
        check("MotionDetected".equals(window.state(5300)), "sustained motion changes the estimated state");
        JSONObject event = window.lastChange();
        check(event != null && "LowMotion".equals(event.getString("from")) && "MotionDetected".equals(event.getString("to")), "change event includes prior and current estimates");
        check("accelerometer+gyroscope".equals(event.getString("source")) && !event.has("accel_x"), "event identifies source without storing raw sensor samples");
        check("Unknown".equals(window.state(8400)), "stale sensor window becomes unknown");
        long previousEvent = event.getLong("observedAt");
        window.add(8401, 107401, 9.81, 0);
        window.add(9001, 108001, 9.81, 0);
        window.add(9801, 108801, 9.81, 0);
        check(window.lastChange().getLong("observedAt") == previousEvent, "a gap must not fabricate a transition from an expired state");
        window.clear();
        check(window.lastChange() == null && "Unknown".equals(window.state(8000)), "stopping a session clears state and events");
    }
    private void sensorLiveSamples() throws Exception {
        PhysicalSensoriumManager sensors = PhysicalSensoriumManager.getInstance(this);
        sensors.stopMonitoring(); sensors.startMonitoring();
        try {
            Thread.sleep(5500);
            JSONObject reading = sensors.evaluateCurrentContext();
            int count = reading.optInt("motion_sample_count", 0);
            android.util.Log.i("AgentOsSensorDiagnostics", "live_motion_samples=" + count
                + " state=" + reading.optString("motion_state", "Unknown")
                + " accel_available=" + !reading.isNull("accel_x") + " gyro_available=" + !reading.isNull("gyro_x"));
            check(!reading.isNull("accel_x") && !reading.isNull("gyro_x"), "physical accelerometer and gyroscope deliver events");
            check(count >= 3, "physical sensor pairing supplies a motion window");
        } finally { sensors.stopMonitoring(); }
    }
    private AgentStore.Memory memoryById(AgentStore store, String id) throws Exception {
        for (AgentStore.Memory memory : store.listMemories()) if (id.equals(memory.id)) return memory;
        throw new IllegalStateException("Synthetic memory missing");
    }
    private void exportLifecycle(AgentStore store) throws Exception {
        reset(store);
        long expires = System.currentTimeMillis() + 86400000L;
        JSONObject event = new JSONObject().put("kind", "motion_change").put("from", "LowMotion").put("to", "MotionDetected")
            .put("observedAt", 123456789L).put("source", "accelerometer+gyroscope").put("method", "three_second_rule_window");
        AgentStore.Memory memory = store.confirmSensorObservation(event, "SYNTHETIC portable observation", expires);
        store.reviseMemory(memory, "SYNTHETIC corrected portable observation", expires);
        memory = memoryById(store, memory.id);
        byte[] archive = store.exportArchive("synthetic-memory-lifecycle".toCharArray());
        try (java.io.FileOutputStream out = openFileOutput("synthetic-migration.taos", 0)) { out.write(archive); }
        try (java.io.FileOutputStream out = openFileOutput("migration-ids.json", 0)) {
            out.write(new JSONObject().put("memoryId", memory.id).put("expiresAt", expires).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        check(memory.expiresAt == expires && memory.userEdited, "synthetic corrected lifecycle archive prepared");
    }
    private void importLifecycle(AgentStore store) throws Exception {
        reset(store);
        byte[] ids = java.nio.file.Files.readAllBytes(new java.io.File(getFilesDir(), "migration-ids.json").toPath());
        JSONObject metadata = new JSONObject(new String(ids, java.nio.charset.StandardCharsets.UTF_8));
        try (java.io.FileInputStream input = openFileInput("synthetic-migration.taos")) {
            store.importArchive(input, "synthetic-memory-lifecycle".toCharArray());
        }
        AgentStore.Memory memory = memoryById(store, metadata.getString("memoryId"));
        check(memory.expiresAt == metadata.getLong("expiresAt"), "new installation restores expiration");
        check(memory.sourceReference.startsWith("confirmed:sensor:motion:"), "new installation restores sensor provenance");
        check(memory.userEdited && "SYNTHETIC corrected portable observation".equals(memory.content), "new installation restores user correction provenance");
        check(store.listPlanningMemories().size() == 1, "unexpired restored memory can be selected");
        check(getContentResolver().getPersistedUriPermissions().isEmpty(), "archive carries no Android file permissions");
        check(!CloudModelConfig.load(this).isConfigured(), "archive carries no cloud configuration");
    }
    private void memoryLifecycle(AgentStore store) throws Exception {
        reset(store);
        AgentStore.Memory original = store.createMemory(AgentStore.MEMORY_PREFERENCE, "SYNTHETIC old preference");
        long expires = System.currentTimeMillis() + 60000;
        store.reviseMemory(original, "SYNTHETIC corrected preference", expires);
        AgentStore.Memory revised = memoryById(store, original.id);
        check(revised.expiresAt == expires && revised.source.equals(original.source) && revised.userEdited, "correction preserves source, marks the user edit and sets expiration");
        rejects(() -> store.reviseMemory(original, "SYNTHETIC stale edit", 0), "stale correction cannot overwrite a newer version");
        rejects(() -> store.reviseMemory(revised, "SYNTHETIC invalid expiry", -1), "negative expiration rejected");
        AgentStore.Task historical = store.createTask("SYNTHETIC history");
        store.savePlan(historical.id, new ExecutionPlan(historical.id, historical.title, Collections.emptyList(), 1, 0)
            .withContext(TaskContext.selected(Collections.singletonList(revised))));
        MemorySelection selection = new MemorySelection(store.listPlanningMemories(), Collections.singletonList(revised.id));
        store.reviseMemory(revised, "SYNTHETIC expired preference", System.currentTimeMillis() - 1);
        check(store.listPlanningMemories().isEmpty() && store.listMemories().size() == 1, "expired canonical memory remains visible but is excluded from new planning");
        rejects(() -> selection.chosen(store.listPlanningMemories()), "an already selected memory cannot enter a new request after expiry");
        check(TaskContext.memories(store.getTask(historical.id).plan).get(0).expiresAt == expires, "historical task retains its original snapshot and expiration");
        JSONObject event = new JSONObject().put("kind", "motion_change").put("from", "LowMotion").put("to", "MotionDetected")
            .put("observedAt", 123456789L).put("source", "accelerometer+gyroscope").put("method", "three_second_rule_window");
        check(store.listMemories().size() == 1, "an observation alone does not create a memory");
        AgentStore.Memory confirmed = store.confirmSensorObservation(event, "SYNTHETIC user-reviewed observation", expires);
        check(confirmed.sourceReference.equals("confirmed:sensor:motion:123456789") && confirmed.expiresAt == expires,
            "explicit confirmation records sensor provenance and expiration");
        AgentStore.Memory forged = new AgentStore.Memory("synthetic-forged", AgentStore.MEMORY_FACT, "SYNTHETIC user record",
            AgentStore.MEMORY_SOURCE_USER, "confirmed:sensor:motion:123456789", 1, 1);
        check(MemorySources.matches(confirmed, 3) && !MemorySources.matches(forged, 3),
            "sensor-only deletion preview cannot select a user record with a forged reference");
        rejects(() -> store.confirmSensorObservation(event, "SYNTHETIC duplicate", expires), "same observation cannot be saved twice");
        byte[] archive = store.exportArchive("synthetic-memory-lifecycle".toCharArray());
        reset(store);
        store.importArchive(new java.io.ByteArrayInputStream(archive), "synthetic-memory-lifecycle".toCharArray());
        check(memoryById(store, confirmed.id).expiresAt == expires && memoryById(store, original.id).isExpired(System.currentTimeMillis())
            && memoryById(store, original.id).userEdited, "archive restores expiry and user edit provenance");
        check(TaskContext.memories(store.getTask(historical.id).plan).get(0).expiresAt == expires, "archive preserves historical snapshot expiry");
        check(store.listPlanningMemories().size() == 1, "expired memory remains excluded after import");
        AgentStore.Memory stale = memoryById(store, confirmed.id), expired = memoryById(store, original.id);
        store.reviseMemory(stale, "SYNTHETIC changed after deletion preview", expires);
        rejects(() -> store.deleteMemoriesReviewed(java.util.Arrays.asList(stale, expired)), "bulk deletion rejects a changed selection atomically");
        check(store.listMemories().size() == 2, "failed reviewed deletion leaves every record intact");
        AgentStore.Memory latest = memoryById(store, confirmed.id);
        store.deleteMemoriesReviewed(Collections.singletonList(latest));
        check(store.listMemories().size() == 1 && store.listMemories().get(0).id.equals(original.id), "reviewed deletion removes only selected source records");
        byte[] deleted = store.exportArchive("synthetic-memory-lifecycle".toCharArray());
        check(MemoryArchive.decrypt(new java.io.ByteArrayInputStream(deleted), "synthetic-memory-lifecycle".toCharArray()).tombstones.size() == 1,
            "reviewed deletion exports a tombstone");
        AgentStore.Memory expiredWorkflow = store.createMemory(AgentStore.MEMORY_WORKFLOW,
            new WorkflowTemplate("SYNTHETIC expired workflow", "Plan {{input}}").encode(), AgentStore.MEMORY_SOURCE_USER, "", System.currentTimeMillis() - 1);
        rejects(() -> WorkflowTemplate.createRun(store, expiredWorkflow.id, "SYNTHETIC"), "expired workflow cannot create a new run");
        AgentStore.Task saved = store.getTask(historical.id);
        JSONObject badContext = new JSONObject(saved.plan.context);
        badContext.getJSONArray("memories").getJSONObject(0).put("expiresAt", 1.5);
        ExecutionPlan badPlan = saved.plan.withContext(badContext.toString());
        rejects(() -> TaskContext.memories(badPlan), "fractional snapshot expiration is rejected");
        MemoryArchive.Data badData = new MemoryArchive.Data();
        badData.tasks.add(new AgentStore.Task(saved.id, saved.title, saved.status, saved.createdAt, saved.updatedAt,
            saved.jobId, saved.executionVersion, saved.executionStep, saved.checkpointNote, saved.result, badPlan));
        byte[] badArchive = MemoryArchive.encrypt(badData, "synthetic-memory-lifecycle".toCharArray());
        rejects(() -> MemoryArchive.decrypt(new java.io.ByteArrayInputStream(badArchive), "synthetic-memory-lifecycle".toCharArray()),
            "malformed v6 snapshot is rejected before import");
    }
    private void check(boolean value, String message) { checks++; if (!value) throw new IllegalStateException(message); }

    private void localEngineLabels() throws Exception {
        LocalSlmEngine engine = new LocalSlmEngine();
        ExecutionPlan plan = engine.plan("synthetic", "SYNTHETIC note", Collections.singletonList(
            new CapabilityDescriptor(CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE, "note", "", true)), Collections.emptyList());
        for (ExecutionPlan.Step step : plan.steps) {
            check(!step.prompt.contains("SLM") && !step.title.contains("SLM"), "rule plan cannot claim model inference");
        }
        String result = engine.synthesize("synthetic", "SYNTHETIC", Collections.emptyList());
        check(!result.contains("SLM") && !result.contains("轻量模型"), "rule summary cannot claim a loaded language model");
    }

    private void scopedSummaryContract() throws Exception {
        JSONObject value = new JSONObject().put("provider", "traceform").put("available", false)
            .put("reason", "no_retained_history").put("initial_state", "")
            .put("sampled_occupancy_probabilities", new JSONArray()).put("history_records", 0)
            .put("eligible_pairs", 0).put("horizon_steps", 0).put("rollouts", 0)
            .put("eligible_for_intervention", false)
            .put("evidence_scope", "local_retained_history_simulation_not_validated_prediction_or_causal_effect");
        check(new JSONObject(TraceformProvider.validateScopedSummary(value.toString())).length() == 11,
            "narrow no-history response is accepted");
        rejects(() -> TraceformProvider.validateScopedSummary(new JSONObject(value.toString()).put("raw", "synthetic").toString()),
            "extra source data is rejected");
        rejects(() -> TraceformProvider.validateScopedSummary(new JSONObject(value.toString()).put("eligible_for_intervention", true).toString()),
            "summary cannot claim intervention authority");
        JSONObject excessive = new JSONObject(value.toString()).put("sampled_occupancy_probabilities", new JSONArray().put(
            new JSONObject().put("step", 1).put("top_states", new JSONArray()).put("raw", "synthetic")));
        rejects(() -> TraceformProvider.validateScopedSummary(excessive.toString()), "nested raw data is rejected");
    }

    private void cloudPlanGuard() throws Exception {
        JSONObject plan = new JSONObject().put("steps", new JSONArray().put(new JSONObject()
            .put("capability", "builtin.synthesis").put("scope", "local_inference")));
        JSONObject message = new JSONObject().put("content", plan.toString());
        JSONObject choice = new JSONObject().put("finish_reason", "length").put("message", message);
        JSONObject response = new JSONObject().put("choices", new JSONArray().put(choice));
        rejects(() -> CloudModelClient.parsePlanResponse("synthetic", "SYNTHETIC", response.toString(), Collections.emptyList()),
            "truncated response must not become an executable plan");
        choice.put("finish_reason", "stop");
        check(CloudModelClient.parsePlanResponse("synthetic", "SYNTHETIC", response.toString(), Collections.emptyList()).totalSteps() == 1,
            "normal complete plan remains accepted");
        message.put("tool_calls", new JSONArray());
        rejects(() -> CloudModelClient.parsePlanResponse("synthetic", "SYNTHETIC", response.toString(), Collections.emptyList()),
            "tool call output must not become an executable plan");
    }

    private void localModelDraftBoundary(AgentStore store) throws Exception {
        reset(store);
        AgentStore.Memory memory = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC selected context");
        check(new String(LocalModelDraft.prompt("SYNTHETIC goal", Collections.singletonList(memory)), java.nio.charset.StandardCharsets.UTF_8)
            .contains(memory.content), "explicitly selected context reaches the local draft prompt");
        StringBuilder large = new StringBuilder(); for (int i = 0; i < 5000; i++) large.append('x');
        rejects(() -> LocalModelDraft.prompt(large.toString(), Collections.emptyList()), "oversized prompt is rejected");
        AgentStore.Task task = store.createTask("SYNTHETIC local model goal");
        JSONObject context = new JSONObject(TaskContext.selected(Collections.singletonList(memory), true)).put("intent", "verified_document");
        store.savePlan(task.id, new ExecutionPlan(task.id, task.title, Collections.emptyList(), 1, 0).withContext(context.toString()));
        AgentStore.Task prepared = store.prepareLocalModelDocument(task.id, "# SYNTHETIC model proposal", TaskContext.fingerprint(store.getTask(task.id)));
        JSONObject draft = new JSONObject(prepared.plan.steps.get(0).observation);
        check(AgentStore.WAITING_FOR_USER.equals(prepared.status) && "on_device_model".equals(draft.getString("draftOrigin")),
            "on-device output stays an explicitly labelled draft");
        check(draft.getJSONArray("memorySources").length() == 1 && draft.optString("attemptId").isEmpty(),
            "draft records selected source without dispatching a write");
        rejects(() -> DocumentAction.writeOnce(getContentResolver(), store, task.id), "model output cannot authorize a file write");
        AgentStore.Task cloud = store.createTask("SYNTHETIC nonlocal task");
        JSONObject cloudContext = new JSONObject(TaskContext.selected(Collections.emptyList(), false)).put("intent", "verified_document");
        store.savePlan(cloud.id, new ExecutionPlan(cloud.id, cloud.title, Collections.emptyList(), 1, 0).withContext(cloudContext.toString()));
        rejects(() -> store.prepareLocalModelDocument(cloud.id, "# SYNTHETIC", TaskContext.fingerprint(store.getTask(cloud.id))),
            "nonlocal context cannot be labelled as an on-device proposal");
        AgentStore.Task cancelled = store.createTask("SYNTHETIC cancelled generation");
        store.savePlan(cancelled.id, new ExecutionPlan(cancelled.id, cancelled.title, Collections.emptyList(), 1, 0).withContext(context.toString()));
        String fingerprint = TaskContext.fingerprint(store.getTask(cancelled.id));
        store.transition(cancelled.id, AgentStore.CANCELLED);
        rejects(() -> store.prepareLocalModelDocument(cancelled.id, "# SYNTHETIC", fingerprint), "cancelled generation cannot persist a draft");
    }

    private void verifyLocalModelUi(AgentStore store, boolean cancelled) throws Exception {
        AgentStore.Task found = null;
        for (AgentStore.Task task : store.listTasks())
            if ("SYNTHETIC local model checklist".equals(task.title)) found = task;
        check(found != null && TaskContext.localOnly(found.plan) && TaskContext.memories(found.plan).isEmpty(),
            "synthetic UI run remains local and has no selected memory");
        if (cancelled) {
            check(AgentStore.CANCELLED.equals(found.status), "UI cancellation is persisted; got " + found.status);
        } else {
            check(AgentStore.WAITING_FOR_USER.equals(found.status) && found.plan.totalSteps() == 1,
                "model output remains a draft awaiting review");
            JSONObject draft = new JSONObject(found.plan.steps.get(0).observation);
            check("on_device_model".equals(draft.getString("draftOrigin")) && draft.optString("attemptId").isEmpty(),
                "model origin is shown and no file action was dispatched");
        }
    }

    private void shareEscapedContent(AgentStore store) throws Exception {
        reset(store);
        String text = new String(new char[7000]).replace('\0', '"');
        AgentStore.Task parent = store.createDocumentTask("SYNTHETIC escaped document", text, new JSONArray());
        JSONObject draft = new JSONObject(parent.plan.steps.get(0).observation);
        String attempt = store.claimExternalAction(parent.id, 0, draft.getString("draftId"), draft);
        Uri uri = Uri.parse("content://" + getPackageName() + ".documents.fixture/escaped_share");
        store.bindDocumentTarget(parent.id, attempt, uri.toString());
        DocumentAction.writeOnce(getContentResolver(), store, parent.id);
        String verified = DocumentAction.readVerifiedContent(getContentResolver(), store.getTask(parent.id));
        check(text.equals(verified), "valid escaped content saves and reads back");
        AgentStore.Task child = store.createShareFollowup(parent.id, verified, uri.toString());
        JSONObject share = new JSONObject(child.plan.steps.get(0).observation);
        JSONObject oversized = new JSONObject(share.toString()).put("extra", new String(new char[128001]).replace('\0', 'x'));
        rejects(() -> store.claimExternalAction(child.id, 0, oversized.getString("draftId"), oversized), "oversized encoded share remains rejected");
        String handoff = store.claimExternalAction(child.id, 0, share.getString("draftId"), share);
        store.recordExternalOutcome(child.id, 0, handoff, false, "synthetic");
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(child.id).status), "escaped share content can be confirmed without duplicate handoff");
        byte[] archive = store.exportArchive("synthetic-escaped-share".toCharArray());
        reset(store);
        store.importArchive(new java.io.ByteArrayInputStream(archive), "synthetic-escaped-share".toCharArray());
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(child.id).status), "escaped share task survives archive restore");
    }

    private static final String BRANCH_PASSWORD = "synthetic-offline-branch-passphrase";
    private void writeBranchFile(String name, byte[] bytes) throws Exception {
        try (java.io.FileOutputStream out = openFileOutput(name, 0)) { out.write(bytes); }
    }
    private JSONObject branchIds() throws Exception {
        return new JSONObject(new String(java.nio.file.Files.readAllBytes(new java.io.File(getFilesDir(), "branch-ids.json").toPath()), java.nio.charset.StandardCharsets.UTF_8));
    }
    private byte[] branchFile(String name) throws Exception {
        return java.nio.file.Files.readAllBytes(new java.io.File(getFilesDir(), name).toPath());
    }
    private void memoryBranchSeed(AgentStore store) throws Exception {
        reset(store);
        AgentStore.Memory conflict = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC common version");
        AgentStore.Memory deleted = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC old deleted content");
        writeBranchFile("branch-ids.json", new JSONObject().put("conflict", conflict.id).put("deleted", deleted.id).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writeBranchFile("branch-initial.taos", store.exportArchive(BRANCH_PASSWORD.toCharArray()));
        check(store.listMemories().size() == 2, "source initial isolated memories created");
    }
    private void memoryBranchTargetEdit(AgentStore store) throws Exception {
        reset(store);
        store.importArchive(new java.io.ByteArrayInputStream(branchFile("branch-initial.taos")), BRANCH_PASSWORD.toCharArray());
        JSONObject ids = branchIds();
        store.reviseMemory(memoryById(store, ids.getString("conflict")), "SYNTHETIC target offline edit", 0);
        check(store.listMemories().size() == 2, "target imported baseline and edited while offline");
    }
    private void memoryBranchSourceEdit(AgentStore store) throws Exception {
        JSONObject ids = branchIds();
        store.reviseMemory(memoryById(store, ids.getString("conflict")), "SYNTHETIC source offline edit", 0);
        store.deleteMemory(ids.getString("deleted"));
        writeBranchFile("branch-source.taos", store.exportArchive(BRANCH_PASSWORD.toCharArray()));
        check(store.listMemories().size() == 1, "source offline edit and deletion recorded");
    }
    private void memoryBranchTargetResolve(AgentStore store) throws Exception {
        JSONObject ids = branchIds(); String conflict = ids.getString("conflict"), deleted = ids.getString("deleted");
        AgentStore.ArchiveReview review = store.reviewArchive(new java.io.ByteArrayInputStream(branchFile("branch-source.taos")), BRANCH_PASSWORD.toCharArray());
        check(review.conflicts.size() == 1 && review.conflicts.containsKey(conflict), "offline edits are presented for explicit choice");
        check("SYNTHETIC source offline edit".equals(review.conflicts.get(conflict).incoming.content)
            && "SYNTHETIC target offline edit".equals(review.conflicts.get(conflict).local.content), "both alternatives are retained for review");
        store.importReviewedArchive(review, java.util.Collections.singletonMap(conflict, false));
        check("SYNTHETIC target offline edit".equals(memoryById(store, conflict).content) && store.listMemories().size() == 1, "chosen target edit survives and deletion propagates");
        store.importArchive(new java.io.ByteArrayInputStream(branchFile("branch-initial.taos")), BRANCH_PASSWORD.toCharArray());
        check(store.listMemories().size() == 1 && store.listMemories().get(0).id.equals(conflict), "old archive cannot revive deleted memory");
        writeBranchFile("branch-target.taos", store.exportArchive(BRANCH_PASSWORD.toCharArray()));
        check(!conflict.equals(deleted), "deleted record is distinct");
    }
    private void memoryBranchSourceResolve(AgentStore store) throws Exception {
        JSONObject ids = branchIds(); String conflict = ids.getString("conflict");
        AgentStore.ArchiveReview review = store.reviewArchive(new java.io.ByteArrayInputStream(branchFile("branch-target.taos")), BRANCH_PASSWORD.toCharArray());
        check(review.conflicts.size() == 1 && review.conflicts.containsKey(conflict), "source explicitly reviews target decision");
        store.importReviewedArchive(review, java.util.Collections.singletonMap(conflict, true));
        check("SYNTHETIC target offline edit".equals(memoryById(store, conflict).content) && store.listMemories().size() == 1, "source converges to reviewed target edit without restoring deletion");
        store.importArchive(new java.io.ByteArrayInputStream(branchFile("branch-initial.taos")), BRANCH_PASSWORD.toCharArray());
        check(store.listMemories().size() == 1, "source also rejects old deleted content");
    }

    private void documentShareChain(AgentStore store) throws Exception {
        reset(store);
        WorkflowTemplate recipe = new WorkflowTemplate("SYNTHETIC share", "Prepare {{input}}", false, true);
        AgentStore.Memory template = store.createMemory(AgentStore.MEMORY_WORKFLOW, recipe.encode());
        check(WorkflowTemplate.decode(template).shareFollowup, "share recipe survives encoding");
        AgentStore.Task run = WorkflowTemplate.createRun(store, template.id, "SYNTHETIC report");
        check("share_draft".equals(new JSONObject(run.plan.context).getJSONObject("workflow").getJSONArray("steps").getString(1)), "share step retained in snapshot");
        AgentStore.Task parent = store.prepareReviewedDocument(run.id, "# SYNTHETIC approved content", TaskContext.fingerprint(run), "");
        AgentStore.Task unverified = parent;
        rejects(() -> DocumentAction.readVerifiedContent(getContentResolver(), unverified), "unverified document cannot be shared");
        JSONObject draft = new JSONObject(parent.plan.steps.get(0).observation);
        String attempt = store.claimExternalAction(parent.id, 0, draft.getString("draftId"), draft);
        Uri uri = Uri.parse("content://" + getPackageName() + ".documents.fixture/share_chain");
        store.bindDocumentTarget(parent.id, attempt, uri.toString());
        DocumentAction.writeOnce(getContentResolver(), store, parent.id);
        parent = store.getTask(parent.id);
        String content = DocumentAction.readVerifiedContent(getContentResolver(), parent);
        check("# SYNTHETIC approved content".equals(content), "file is read and verified before share");
        String parentId = parent.id;
        rejects(() -> store.createShareFollowup(parentId, "SYNTHETIC altered", uri.toString()), "changed content rejected");
        rejects(() -> store.createShareFollowup(parentId, content, uri.toString() + "_other"), "stale selected URI rejected");
        AgentStore.Task child = store.createShareFollowup(parent.id, content, uri.toString());
        check(child.id.equals(store.createShareFollowup(parent.id, content, uri.toString()).id), "share draft creation is idempotent");
        JSONObject share = new JSONObject(child.plan.steps.get(0).observation);
        check(AgentStore.WAITING_FOR_USER.equals(child.status) && content.equals(share.getString("content")), "verified content awaits separate share confirmation");
        check(TaskContext.localOnly(child.plan) && TaskContext.memories(child.plan).isEmpty() && !child.plan.context.contains("content://"), "share inherits no file grant or memory selection");
        store.transition(child.id, AgentStore.CANCELLED);
        AgentStore.Task retry = store.createShareFollowup(parent.id, content, uri.toString());
        check(!child.id.equals(retry.id), "cancelled draft may be recreated without sending");
        share = new JSONObject(retry.plan.steps.get(0).observation);
        String handoff = store.claimExternalAction(retry.id, 0, share.getString("draftId"), share);
        store.recordExternalOutcome(retry.id, 0, handoff, false, "synthetic");
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(retry.id).status) && retry.id.equals(store.createShareFollowup(parent.id, content, uri.toString()).id), "unknown handoff is not resent");
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) { out.write("SYNTHETIC modified".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        AgentStore.Task saved = parent;
        rejects(() -> DocumentAction.readVerifiedContent(getContentResolver(), saved), "modified file rejected before new share");
        byte[] archive = store.exportArchive("synthetic-share-migration".toCharArray());
        reset(store);
        store.importArchive(new java.io.ByteArrayInputStream(archive), "synthetic-share-migration".toCharArray());
        check(WorkflowTemplate.decode(memoryById(store, template.id)).shareFollowup, "archive restores share workflow recipe");
        AgentStore.Task imported = store.getTask(parent.id);
        check("share_draft".equals(new JSONObject(imported.plan.context).getJSONObject("workflow").getJSONArray("steps").getString(1)), "archive restores workflow snapshot");
        check(!DocumentAction.locallyVerified(imported) && !new JSONObject(imported.plan.steps.get(0).observation).has("uri"), "imported receipt inherits no file access");
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(retry.id).status), "archive preserves unknown share result");
        getSharedPreferences("workflow_validation", 0).edit().putString("shareRecipe", template.id)
            .putString("shareParent", parent.id).putString("shareChild", retry.id).commit();
    }

    private void exportShareMigration(AgentStore store) throws Exception {
        documentShareChain(store);
        android.content.SharedPreferences prefs = getSharedPreferences("workflow_validation", 0);
        JSONObject ids = new JSONObject().put("recipe", prefs.getString("shareRecipe", ""))
            .put("parent", prefs.getString("shareParent", "")).put("child", prefs.getString("shareChild", ""));
        try (java.io.FileOutputStream out = openFileOutput("share-migration.taos", 0)) {
            out.write(store.exportArchive("synthetic-share-migration".toCharArray()));
        }
        try (java.io.FileOutputStream out = openFileOutput("share-migration-ids.json", 0)) {
            out.write(ids.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void importShareMigration(AgentStore store) throws Exception {
        reset(store);
        try (java.io.FileInputStream in = openFileInput("share-migration.taos")) {
            store.importArchive(in, "synthetic-share-migration".toCharArray());
        }
        byte[] data = java.nio.file.Files.readAllBytes(new java.io.File(getFilesDir(), "share-migration-ids.json").toPath());
        JSONObject ids = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        getSharedPreferences("workflow_validation", 0).edit().putString("shareRecipe", ids.getString("recipe"))
            .putString("shareParent", ids.getString("parent")).putString("shareChild", ids.getString("child")).commit();
        verifyShareRestart(store);
        check(getContentResolver().getPersistedUriPermissions().isEmpty(), "new installation has no inherited file grants");
        check(!CloudModelConfig.load(this).isConfigured(), "new installation has no inherited cloud configuration");
    }

    private void seedShareUi(AgentStore store) throws Exception {
        reset(store);
        getSharedPreferences("agent_os_prefs", 0).edit().putString("app_lang", "en").commit();
        AgentStore.Memory recipe = store.createMemory(AgentStore.MEMORY_WORKFLOW,
            new WorkflowTemplate("SYNTHETIC share UI", "Prepare {{input}}", false, true).encode());
        AgentStore.Task run = WorkflowTemplate.createRun(store, recipe.id, "SYNTHETIC UI checklist");
        AgentStore.Task parent = store.prepareReviewedDocument(run.id, "# SYNTHETIC UI checklist\n- item", TaskContext.fingerprint(run), "");
        JSONObject draft = new JSONObject(parent.plan.steps.get(0).observation);
        String attempt = store.claimExternalAction(parent.id, 0, draft.getString("draftId"), draft);
        Uri uri = Uri.parse("content://" + getPackageName() + ".documents.fixture/share_ui");
        store.bindDocumentTarget(parent.id, attempt, uri.toString());
        DocumentAction.writeOnce(getContentResolver(), store, parent.id);
        getSharedPreferences("workflow_validation", 0).edit().putString("shareUiParent", parent.id).commit();
        check(DocumentAction.locallyVerified(store.getTask(parent.id)), "synthetic workflow document ready for UI review");
    }

    private void verifyShareRestart(AgentStore store) throws Exception {
        android.content.SharedPreferences prefs = getSharedPreferences("workflow_validation", 0);
        AgentStore.Memory recipe = memoryById(store, prefs.getString("shareRecipe", ""));
        check(WorkflowTemplate.decode(recipe).shareFollowup, "share recipe persists after force-stop");
        AgentStore.Task parent = store.getTask(prefs.getString("shareParent", ""));
        AgentStore.Task child = store.getTask(prefs.getString("shareChild", ""));
        check(parent != null && !DocumentAction.locallyVerified(parent), "imported file access remains unavailable after restart");
        check(child != null && AgentStore.OUTCOME_UNKNOWN.equals(child.status), "unknown share handoff persists after restart");
        rejects(() -> store.createShareFollowup(parent.id, "# SYNTHETIC approved content", "content://synthetic/old"), "restart cannot restore a file grant or resend");
        AgentStore.Task fresh = WorkflowTemplate.createRun(store, recipe.id, "SYNTHETIC fresh run");
        check(TaskContext.localOnly(fresh.plan) && TaskContext.memories(fresh.plan).isEmpty(), "restored recipe starts without inherited permissions");
    }

    private void documentCalendarChain(AgentStore store) throws Exception {
        reset(store);
        AgentStore.Task parent = store.createDocumentTask("SYNTHETIC schedule", "# SYNTHETIC schedule", new JSONArray());
        rejects(() -> store.createCalendarFollowup(parent.id), "unverified document cannot create followup");
        JSONObject draft = new JSONObject(parent.plan.steps.get(0).observation);
        String attempt = store.claimExternalAction(parent.id, 0, draft.getString("draftId"), draft);
        Uri uri = Uri.parse("content://" + getPackageName() + ".documents.fixture/chain");
        store.bindDocumentTarget(parent.id, attempt, uri.toString());
        DocumentAction.writeOnce(getContentResolver(), store, parent.id);
        check(DocumentAction.locallyVerified(store.getTask(parent.id)), "parent document verified");
        AgentStore.Task child = store.createCalendarFollowup(parent.id);
        check(child.id.equals(store.createCalendarFollowup(parent.id).id), "followup creation is idempotent");
        store.transition(child.id, AgentStore.CANCELLED);
        AgentStore.Task retry = store.createCalendarFollowup(parent.id);
        check(!child.id.equals(retry.id) && AgentStore.CANCELLED.equals(store.getTask(child.id).status),
            "cancelled before handoff permits a new draft while preserving history");
        child = retry;
        check(child.id.equals(store.createCalendarFollowup(parent.id).id), "replacement draft is idempotent");
        check(AgentStore.WAITING_FOR_USER.equals(child.status) && "external".equals(TaskCenterState.destination(child)), "followup awaits separate confirmation");
        JSONObject context = new JSONObject(child.plan.context);
        check(TaskContext.localOnly(child.plan) && TaskContext.memories(child.plan).isEmpty() && !context.toString().contains("content://"), "no memory, file grant or cloud consent is inherited");
        check(parent.id.equals(context.getJSONObject("workflowLink").getString("parentTaskId")), "parent link is recorded");
        JSONObject calendar = new JSONObject(child.plan.steps.get(0).observation);
        calendar.put("startTime", System.currentTimeMillis() + 3600000).put("endTime", System.currentTimeMillis() + 7200000);
        String handoff = store.claimExternalAction(child.id, 0, calendar.getString("draftId"), calendar);
        store.recordExternalOutcome(child.id, 0, handoff, false, "synthetic");
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(child.id).status), "external save is not falsely verified");
        check(child.id.equals(store.createCalendarFollowup(parent.id).id), "unknown result does not create a duplicate");
        AgentStore.Task other = store.createDocumentTask("SYNTHETIC cancel after handoff", "# SYNTHETIC", new JSONArray());
        JSONObject otherDraft = new JSONObject(other.plan.steps.get(0).observation);
        String otherAttempt = store.claimExternalAction(other.id, 0, otherDraft.getString("draftId"), otherDraft);
        store.bindDocumentTarget(other.id, otherAttempt, Uri.parse("content://" + getPackageName() + ".documents.fixture/chain_cancel").toString());
        DocumentAction.writeOnce(getContentResolver(), store, other.id);
        AgentStore.Task handedOff = store.createCalendarFollowup(other.id);
        JSONObject otherCalendar = new JSONObject(handedOff.plan.steps.get(0).observation);
        otherCalendar.put("startTime", System.currentTimeMillis() + 3600000).put("endTime", System.currentTimeMillis() + 7200000);
        store.claimExternalAction(handedOff.id, 0, otherCalendar.getString("draftId"), otherCalendar);
        store.transition(handedOff.id, AgentStore.CANCELLED);
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(handedOff.id).status)
                && handedOff.id.equals(store.createCalendarFollowup(other.id).id),
            "cancellation after handoff remains unknown and cannot create a duplicate");
        getSharedPreferences("workflow_validation", 0).edit().putString("chainParent", parent.id).putString("chainChild", child.id).commit();
    }

    private void verifyChainRestart(AgentStore store) throws Exception {
        String parent = getSharedPreferences("workflow_validation", 0).getString("chainParent", "");
        String child = getSharedPreferences("workflow_validation", 0).getString("chainChild", "");
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(child).status), "unknown handoff survives force stop");
        check(child.equals(store.createCalendarFollowup(parent).id), "restart cannot replay or duplicate the action");
    }
    private void seedCalendarRetryUi(AgentStore store) throws Exception {
        reset(store);
        getSharedPreferences("agent_os_prefs", 0).edit().putString("app_lang", "en").commit();
        AgentStore.Task parent = store.createDocumentTask("SYNTHETIC retry UI", "# SYNTHETIC", new JSONArray());
        JSONObject draft = new JSONObject(parent.plan.steps.get(0).observation);
        String attempt = store.claimExternalAction(parent.id, 0, draft.getString("draftId"), draft);
        store.bindDocumentTarget(parent.id, attempt, Uri.parse("content://" + getPackageName() + ".documents.fixture/retry_ui").toString());
        DocumentAction.writeOnce(getContentResolver(), store, parent.id);
        check(DocumentAction.locallyVerified(store.getTask(parent.id)), "synthetic parent verified");
        getSharedPreferences("workflow_validation", 0).edit().putString("retryUiParent", parent.id).commit();
    }
    private void verifyCalendarRetryUi(AgentStore store) throws Exception {
        String parent = getSharedPreferences("workflow_validation", 0).getString("retryUiParent", "");
        int cancelled = 0, waiting = 0;
        for (AgentStore.Task task : store.listTasks()) {
            if (task.plan == null || task.plan.context.isEmpty()) continue;
            JSONObject link = new JSONObject(task.plan.context).optJSONObject("workflowLink");
            if (link == null || !parent.equals(link.optString("parentTaskId"))) continue;
            if (AgentStore.CANCELLED.equals(task.status)) cancelled++;
            if (AgentStore.WAITING_FOR_USER.equals(task.status)
                    && ExecutionPlan.STEP_DRAFT_READY.equals(task.plan.steps.get(0).status)
                    && new JSONObject(task.plan.steps.get(0).observation).optString("attemptId").isEmpty()) waiting++;
        }
        check(cancelled == 1 && waiting == 1, "UI cancellation preserves history and opens one fresh unhanded draft");
    }
    private interface Checked { void run() throws Exception; }
    private void rejects(Checked action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | SecurityException | org.json.JSONException | java.io.IOException expected) { rejected = true; }
        check(rejected, message);
    }
    private void workflows(AgentStore store) throws Exception {
        reset(store);
        WorkflowTemplate recipe = new WorkflowTemplate("SYNTHETIC checklist", "Prepare a local Markdown checklist for {{input}}.");
        AgentStore.Memory template = store.createMemory(AgentStore.MEMORY_WORKFLOW, recipe.encode());
        WorkflowTemplate chained = new WorkflowTemplate("SYNTHETIC file and calendar", "Prepare {{input}}.", true);
        AgentStore.Memory chainTemplate = store.createMemory(AgentStore.MEMORY_WORKFLOW, chained.encode());
        check(WorkflowTemplate.decode(chainTemplate).calendarFollowup, "ordered file and calendar recipe survives encoding");
        AgentStore.Task chainRun = WorkflowTemplate.createRun(store, chainTemplate.id, "SYNTHETIC meeting");
        JSONArray planned = new JSONObject(chainRun.plan.context).getJSONObject("workflow").getJSONArray("steps");
        check(planned.length() == 2 && "verified_document".equals(planned.getString(0)) && "calendar_draft".equals(planned.getString(1)), "new run retains ordered steps");
        check(TaskContext.localOnly(chainRun.plan) && TaskContext.memories(chainRun.plan).isEmpty(), "new chain inherits no memory or cloud consent");
        JSONObject alteredChain = new JSONObject(chained.encode()).put("steps", new JSONArray().put("calendar_draft").put("verified_document"));
        AgentStore.Memory badChain = store.createMemory(AgentStore.MEMORY_WORKFLOW, alteredChain.toString());
        rejects(() -> WorkflowTemplate.decode(badChain), "unordered or unsupported chain cannot execute");
        JSONObject grantedChain = new JSONObject(chained.encode()).put("uri", "content://unapproved/file");
        AgentStore.Memory badGrant = store.createMemory(AgentStore.MEMORY_WORKFLOW, grantedChain.toString());
        rejects(() -> WorkflowTemplate.decode(badGrant), "recipe cannot carry an inherited file grant");
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
        check(WorkflowTemplate.decode(memoryById(store, chainTemplate.id)).calendarFollowup,
            "ordered workflow recipe survives archive restore");
        check(new JSONObject(store.getTask(chainRun.id).plan.context).getJSONObject("workflow").getJSONArray("steps").length() == 2,
            "restored run retains ordered workflow snapshot");
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
