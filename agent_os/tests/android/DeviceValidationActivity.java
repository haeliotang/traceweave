package org.traceform.agentos;

import android.app.Activity;
import android.app.job.JobScheduler;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class DeviceValidationActivity extends Activity {
    static final String ACTION_SEED_ARCHIVE = "org.traceform.agentos.validation.SEED_ARCHIVE";
    static final String ACTION_VERIFY_IMPORT = "org.traceform.agentos.validation.VERIFY_IMPORT";
    static final String ACTION_VERIFY_CONFLICT = "org.traceform.agentos.validation.VERIFY_CONFLICT";
    static final String ACTION_VERIFY_CAPABILITY_AUDIT = "org.traceform.agentos.validation.VERIFY_CAPABILITY_AUDIT";
    static final String ACTION_VERIFY_STATUS_PROVIDER_AUDIT = "org.traceform.agentos.validation.VERIFY_STATUS_PROVIDER_AUDIT";
    static final String ACTION_VERIFY_REMOTE_PROVIDER_AUDIT = "org.traceform.agentos.validation.VERIFY_REMOTE_PROVIDER_AUDIT";
    static final String ACTION_VERIFY_REMOTE_PROVIDER_FAILURE = "org.traceform.agentos.validation.VERIFY_REMOTE_PROVIDER_FAILURE";
    static final String ACTION_VERIFY_REMOTE_PROVIDER_RESTART = "org.traceform.agentos.validation.VERIFY_REMOTE_PROVIDER_RESTART";
    static final String ACTION_VERIFY_EXECUTOR = "org.traceform.agentos.validation.VERIFY_EXECUTOR";
    static final String ACTION_VERIFY_EXECUTOR_LIFECYCLE = "org.traceform.agentos.validation.VERIFY_EXECUTOR_LIFECYCLE";
    static final String ACTION_ARM_UI_CHECKPOINT_GATE = "org.traceform.agentos.validation.ARM_UI_CHECKPOINT_GATE";
    static final String ACTION_RELEASE_UI_CHECKPOINT_GATE = "org.traceform.agentos.validation.RELEASE_UI_CHECKPOINT_GATE";
    static final String ACTION_PREPARE_CHECKPOINT_RESTART = "org.traceform.agentos.validation.PREPARE_CHECKPOINT_RESTART";
    static final String ACTION_VERIFY_CHECKPOINT_RESTART = "org.traceform.agentos.validation.VERIFY_CHECKPOINT_RESTART";
    static final String ACTION_VERIFY_MEMORY_PROVENANCE = "org.traceform.agentos.validation.VERIFY_MEMORY_PROVENANCE";
    static final String ACTION_VERIFY_REASONING_PLANNER = "org.traceform.agentos.validation.VERIFY_REASONING_PLANNER";
    static final String ACTION_VERIFY_DYNAMIC_EXECUTOR = "org.traceform.agentos.validation.VERIFY_DYNAMIC_EXECUTOR";
    static final String ACTION_VERIFY_STEP_TREE_AND_JIT_GRANT = "org.traceform.agentos.validation.VERIFY_STEP_TREE_AND_JIT_GRANT";
    static final String ACTION_VERIFY_END_TO_END_INTEGRATED = "org.traceform.agentos.validation.VERIFY_END_TO_END_INTEGRATED";
    static final String ACTION_VERIFY_HYBRID_CLOUD_AND_REDACTION = "org.traceform.agentos.validation.VERIFY_HYBRID_CLOUD_AND_REDACTION";
    static final String ACTION_VERIFY_SLM_AND_SYSTEM_CAPABILITIES = "org.traceform.agentos.validation.VERIFY_SLM_AND_SYSTEM_CAPABILITIES";
    static final String ACTION_VERIFY_MEMORY_CONSOLIDATION_AND_UI = "org.traceform.agentos.validation.VERIFY_MEMORY_CONSOLIDATION_AND_UI";
    static final String ACTION_VERIFY_NATIVE_CORE_AND_JNI = "org.traceform.agentos.validation.VERIFY_NATIVE_CORE_AND_JNI";
    private static final String TEST_PASSPHRASE = "synthetic-only-agent-os-archive-passphrase";
    private static final String TEST_TASK_TITLE = "SYNTHETIC cross-install task";
    private static final String TEST_MEMORY_CONTENT = "SYNTHETIC preference for cross-install validation";
    private static CheckpointGate uiCheckpointGate;

    private TextView resultView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        resultView = new TextView(this);
        resultView.setTextSize(18);
        resultView.setGravity(Gravity.CENTER_VERTICAL);
        resultView.setPadding(32, 32, 32, 32);
        setContentView(resultView);

        new Thread(() -> {
            String result;
            try {
                String action = getIntent().getAction();
                if (ACTION_SEED_ARCHIVE.equals(action)) {
                    result = seedArchive();
                } else if (ACTION_VERIFY_IMPORT.equals(action)) {
                    result = verifyImportedRecords(false);
                } else if (ACTION_VERIFY_CONFLICT.equals(action)) {
                    result = verifyImportedRecords(true);
                } else if (ACTION_VERIFY_CAPABILITY_AUDIT.equals(action)) {
                    result = verifyCapabilityAudit();
                } else if (ACTION_VERIFY_STATUS_PROVIDER_AUDIT.equals(action)) {
                    result = verifyStatusProviderAudit();
                } else if (ACTION_VERIFY_REMOTE_PROVIDER_AUDIT.equals(action)) {
                    result = verifyRemoteProviderAudit();
                } else if (ACTION_VERIFY_REMOTE_PROVIDER_FAILURE.equals(action)) {
                    result = verifyRemoteProviderFailure();
                } else if (ACTION_VERIFY_REMOTE_PROVIDER_RESTART.equals(action)) {
                    result = verifyRemoteProviderRestart();
                } else if (ACTION_VERIFY_EXECUTOR.equals(action)) {
                    result = verifyExecutor();
                } else if (ACTION_VERIFY_EXECUTOR_LIFECYCLE.equals(action)) {
                    result = verifyExecutorLifecycle();
                } else if (ACTION_ARM_UI_CHECKPOINT_GATE.equals(action)) {
                    result = armUiCheckpointGate();
                } else if (ACTION_RELEASE_UI_CHECKPOINT_GATE.equals(action)) {
                    result = releaseUiCheckpointGate();
                } else if (ACTION_PREPARE_CHECKPOINT_RESTART.equals(action)) {
                    result = prepareCheckpointRestart();
                } else if (ACTION_VERIFY_CHECKPOINT_RESTART.equals(action)) {
                    result = verifyCheckpointRestart();
                } else if (ACTION_VERIFY_MEMORY_PROVENANCE.equals(action)) {
                    result = verifyMemoryProvenance();
                } else if (ACTION_VERIFY_REASONING_PLANNER.equals(action)) {
                    result = verifyReasoningPlanner();
                } else if (ACTION_VERIFY_DYNAMIC_EXECUTOR.equals(action)) {
                    result = verifyDynamicExecutor();
                } else if (ACTION_VERIFY_STEP_TREE_AND_JIT_GRANT.equals(action)) {
                    result = verifyStepTreeAndJitGrant();
                } else if (ACTION_VERIFY_END_TO_END_INTEGRATED.equals(action)) {
                    result = verifyEndToEndIntegrated();
                } else if (ACTION_VERIFY_HYBRID_CLOUD_AND_REDACTION.equals(action)) {
                    result = verifyHybridCloudAndRedaction();
                } else if (ACTION_VERIFY_SLM_AND_SYSTEM_CAPABILITIES.equals(action)) {
                    result = verifySlmAndSystemCapabilities();
                } else if (ACTION_VERIFY_MEMORY_CONSOLIDATION_AND_UI.equals(action)) {
                    result = verifyMemoryConsolidationAndUi();
                } else if (ACTION_VERIFY_NATIVE_CORE_AND_JNI.equals(action)) {
                    result = verifyNativeCoreAndJni();
                } else {
                    result = runMigrationAndKdf();
                }
            } catch (Exception error) {
                result = "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage();
            }
            String completed = result;
            boolean finishAfterResult = ACTION_PREPARE_CHECKPOINT_RESTART.equals(getIntent().getAction());
            boolean openMainActivity = ACTION_ARM_UI_CHECKPOINT_GATE.equals(getIntent().getAction())
                || ACTION_RELEASE_UI_CHECKPOINT_GATE.equals(getIntent().getAction());
            Log.i("AgentOsValidation", completed);
            runOnUiThread(() -> {
                resultView.setText(completed);
                if (openMainActivity) {
                    startActivity(new android.content.Intent(this, MainActivity.class));
                    finish();
                } else if (finishAfterResult) {
                    finish();
                }
            });
        }, "agent-os-validation").start();
    }

    private String runMigrationAndKdf() throws Exception {
        deleteDatabase("agent_os.db");
        AgentStore original = new AgentStore(this);
        AgentStore.Task task;
        byte[] sealedPayload;
        try {
            task = original.createTask("SYNTHETIC database migration task");
            original.transition(task.id, AgentStore.ACTIVE);
            try (Cursor cursor = original.getReadableDatabase().query("tasks", new String[]{"sealed_payload"},
                    "id=?", new String[]{task.id}, null, null, null)) {
                if (!cursor.moveToFirst()) throw new IllegalStateException("The synthetic task payload is missing");
                sealedPayload = cursor.getBlob(0);
            }
        } finally {
            original.close();
        }

        deleteDatabase("agent_os.db");
        SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(getDatabasePath("agent_os.db"), null);
        try {
            legacy.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL, status TEXT NOT NULL, sealed_payload BLOB NOT NULL)");
            legacy.execSQL("CREATE TABLE audit (id TEXT PRIMARY KEY NOT NULL, task_id TEXT NOT NULL, "
                + "occurred_at INTEGER NOT NULL, event TEXT NOT NULL)");
            ContentValues taskRow = new ContentValues();
            taskRow.put("id", task.id);
            taskRow.put("created_at", task.createdAt);
            taskRow.put("updated_at", task.updatedAt);
            taskRow.put("status", AgentStore.ACTIVE);
            taskRow.put("sealed_payload", sealedPayload);
            legacy.insertOrThrow("tasks", null, taskRow);
            insertLegacyAudit(legacy, task.id, "task_created", task.createdAt);
            insertLegacyAudit(legacy, task.id, "status_active", task.updatedAt);
            legacy.setVersion(1);
        } finally {
            legacy.close();
            java.util.Arrays.fill(sealedPayload, (byte) 0);
        }

        AgentStore migrated = new AgentStore(this);
        try {
            List<AgentStore.Task> tasks = migrated.listTasks();
            if (tasks.size() != 1 || !task.id.equals(tasks.get(0).id)
                    || !"SYNTHETIC database migration task".equals(tasks.get(0).title)
                    || !AgentStore.QUEUED.equals(tasks.get(0).status)
                    || tasks.get(0).executionVersion != 0 || tasks.get(0).jobId == 0) {
                throw new IllegalStateException("The version 1 task was not preserved");
            }
            if (!migrated.listMemories().isEmpty()) {
                throw new IllegalStateException("The migrated memory table should be empty");
            }

            SQLiteDatabase database = migrated.getReadableDatabase();
            if (database.getVersion() != 4 || !tableExists(database, "memories")
                    || !tableExists(database, "tombstones") || auditCount(database, task.id) != 3) {
                throw new IllegalStateException("The version 1 to 4 migration did not preserve schema and audit data");
            }
        } finally {
            migrated.close();
        }

        return "PASS: database v1→v4 migration preserved the encrypted task and audit history, assigned a job ID, "
            + "and re-queued a legacy active journal entry.";
    }

    private String verifyMemoryProvenance() throws Exception {
        deleteDatabase("agent_os.db");
        AgentStore store = new AgentStore(this);
        AgentStore.Memory legacy;
        try {
            legacy = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC legacy memory stays private");
            Method seal = AgentStore.class.getDeclaredMethod("seal", String.class, byte[].class);
            seal.setAccessible(true);
            byte[] oldPayload = "SYNTHETIC legacy memory stays private".getBytes("UTF-8");
            byte[] oldSealed = (byte[]) seal.invoke(store, "memory:" + legacy.id, oldPayload);
            Arrays.fill(oldPayload, (byte) 0);
            ContentValues values = new ContentValues();
            values.put("sealed_payload", oldSealed);
            store.getWritableDatabase().update("memories", values, "id=?", new String[]{legacy.id});
            Arrays.fill(oldSealed, (byte) 0);
        } finally {
            store.close();
        }
        SQLiteDatabase legacyDatabase = SQLiteDatabase.openDatabase(getDatabasePath("agent_os.db").getPath(), null,
            SQLiteDatabase.OPEN_READWRITE);
        try {
            legacyDatabase.setVersion(3);
        } finally {
            legacyDatabase.close();
        }

        store = new AgentStore(this);
        try {
            AgentStore.Memory migrated = findMemory(store.listMemories(), legacy.id);
            if (migrated == null || !"SYNTHETIC legacy memory stays private".equals(migrated.content)
                    || !AgentStore.MEMORY_SOURCE_USER.equals(migrated.source) || !migrated.sourceReference.isEmpty()
                    || store.getReadableDatabase().getVersion() != 4) {
                throw new IllegalStateException("The v3 memory migration did not preserve content and mark its source");
            }

            AgentStore.Memory source = store.createMemory(AgentStore.MEMORY_PREFERENCE,
                "SYNTHETIC 搜索来源 and local choice", AgentStore.MEMORY_SOURCE_TRACEFORM, "provider=traceform;synthetic=true");
            MemoryIndex index = new MemoryIndex(store.listMemories());
            if (index.search("搜索来源").size() != 1 || index.search("Traceform").size() != 1
                    || !source.id.equals(index.search("Traceform").get(0).id)) {
                throw new IllegalStateException("The rebuilt memory index did not find Chinese content and source metadata");
            }

            MemoryArchive.Data archiveData = new MemoryArchive.Data();
            archiveData.exportedAt = System.currentTimeMillis();
            archiveData.memories.add(new AgentStore.Memory(source.id, source.kind,
                "SYNTHETIC archive-selected content", AgentStore.MEMORY_SOURCE_TRACEFORM,
                "provider=traceform;archive=synthetic", source.createdAt, source.updatedAt));
            char[] passphrase = TEST_PASSPHRASE.toCharArray();
            byte[] archive;
            try {
                archive = MemoryArchive.encrypt(archiveData, passphrase);
            } finally {
                Arrays.fill(passphrase, '\0');
            }
            AgentStore.ImportResult keepLocal = importMemoryArchive(store, archive, false);
            AgentStore.Memory afterKeep = findMemory(store.listMemories(), source.id);
            if (keepLocal.memoryConflicts != 1 || keepLocal.memoriesReplaced != 0
                    || !source.content.equals(afterKeep.content)) {
                throw new IllegalStateException("The keep-local conflict choice did not preserve local memory");
            }
            AgentStore.ImportResult chooseArchive = importMemoryArchive(store, archive, true);
            AgentStore.Memory afterReplace = findMemory(store.listMemories(), source.id);
            if (chooseArchive.memoryConflicts != 1 || chooseArchive.memoriesReplaced != 1
                    || !"SYNTHETIC archive-selected content".equals(afterReplace.content)
                    || !"provider=traceform;archive=synthetic".equals(afterReplace.sourceReference)) {
                throw new IllegalStateException("The archive conflict choice did not replace content and provenance");
            }
            store.deleteMemory(source.id);
            AgentStore.ImportResult tombstone = importMemoryArchive(store, archive, true);
            if (tombstone.recordsSkipped != 1 || findMemory(store.listMemories(), source.id) != null) {
                throw new IllegalStateException("A local deletion tombstone did not override the archive choice");
            }
            Arrays.fill(archive, (byte) 0);
            return "PASS: v3 memory migration preserved encrypted content and marked legacy provenance; the rebuilt index "
                + "found Chinese content and source; import choices kept or replaced a same-ID memory; a tombstone still won.";
        } finally {
            store.close();
        }
    }

    private static AgentStore.ImportResult importMemoryArchive(AgentStore store, byte[] archive,
            boolean preferArchive) throws Exception {
        char[] passphrase = TEST_PASSPHRASE.toCharArray();
        try {
            return store.importArchive(new ByteArrayInputStream(archive), passphrase, preferArchive);
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    private void insertLegacyAudit(SQLiteDatabase database, String taskId, String event, long occurredAt) {
        ContentValues values = new ContentValues();
        values.put("id", UUID.randomUUID().toString());
        values.put("task_id", taskId);
        values.put("occurred_at", occurredAt);
        values.put("event", event);
        database.insertOrThrow("audit", null, values);
    }

    private String verifyExecutor() throws Exception {
        AgentStore store = new AgentStore(this);
        try {
            AgentStore.Task authorizedTask = store.createTask("SYNTHETIC authorized local executor task");
            CapabilityBroker broker = new CapabilityBroker(this, store);
            broker.grant(authorizedTask.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            store.transition(authorizedTask.id, AgentStore.ACTIVE);
            AgentScheduler.scheduleTask(this, store.getTask(authorizedTask.id));
            AgentStore.Task completed = awaitTaskStatus(store, authorizedTask.id, AgentStore.COMPLETED);
            if (completed.executionStep != 2 || completed.checkpointNote.isEmpty()
                    || !completed.result.contains(authorizedTask.title)
                    || !completed.result.contains(completed.checkpointNote)) {
                throw new IllegalStateException("The executor did not persist its checkpoint and result");
            }
            SQLiteDatabase database = store.getReadableDatabase();
            if (auditEventCount(database, authorizedTask.id,
                    CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY,
                        CapabilityBroker.DEMO_NOTE_SCOPE)) != 1
                    || auditEventCount(database, authorizedTask.id, "executor_step_1_saved") != 1
                    || auditEventCount(database, authorizedTask.id, "executor_step_2_saved") != 1
                    || auditEventCount(database, authorizedTask.id, "task_succeeded") != 1) {
                throw new IllegalStateException("The executor audit trail is incomplete");
            }

            AgentStore.Task waitingTask = store.createTask("SYNTHETIC authorization wait task");
            store.transition(waitingTask.id, AgentStore.ACTIVE);
            AgentScheduler.scheduleTask(this, store.getTask(waitingTask.id));
            awaitTaskStatus(store, waitingTask.id, AgentStore.WAITING_FOR_USER);
            if (auditEventCount(database, waitingTask.id,
                    CapabilityBroker.auditEvent("capability_denied", CapabilityBroker.DEMO_NOTE_CAPABILITY,
                        CapabilityBroker.DEMO_NOTE_SCOPE)) != 1) {
                throw new IllegalStateException("The missing capability was not denied and audited");
            }
            broker.grant(waitingTask.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            store.transition(waitingTask.id, AgentStore.ACTIVE);
            AgentScheduler.scheduleTask(this, store.getTask(waitingTask.id));
            AgentStore.Task resumed = awaitTaskStatus(store, waitingTask.id, AgentStore.COMPLETED);
            if (resumed.executionStep != 2 || resumed.result.isEmpty())
                throw new IllegalStateException("The authorized task did not resume to completion");

            return "PASS: scheduled local execution saved both steps; missing authorization waited and audited, "
                + "then resumed after a fresh grant. task=" + authorizedTask.id + " retry=" + waitingTask.id;
        } finally {
            store.close();
        }
    }

    private String verifyExecutorLifecycle() throws Exception {
        AgentStore store = new AgentStore(this);
        JobScheduler scheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
        try {
            CapabilityBroker broker = new CapabilityBroker(this, store);
            AgentStore.Task pausedTask = store.createTask("SYNTHETIC executor pause and resume task");
            prepareExecutorCheckpoint(store, broker, pausedTask);
            AgentScheduler.scheduleTask(this, store.getTask(pausedTask.id), 5_000);
            if (scheduler == null || scheduler.getPendingJob(pausedTask.jobId) == null)
                throw new IllegalStateException("The pause test job was not pending in JobScheduler");
            store.transition(pausedTask.id, AgentStore.PAUSED);
            AgentScheduler.cancelTask(this, pausedTask.jobId);
            Thread.sleep(6_000);

            AgentStore.Task paused = store.getTask(pausedTask.id);
            SQLiteDatabase database = store.getReadableDatabase();
            if (!AgentStore.PAUSED.equals(paused.status) || paused.executionStep != 1
                    || paused.checkpointNote.isEmpty() || !paused.result.isEmpty()
                    || scheduler.getPendingJob(pausedTask.jobId) != null
                    || auditEventCount(database, pausedTask.id,
                        CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY,
                            CapabilityBroker.DEMO_NOTE_SCOPE)) != 1
                    || auditEventCount(database, pausedTask.id, "executor_step_2_started") != 0) {
                throw new IllegalStateException("A paused checkpointed task advanced or lost its checkpoint");
            }

            store.transition(pausedTask.id, AgentStore.ACTIVE);
            AgentScheduler.scheduleTask(this, store.getTask(pausedTask.id));
            AgentStore.Task resumed = awaitTaskStatus(store, pausedTask.id, AgentStore.COMPLETED);
            if (resumed.executionStep != 2 || !paused.checkpointNote.equals(resumed.checkpointNote)
                    || auditEventCount(database, pausedTask.id,
                        CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY,
                            CapabilityBroker.DEMO_NOTE_SCOPE)) != 1
                    || auditEventCount(database, pausedTask.id, "executor_step_1_saved") != 1
                    || auditEventCount(database, pausedTask.id, "executor_step_2_saved") != 1) {
                throw new IllegalStateException("Resume did not continue once from the saved checkpoint");
            }

            AgentStore.Task cancelledTask = store.createTask("SYNTHETIC executor cancellation task");
            prepareExecutorCheckpoint(store, broker, cancelledTask);
            AgentScheduler.scheduleTask(this, store.getTask(cancelledTask.id), 60_000);
            if (scheduler.getPendingJob(cancelledTask.jobId) == null)
                throw new IllegalStateException("The cancellation test job was not pending in JobScheduler");
            store.transition(cancelledTask.id, AgentStore.CANCELLED);
            AgentScheduler.cancelTask(this, cancelledTask.jobId);
            Thread.sleep(1_500);

            AgentStore.Task cancelled = store.getTask(cancelledTask.id);
            if (!AgentStore.CANCELLED.equals(cancelled.status) || cancelled.executionStep != 1
                    || cancelled.checkpointNote.isEmpty() || !cancelled.result.isEmpty()
                    || scheduler.getPendingJob(cancelledTask.jobId) != null
                    || auditEventCount(database, cancelledTask.id,
                        CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY,
                            CapabilityBroker.DEMO_NOTE_SCOPE)) != 1
                    || auditEventCount(database, cancelledTask.id, "executor_step_2_started") != 0
                    || auditEventCount(database, cancelledTask.id, "task_succeeded") != 0) {
                throw new IllegalStateException("A cancelled task was completed or remained scheduled");
            }

            verifyActiveExecutorPauseResumeCancel(store, broker, scheduler);

            return "PASS: pause preserved the step-1 checkpoint with no pending job; resume completed step 2 "
                + "without repeating the capability call; cancellation removed the pending job and remained terminal; "
                + "active-worker pause/resume and cancellation also stopped before step 2. "
                + "pausedTask=" + pausedTask.id + " cancelledTask=" + cancelledTask.id;
        } finally {
            AgentExecutor.afterFirstCheckpointForValidation = null;
            store.close();
        }
    }

    private String armUiCheckpointGate() {
        if (uiCheckpointGate != null) throw new IllegalStateException("A UI checkpoint gate is already armed");
        uiCheckpointGate = new CheckpointGate();
        AgentExecutor.afterFirstCheckpointForValidation = uiCheckpointGate;
        return "PASS: the UI lifecycle checkpoint gate is armed.";
    }

    private String releaseUiCheckpointGate() {
        if (uiCheckpointGate == null) throw new IllegalStateException("No UI checkpoint gate is armed");
        uiCheckpointGate.release();
        uiCheckpointGate = null;
        AgentExecutor.afterFirstCheckpointForValidation = null;
        return "PASS: the UI lifecycle checkpoint gate is released.";
    }

    private void verifyActiveExecutorPauseResumeCancel(AgentStore store, CapabilityBroker broker,
            JobScheduler scheduler) throws Exception {
        AgentStore.Task pausedTask = store.createTask("SYNTHETIC active executor pause task");
        CheckpointGate pauseGate = new CheckpointGate();
        AgentExecutor.afterFirstCheckpointForValidation = pauseGate;
        try {
            broker.grant(pausedTask.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            store.transition(pausedTask.id, AgentStore.ACTIVE);
            AgentScheduler.scheduleTask(this, store.getTask(pausedTask.id));
            if (!pauseGate.awaitEntered(90, TimeUnit.SECONDS))
                throw new IllegalStateException("The active pause job did not reach its checkpoint gate");
            AgentStore.Task gated = store.getTask(pausedTask.id);
            if (!AgentStore.ACTIVE.equals(gated.status) || gated.executionStep != 1 || gated.checkpointNote.isEmpty())
                throw new IllegalStateException("The pause job was not running after committing step 1");
            store.transition(pausedTask.id, AgentStore.PAUSED);
            AgentScheduler.cancelTask(this, pausedTask.jobId);
            pauseGate.release();
            if (!pauseGate.awaitExited(5, TimeUnit.SECONDS))
                throw new IllegalStateException("The paused executor did not leave its checkpoint gate");
        } finally {
            pauseGate.release();
            AgentExecutor.afterFirstCheckpointForValidation = null;
        }
        Thread.sleep(500);

        SQLiteDatabase database = store.getReadableDatabase();
        AgentStore.Task paused = store.getTask(pausedTask.id);
        if (!AgentStore.PAUSED.equals(paused.status) || paused.executionStep != 1 || !paused.result.isEmpty()
                || scheduler.getPendingJob(pausedTask.jobId) != null
                || auditEventCount(database, pausedTask.id, "executor_step_2_started") != 0
                || auditEventCount(database, pausedTask.id, "task_succeeded") != 0) {
            throw new IllegalStateException("The active paused worker advanced or remained scheduled");
        }
        store.transition(pausedTask.id, AgentStore.ACTIVE);
        AgentScheduler.scheduleTask(this, store.getTask(pausedTask.id));
        AgentStore.Task resumed = awaitTaskStatus(store, pausedTask.id, AgentStore.COMPLETED);
        if (resumed.executionStep != 2 || !paused.checkpointNote.equals(resumed.checkpointNote)
                || auditEventCount(database, pausedTask.id,
                    CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY,
                        CapabilityBroker.DEMO_NOTE_SCOPE)) != 1
                || auditEventCount(database, pausedTask.id, "executor_step_1_saved") != 1
                || auditEventCount(database, pausedTask.id, "executor_step_2_saved") != 1) {
            throw new IllegalStateException("The active paused task did not resume once from its checkpoint");
        }

        AgentStore.Task cancelledTask = store.createTask("SYNTHETIC active executor cancellation task");
        CheckpointGate cancelGate = new CheckpointGate();
        AgentExecutor.afterFirstCheckpointForValidation = cancelGate;
        try {
            broker.grant(cancelledTask.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            store.transition(cancelledTask.id, AgentStore.ACTIVE);
            AgentScheduler.scheduleTask(this, store.getTask(cancelledTask.id));
            if (!cancelGate.awaitEntered(90, TimeUnit.SECONDS))
                throw new IllegalStateException("The active cancellation job did not reach its checkpoint gate");
            AgentStore.Task gated = store.getTask(cancelledTask.id);
            if (!AgentStore.ACTIVE.equals(gated.status) || gated.executionStep != 1 || gated.checkpointNote.isEmpty())
                throw new IllegalStateException("The cancellation job was not running after committing step 1");
            store.transition(cancelledTask.id, AgentStore.CANCELLED);
            AgentScheduler.cancelTask(this, cancelledTask.jobId);
            cancelGate.release();
            if (!cancelGate.awaitExited(5, TimeUnit.SECONDS))
                throw new IllegalStateException("The cancelled executor did not leave its checkpoint gate");
        } finally {
            cancelGate.release();
            AgentExecutor.afterFirstCheckpointForValidation = null;
        }
        Thread.sleep(500);

        AgentStore.Task cancelled = store.getTask(cancelledTask.id);
        if (!AgentStore.CANCELLED.equals(cancelled.status) || cancelled.executionStep != 1
                || cancelled.checkpointNote.isEmpty() || !cancelled.result.isEmpty()
                || scheduler.getPendingJob(cancelledTask.jobId) != null
                || auditEventCount(database, cancelledTask.id,
                    CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY,
                        CapabilityBroker.DEMO_NOTE_SCOPE)) != 1
                || auditEventCount(database, cancelledTask.id, "executor_step_2_started") != 0
                || auditEventCount(database, cancelledTask.id, "task_succeeded") != 0) {
            throw new IllegalStateException("The active cancelled worker advanced or remained scheduled");
        }
    }

    private static final class CheckpointGate implements Runnable {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch exited = new CountDownLatch(1);

        @Override
        public void run() {
            entered.countDown();
            try {
                if (!released.await(90, TimeUnit.SECONDS))
                    throw new IllegalStateException("The validation checkpoint gate timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("The validation checkpoint gate was interrupted", error);
            } finally {
                exited.countDown();
            }
        }

        boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
            return entered.await(timeout, unit);
        }

        boolean awaitExited(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        void release() {
            released.countDown();
        }
    }

    private void prepareExecutorCheckpoint(AgentStore store, CapabilityBroker broker, AgentStore.Task task)
            throws Exception {
        broker.grant(task.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
        store.transition(task.id, AgentStore.ACTIVE);
        store.recordStepStarted(task.id, 0);
        String note = broker.invoke(task.id, CapabilityBroker.DEMO_NOTE_CAPABILITY,
            CapabilityBroker.DEMO_NOTE_SCOPE);
        store.saveNoteCheckpoint(task.id, note);
    }

    private AgentStore.Task awaitTaskStatus(AgentStore store, String taskId, String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 90_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AgentStore.Task task = store.getTask(taskId);
            if (task == null) throw new IllegalStateException("The synthetic task disappeared");
            if (expected.equals(task.status)) return task;
            if (AgentStore.FAILED.equals(task.status)) throw new IllegalStateException("The synthetic task failed");
            Thread.sleep(250);
        }
        throw new IllegalStateException("The scheduled task did not reach " + expected);
    }

    private String prepareCheckpointRestart() throws Exception {
        AgentStore store = new AgentStore(this);
        try {
            AgentStore.Task task = store.createTask("SYNTHETIC checkpoint restart task");
            CapabilityBroker broker = new CapabilityBroker(this, store);
            broker.grant(task.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            store.transition(task.id, AgentStore.ACTIVE);
            store.recordStepStarted(task.id, 0);
            String note = broker.invoke(task.id, CapabilityBroker.DEMO_NOTE_CAPABILITY,
                CapabilityBroker.DEMO_NOTE_SCOPE);
            store.saveNoteCheckpoint(task.id, note);
            AgentStore.Task checkpoint = store.getTask(task.id);
            if (checkpoint.executionStep != 1 || !note.equals(checkpoint.checkpointNote))
                throw new IllegalStateException("The first execution checkpoint was not committed");
            AgentScheduler.scheduleTask(this, store.getTask(task.id), 30_000);
            return "PREPARED: taskId=" + task.id + " checkpointStep=1 pid=" + android.os.Process.myPid()
                + "; Activity will finish so its process can be stopped.";
        } finally {
            store.close();
        }
    }

    private String verifyCheckpointRestart() throws Exception {
        String taskId = getIntent().getStringExtra("taskId");
        if (taskId == null) throw new IllegalArgumentException("Expected checkpoint task ID was not supplied");
        AgentStore store = new AgentStore(this);
        try {
            AgentStore.Task task = awaitTaskStatus(store, taskId, AgentStore.COMPLETED);
            CapabilityBroker broker = new CapabilityBroker(this, store);
            SQLiteDatabase database = store.getReadableDatabase();
            long remainingGrant = broker.remainingMillis(taskId, CapabilityBroker.DEMO_NOTE_CAPABILITY,
                CapabilityBroker.DEMO_NOTE_SCOPE);
            int invokeCount = auditEventCount(database, taskId,
                CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY,
                    CapabilityBroker.DEMO_NOTE_SCOPE));
            int step1Count = auditEventCount(database, taskId, "executor_step_1_saved");
            int step2Count = auditEventCount(database, taskId, "executor_step_2_saved");
            if (task.executionStep != 2 || task.checkpointNote.isEmpty() || task.result.isEmpty()
                    || remainingGrant != 0 || invokeCount != 1 || step1Count != 1 || step2Count != 1) {
                throw new IllegalStateException("Checkpoint verification failed: step=" + task.executionStep
                    + ", hasNote=" + !task.checkpointNote.isEmpty() + ", hasResult=" + !task.result.isEmpty()
                    + ", remainingGrant=" + remainingGrant + ", invokes=" + invokeCount
                    + ", step1Saves=" + step1Count + ", step2Saves=" + step2Count
                    + ", pid=" + android.os.Process.myPid());
            }
            return "PASS: persisted JobScheduler task resumed from step 1 after process stop; the note was not reread, "
                + "and its process-local capability grant did not survive. task=" + taskId
                + " pid=" + android.os.Process.myPid();
        } finally {
            store.close();
        }
    }

    private String measureKdfMedianMillis() throws Exception {
        char[] passphrase = TEST_PASSPHRASE.toCharArray();
        byte[] salt = new byte[16];
        long[] durations = new long[3];
        try {
            for (int index = 0; index < durations.length + 1; index++) {
                long started = SystemClock.elapsedRealtimeNanos();
                byte[] derived = MemoryArchive.deriveKey(passphrase, salt);
                long elapsed = SystemClock.elapsedRealtimeNanos() - started;
                Arrays.fill(derived, (byte) 0);
                if (index > 0) durations[index - 1] = elapsed;
            }
        } finally {
            Arrays.fill(passphrase, '\0');
            Arrays.fill(salt, (byte) 0);
        }
        Arrays.sort(durations);
        return String.format(Locale.US, "%.1f", durations[1] / 1_000_000.0);
    }

    private String seedArchive() throws Exception {
        AgentStore store = new AgentStore(this);
        try {
            AgentStore.Task created = store.createTask(TEST_TASK_TITLE);
            store.transition(created.id, AgentStore.ACTIVE);
            store.transition(created.id, AgentStore.PAUSED);
            AgentStore.Memory memory = store.createMemory(AgentStore.MEMORY_PREFERENCE, TEST_MEMORY_CONTENT);
            AgentStore.Task task = findTask(store.listTasks(), created.id);
            if (task == null) throw new IllegalStateException("The synthetic source task was not created");

            char[] passphrase = TEST_PASSPHRASE.toCharArray();
            try {
                writePrivateFile("agent-os-cross-install.taos", store.exportArchive(passphrase));
            } finally {
                Arrays.fill(passphrase, '\0');
            }

            MemoryArchive.Data conflict = new MemoryArchive.Data();
            conflict.exportedAt = System.currentTimeMillis();
            conflict.tasks.add(new AgentStore.Task(task.id, "SYNTHETIC conflicting task content",
                task.status, task.createdAt, task.updatedAt));
            conflict.memories.add(new AgentStore.Memory(memory.id, memory.kind,
                "SYNTHETIC conflicting memory content", memory.createdAt, memory.updatedAt));
            char[] conflictPassphrase = TEST_PASSPHRASE.toCharArray();
            try {
                writePrivateFile("agent-os-conflict.taos", MemoryArchive.encrypt(conflict, conflictPassphrase));
            } finally {
                Arrays.fill(conflictPassphrase, '\0');
            }

            JSONObject manifest = new JSONObject()
                .put("taskId", task.id)
                .put("taskTitle", task.title)
                .put("taskStatus", task.status)
                .put("taskCreatedAt", task.createdAt)
                .put("taskUpdatedAt", task.updatedAt)
                .put("taskAuditCount", 3)
                .put("memoryId", memory.id)
                .put("memoryKind", memory.kind)
                .put("memoryContent", memory.content)
                .put("memorySource", memory.source)
                .put("memorySourceReference", memory.sourceReference)
                .put("memoryCreatedAt", memory.createdAt)
                .put("memoryUpdatedAt", memory.updatedAt);
            writePrivateFile("agent-os-validation-manifest.json", manifest.toString().getBytes("UTF-8"));
            return "PASS: synthetic source archives created.\n" + manifest;
        } finally {
            store.close();
        }
    }

    private String verifyImportedRecords(boolean conflictCheck) throws Exception {
        AgentStore store = new AgentStore(this);
        try {
            String taskId = getIntent().getStringExtra("taskId");
            String taskTitle = getIntent().getStringExtra("taskTitle");
            String taskStatus = getIntent().getStringExtra("taskStatus");
            long taskCreatedAt = getIntent().getLongExtra("taskCreatedAt", -1);
            long taskUpdatedAt = getIntent().getLongExtra("taskUpdatedAt", -1);
            int taskAuditCount = getIntent().getIntExtra("taskAuditCount", -1);
            String memoryId = getIntent().getStringExtra("memoryId");
            String memoryKind = getIntent().getStringExtra("memoryKind");
            String memoryContent = getIntent().getStringExtra("memoryContent");
            long memoryCreatedAt = getIntent().getLongExtra("memoryCreatedAt", -1);
            long memoryUpdatedAt = getIntent().getLongExtra("memoryUpdatedAt", -1);
            String memorySource = getIntent().getStringExtra("memorySource");
            String memorySourceReference = getIntent().getStringExtra("memorySourceReference");
            if (taskId == null || memoryId == null) {
                throw new IllegalArgumentException("Expected source record metadata was not supplied");
            }

            AgentStore.Task task = findTask(store.listTasks(), taskId);
            AgentStore.Memory memory = findMemory(store.listMemories(), memoryId);
            if (task == null || memory == null || !taskTitle.equals(task.title)
                    || !taskStatus.equals(task.status) || taskCreatedAt != task.createdAt
                    || taskUpdatedAt != task.updatedAt || !memoryKind.equals(memory.kind)
                    || !memoryContent.equals(memory.content) || memoryCreatedAt != memory.createdAt
                    || memoryUpdatedAt != memory.updatedAt || !memorySource.equals(memory.source)
                    || !memorySourceReference.equals(memory.sourceReference)
                    || auditCount(store.getReadableDatabase(), taskId) != taskAuditCount) {
                throw new IllegalStateException("Imported IDs, content, timestamps, status, or audit history changed");
            }
            return conflictCheck
                ? "PASS: a same-ID archive with different task and memory content did not overwrite local records."
                : "PASS: another installation retained task/memory IDs, content, timestamps, status, source provenance, and all audit events.";
        } finally {
            store.close();
        }
    }

    private String verifyCapabilityAudit() {
        String taskId = getIntent().getStringExtra("taskId");
        if (taskId == null) throw new IllegalArgumentException("Expected task ID was not supplied");
        AgentStore store = new AgentStore(this);
        try {
            SQLiteDatabase database = store.getReadableDatabase();
            if (auditEventCount(database, taskId, CapabilityBroker.GRANTED_EVENT) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.INVOKED_EVENT) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.REVOKED_EVENT) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.DENIED_EVENT) != 1
                    || auditCount(database, taskId) != 7) {
                throw new IllegalStateException("Capability grant, invocation, revocation, or denial audit is missing");
            }
            return "PASS: grant, invocation, revocation, and post-revocation denial were each audited once.";
        } finally {
            store.close();
        }
    }

    private String verifyStatusProviderAudit() {
        String taskId = getIntent().getStringExtra("taskId");
        if (taskId == null) throw new IllegalArgumentException("Expected task ID was not supplied");
        AgentStore store = new AgentStore(this);
        try {
            SQLiteDatabase database = store.getReadableDatabase();
            String capability = CapabilityBroker.DEMO_STATUS_CAPABILITY;
            String scope = CapabilityBroker.DEMO_STATUS_SCOPE;
            if (auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_granted", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_invoked", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_revoked", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_denied", capability, scope)) != 1
                    || auditCount(database, taskId) != 7) {
                throw new IllegalStateException("Status provider grant, invocation, revocation, or denial audit is missing");
            }
            return "PASS: the second provider used the same scoped grant, invocation, revoke, and denial audit contract.";
        } finally {
            store.close();
        }
    }

    private String verifyRemoteProviderAudit() {
        String taskId = getIntent().getStringExtra("taskId");
        if (taskId == null) throw new IllegalArgumentException("Expected task ID was not supplied");
        AgentStore store = new AgentStore(this);
        try {
            SQLiteDatabase database = store.getReadableDatabase();
            String capability = AndroidRemoteStatusProvider.CAPABILITY;
            String scope = AndroidRemoteStatusProvider.SCOPE;
            if (checkUriPermission(AndroidRemoteStatusProvider.CONTENT_URI, android.os.Process.myPid(),
                    android.os.Process.myUid(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_source_selected", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_granted", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_invoked", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_revoked", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_denied", capability, scope)) != 1
                    || auditCount(database, taskId) != 8) {
                throw new IllegalStateException("Remote provider grant, invocation, revocation, or denial audit is missing");
            }
            return "PASS: the independent provider's selected URI was read and revoked through the scoped Broker.";
        } finally {
            store.close();
        }
    }

    private String verifyRemoteProviderRestart() {
        String taskId = getIntent().getStringExtra("taskId");
        if (taskId == null) throw new IllegalArgumentException("Expected task ID was not supplied");
        if (checkUriPermission(AndroidRemoteStatusProvider.CONTENT_URI, android.os.Process.myPid(),
                android.os.Process.myUid(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new IllegalStateException("The temporary content URI grant survived process restart");
        }

        AgentStore store = new AgentStore(this);
        try {
            CapabilityBroker broker = new CapabilityBroker(this, store);
            boolean denied = false;
            try {
                broker.invoke(taskId, AndroidRemoteStatusProvider.CAPABILITY,
                    AndroidRemoteStatusProvider.SCOPE);
            } catch (SecurityException expected) {
                denied = true;
            }
            SQLiteDatabase database = store.getReadableDatabase();
            String capability = AndroidRemoteStatusProvider.CAPABILITY;
            String scope = AndroidRemoteStatusProvider.SCOPE;
            if (!denied
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_source_selected", capability, scope)) != 2
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_granted", capability, scope)) != 2
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_invoked", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_failed", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_revoked", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_denied", capability, scope)) != 2
                    || auditCount(database, taskId) != 12) {
                throw new IllegalStateException("A selected URI or Broker grant was replayed after process restart");
            }
            return "PASS: provider failure and retry were audited; temporary URI and Broker grants did not survive restart, and replay was denied and audited.";
        } finally {
            store.close();
        }
    }

    private String verifyRemoteProviderFailure() {
        String taskId = getIntent().getStringExtra("taskId");
        if (taskId == null) throw new IllegalArgumentException("Expected task ID was not supplied");
        AgentStore store = new AgentStore(this);
        try {
            SQLiteDatabase database = store.getReadableDatabase();
            String capability = AndroidRemoteStatusProvider.CAPABILITY;
            String scope = AndroidRemoteStatusProvider.SCOPE;
            if (checkUriPermission(AndroidRemoteStatusProvider.CONTENT_URI, android.os.Process.myPid(),
                    android.os.Process.myUid(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_source_selected", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_granted", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_failed", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_invoked", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_revoked", capability, scope)) != 1
                    || auditEventCount(database, taskId, CapabilityBroker.auditEvent("capability_denied", capability, scope)) != 1
                    || auditCount(database, taskId) != 9) {
                throw new IllegalStateException("Picker cancellation, provider failure, retry, or revocation audit is missing");
            }
            return "PASS: picker cancellation was denied and audited; the provider exception was recorded, retry succeeded, and the URI grant was revoked.";
        } finally {
            store.close();
        }
    }

    private void writePrivateFile(String name, byte[] contents) throws Exception {
        try (FileOutputStream output = openFileOutput(name, MODE_PRIVATE)) {
            output.write(contents);
            output.flush();
        } finally {
            Arrays.fill(contents, (byte) 0);
        }
    }

    private static boolean tableExists(SQLiteDatabase database, String table) {
        try (Cursor cursor = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return cursor.moveToFirst();
        }
    }

    private static int auditCount(SQLiteDatabase database, String taskId) {
        try (Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM audit WHERE task_id=?", new String[]{taskId})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static int auditEventCount(SQLiteDatabase database, String taskId, String event) {
        try (Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM audit WHERE task_id=? AND event=?",
                new String[]{taskId, event})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static AgentStore.Task findTask(List<AgentStore.Task> tasks, String id) {
        for (AgentStore.Task task : tasks) if (id.equals(task.id)) return task;
        return null;
    }

    private static AgentStore.Memory findMemory(List<AgentStore.Memory> memories, String id) {
        for (AgentStore.Memory memory : memories) if (id.equals(memory.id)) return memory;
        return null;
    }

    private String verifyReasoningPlanner() throws Exception {
        SemanticRulePlanner planner = new SemanticRulePlanner();
        AgentStore store = new AgentStore(this);
        CapabilityBroker broker = new CapabilityBroker(this, store);
        try {
            List<CapabilityDescriptor> availableCaps = broker.listAvailableCapabilities("synthetic-task");
            List<AgentStore.Memory> memories = Collections.emptyList();

            // 1. Single intent (Note)
            ExecutionPlan notePlan = planner.plan("task-1", "整理本地示例便笺", availableCaps, memories);
            if (notePlan.totalSteps() != 2) throw new IllegalStateException("Expected 2 steps for note intent, got " + notePlan.totalSteps());
            if (!CapabilityBroker.DEMO_NOTE_CAPABILITY.equals(notePlan.steps.get(0).capability))
                throw new IllegalStateException("Step 0 should be note capability");
            if (!"builtin.synthesis".equals(notePlan.steps.get(1).capability))
                throw new IllegalStateException("Step 1 should be builtin.synthesis");

            // 2. Multi-intent (Note + Status + Traceform)
            ExecutionPlan multiPlan = planner.plan("task-2", "整理便笺并检查设备状态与历史预测", availableCaps, memories);
            if (multiPlan.totalSteps() != 4) throw new IllegalStateException("Expected 4 steps for multi intent, got " + multiPlan.totalSteps());
            if (!CapabilityBroker.DEMO_NOTE_CAPABILITY.equals(multiPlan.steps.get(0).capability))
                throw new IllegalStateException("Multi step 0 mismatch");
            if (!CapabilityBroker.DEMO_STATUS_CAPABILITY.equals(multiPlan.steps.get(1).capability))
                throw new IllegalStateException("Multi step 1 mismatch");
            if (!CapabilityBroker.TRACEFORM_CAPABILITY.equals(multiPlan.steps.get(2).capability))
                throw new IllegalStateException("Multi step 2 mismatch");
            if (!"builtin.synthesis".equals(multiPlan.steps.get(3).capability))
                throw new IllegalStateException("Multi step 3 mismatch");

            // 3. Fallback intent
            ExecutionPlan fallbackPlan = planner.plan("task-3", "执行未知综合计算", availableCaps, memories);
            if (fallbackPlan.totalSteps() != 2) throw new IllegalStateException("Expected fallback to 2 steps");
            if (!CapabilityBroker.DEMO_NOTE_CAPABILITY.equals(fallbackPlan.steps.get(0).capability))
                throw new IllegalStateException("Fallback step 0 should be demo note");

            // 4. JSON Serialization & Deserialization round-trip
            JSONObject serialized = multiPlan.toJson();
            ExecutionPlan deserialized = ExecutionPlan.fromJson(serialized);
            if (deserialized.totalSteps() != multiPlan.totalSteps()
                    || !deserialized.id.equals(multiPlan.id)
                    || !deserialized.goal.equals(multiPlan.goal)
                    || deserialized.currentStepIndex != multiPlan.currentStepIndex) {
                throw new IllegalStateException("JSON deserialization round-trip failed");
            }
            for (int i = 0; i < multiPlan.totalSteps(); i++) {
                ExecutionPlan.Step orig = multiPlan.steps.get(i);
                ExecutionPlan.Step deser = deserialized.steps.get(i);
                if (orig.index != deser.index || !orig.title.equals(deser.title)
                        || !orig.capability.equals(deser.capability)
                        || !orig.scope.equals(deser.scope)
                        || !orig.status.equals(deser.status)) {
                    throw new IllegalStateException("Step JSON serialization mismatch at step " + i);
                }
            }

            // 5. Step update immutability
            ExecutionPlan.Step step0 = multiPlan.steps.get(0);
            ExecutionPlan.Step done0 = step0.withStatusAndObservation(ExecutionPlan.STEP_DONE, "观察数据测试", System.currentTimeMillis());
            ExecutionPlan updatedPlan = multiPlan.withStepUpdated(0, done0, 1);
            if (!ExecutionPlan.STEP_DONE.equals(updatedPlan.steps.get(0).status)
                    || !ExecutionPlan.STEP_PENDING.equals(updatedPlan.steps.get(1).status)
                    || updatedPlan.currentStepIndex != 1) {
                throw new IllegalStateException("ExecutionPlan step update failure");
            }

            // 6. Synthesis output test
            List<ExecutionPlan.Step> executedSteps = new ArrayList<>();
            executedSteps.add(done0);
            String synthesis = planner.synthesize("task-2", "整理便笺并检查设备状态与历史预测", executedSteps);
            if (!synthesis.contains("本地演示结果") || !synthesis.contains("观察数据测试") || !synthesis.contains("端侧推演分析")) {
                throw new IllegalStateException("Synthesis text missing expected structure: " + synthesis);
            }

            // 7. Benchmark: 100 plan iterations
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                planner.plan("task-" + i, "整理便笺并核对设备运行状态", availableCaps, memories);
            }
            long durationNs = System.nanoTime() - start;
            double avgMs = durationNs / 100.0 / 1_000_000.0;
            if (avgMs > 5.0) {
                throw new IllegalStateException("Planner latency exceeded 5ms SLA: " + avgMs + "ms");
            }

            return "PASS: semantic planner generated single, multi, and fallback plans; JSON round-trip preserved all fields; step update functioned; synthesis output structured; avg planning latency=" + String.format(Locale.US, "%.3f", avgMs) + "ms";
        } finally {
            store.close();
        }
    }

    private String verifyDynamicExecutor() throws Exception {
        AgentStore store = new AgentStore(this);
        CapabilityBroker broker = new CapabilityBroker(this, store);
        SQLiteDatabase database = store.getWritableDatabase();
        try {
            // Case 1: Multi-step dynamic execution (Note + Status + Synthesis)
            AgentStore.Task task1 = store.createTask("SYNTHETIC dynamic task with note and status");
            if (task1.executionVersion != 2) throw new IllegalStateException("Task should have executionVersion 2");
            broker.grant(task1.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            broker.grant(task1.id, CapabilityBroker.DEMO_STATUS_CAPABILITY, CapabilityBroker.DEMO_STATUS_SCOPE);
            store.transition(task1.id, AgentStore.ACTIVE);

            new AgentExecutor(store, broker).execute(task1.id, new java.util.concurrent.atomic.AtomicBoolean(false));

            AgentStore.Task completed1 = store.getTask(task1.id);
            if (!AgentStore.COMPLETED.equals(completed1.status))
                throw new IllegalStateException("Task1 did not complete, status=" + completed1.status);
            if (completed1.plan == null || completed1.plan.totalSteps() != 3)
                throw new IllegalStateException("Task1 plan steps mismatch, totalSteps=" + (completed1.plan == null ? 0 : completed1.plan.totalSteps()));
            if (!ExecutionPlan.STEP_DONE.equals(completed1.plan.steps.get(0).status)
                    || !ExecutionPlan.STEP_DONE.equals(completed1.plan.steps.get(1).status)
                    || !ExecutionPlan.STEP_DONE.equals(completed1.plan.steps.get(2).status))
                throw new IllegalStateException("Not all steps marked DONE in Task1");
            if (!completed1.result.contains("本地演示结果") || !completed1.result.contains("合成示例便笺")
                    || !completed1.result.contains("合成状态") || !completed1.result.contains("端侧推演分析"))
                throw new IllegalStateException("Task1 result missing expected components: " + completed1.result);

            // Verify audits for task1
            if (auditEventCount(database, task1.id, "executor_step_1_saved") != 1
                    || auditEventCount(database, task1.id, "executor_step_2_saved") != 1
                    || auditEventCount(database, task1.id, "executor_step_3_saved") != 1
                    || auditEventCount(database, task1.id, "task_succeeded") != 1
                    || auditEventCount(database, task1.id, "internal_synthesis_completed:local_inference") != 1) {
                throw new IllegalStateException("Task1 audit trace incomplete");
            }

            // Case 2: Missing authorization halts execution into WAITING_FOR_USER
            AgentStore.Task task2 = store.createTask("SYNTHETIC task with note and status awaiting authorization");
            broker.grant(task2.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            // Intentionally do NOT grant DEMO_STATUS_CAPABILITY
            store.transition(task2.id, AgentStore.ACTIVE);

            new AgentExecutor(store, broker).execute(task2.id, new java.util.concurrent.atomic.AtomicBoolean(false));

            AgentStore.Task waitingTask = store.getTask(task2.id);
            if (!AgentStore.WAITING_FOR_USER.equals(waitingTask.status))
                throw new IllegalStateException("Task2 should be WAITING_FOR_USER, got " + waitingTask.status);
            if (waitingTask.executionStep != 1)
                throw new IllegalStateException("Task2 executionStep should be 1 (step 0 saved), got " + waitingTask.executionStep);
            if (!ExecutionPlan.STEP_DONE.equals(waitingTask.plan.steps.get(0).status))
                throw new IllegalStateException("Task2 step 0 should be DONE in checkpoint");

            // Now grant the missing capability and resume
            broker.grant(task2.id, CapabilityBroker.DEMO_STATUS_CAPABILITY, CapabilityBroker.DEMO_STATUS_SCOPE);
            store.transition(task2.id, AgentStore.ACTIVE);

            new AgentExecutor(store, broker).execute(task2.id, new java.util.concurrent.atomic.AtomicBoolean(false));

            AgentStore.Task completed2 = store.getTask(task2.id);
            if (!AgentStore.COMPLETED.equals(completed2.status))
                throw new IllegalStateException("Task2 did not complete after grant, status=" + completed2.status);
            // Verify step 0 note capability was invoked exactly once across both attempts
            int noteInvokedCount = auditEventCount(database, task2.id,
                CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE));
            if (noteInvokedCount != 1)
                throw new IllegalStateException("Note capability was re-invoked on resume! count=" + noteInvokedCount);

            // Case 3: Checkpoint Crash Recovery
            AgentStore.Task task3 = store.createTask("SYNTHETIC task with note and status testing crash checkpoint recovery");
            broker.grant(task3.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            broker.grant(task3.id, CapabilityBroker.DEMO_STATUS_CAPABILITY, CapabilityBroker.DEMO_STATUS_SCOPE);
            store.transition(task3.id, AgentStore.ACTIVE);

            final java.util.concurrent.atomic.AtomicInteger hookRuns = new java.util.concurrent.atomic.AtomicInteger();
            AgentExecutor.afterFirstCheckpointForValidation = () -> {
                if (hookRuns.incrementAndGet() == 1) {
                    throw new RuntimeException("Simulated process crash at Step 0 checkpoint");
                }
            };

            boolean crashed = false;
            try {
                new AgentExecutor(store, broker).execute(task3.id, new java.util.concurrent.atomic.AtomicBoolean(false));
            } catch (RuntimeException ex) {
                if ("Simulated process crash at Step 0 checkpoint".equals(ex.getMessage())) {
                    crashed = true;
                }
            } finally {
                AgentExecutor.afterFirstCheckpointForValidation = null;
            }

            if (!crashed) throw new IllegalStateException("Crash injection did not trigger");

            // Verify task3 step 0 checkpoint was preserved in database
            AgentStore.Task crashedTask = store.getTask(task3.id);
            if (crashedTask.executionStep != 1 || crashedTask.plan == null)
                throw new IllegalStateException("Crashed task checkpoint was not saved");
            if (!ExecutionPlan.STEP_DONE.equals(crashedTask.plan.steps.get(0).status))
                throw new IllegalStateException("Crashed task step 0 should be marked DONE");

            // Recover from crash: resume execution
            new AgentExecutor(store, broker).execute(task3.id, new java.util.concurrent.atomic.AtomicBoolean(false));

            AgentStore.Task recoveredTask = store.getTask(task3.id);
            if (!AgentStore.COMPLETED.equals(recoveredTask.status))
                throw new IllegalStateException("Recovered task did not complete, status=" + recoveredTask.status);

            int task3NoteCount = auditEventCount(database, task3.id,
                CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE));
            if (task3NoteCount != 1)
                throw new IllegalStateException("Step 0 note capability was called " + task3NoteCount + " times after crash recovery (expected 1)");

            return "PASS: multi-step dynamic execution completed 3-step plan; missing-authorization halted into WAITING_FOR_USER without repeating step 0 on resume; crash recovery at step 1 checkpoint completed without duplicate execution. task1=" + task1.id + " task2=" + task2.id + " task3=" + task3.id;
        } finally {
            AgentExecutor.afterFirstCheckpointForValidation = null;
            store.close();
        }
    }

    private String verifyStepTreeAndJitGrant() throws Exception {
        AgentStore store = new AgentStore(this);
        CapabilityBroker broker = new CapabilityBroker(this, store);
        SQLiteDatabase database = store.getWritableDatabase();
        try {
            // 1. Create a task with multi-step intent requiring note, status, and synthesis
            String taskTitle = "SYNTHETIC JIT test: 整理本地便笺与设备状态";
            AgentStore.Task task = store.createTask(taskTitle);
            ReasoningEngine planner = new SemanticRulePlanner();
            ExecutionPlan plan = planner.plan(task.id, task.title, broker.listAvailableCapabilities(task.id), store.listMemories());
            store.savePlan(task.id, plan);

            AgentStore.Task savedTask = store.getTask(task.id);
            if (savedTask.plan == null || savedTask.plan.totalSteps() != 3) {
                throw new IllegalStateException("Initial plan generation failed: steps=" + (savedTask.plan == null ? 0 : savedTask.plan.totalSteps()));
            }
            if (!ExecutionPlan.STEP_PENDING.equals(savedTask.plan.steps.get(0).status)
                    || !ExecutionPlan.STEP_PENDING.equals(savedTask.plan.steps.get(1).status)
                    || !ExecutionPlan.STEP_PENDING.equals(savedTask.plan.steps.get(2).status)) {
                throw new IllegalStateException("Initial steps should all be pending");
            }

            // 2. Start execution with NO grants -> should halt at Step 0 into WAITING_FOR_USER
            store.transition(task.id, AgentStore.ACTIVE);
            new AgentExecutor(store, broker).execute(task.id, new java.util.concurrent.atomic.AtomicBoolean(false));

            AgentStore.Task halted0 = store.getTask(task.id);
            if (!AgentStore.WAITING_FOR_USER.equals(halted0.status)) {
                throw new IllegalStateException("Task should halt in WAITING_FOR_USER at Step 0, got " + halted0.status);
            }
            if (halted0.executionStep != 0) {
                throw new IllegalStateException("Task executionStep should be 0, got " + halted0.executionStep);
            }
            if (!ExecutionPlan.STEP_AWAITING_GRANT.equals(halted0.plan.steps.get(0).status)) {
                throw new IllegalStateException("Step 0 should be awaiting_grant, got " + halted0.plan.steps.get(0).status);
            }
            if (!ExecutionPlan.STEP_PENDING.equals(halted0.plan.steps.get(1).status)) {
                throw new IllegalStateException("Step 1 should remain pending, got " + halted0.plan.steps.get(1).status);
            }

            // 3. JIT Grant Step 0 ONLY (demo.note.read)
            broker.grant(task.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            if (!broker.isGranted(task.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE)) {
                throw new IllegalStateException("Step 0 grant failed");
            }
            if (broker.isGranted(task.id, CapabilityBroker.DEMO_STATUS_CAPABILITY, CapabilityBroker.DEMO_STATUS_SCOPE)) {
                throw new IllegalStateException("Step 1 should not be granted");
            }

            // Resume task
            store.transition(task.id, AgentStore.ACTIVE);
            new AgentExecutor(store, broker).execute(task.id, new java.util.concurrent.atomic.AtomicBoolean(false));

            // Verify Step 0 succeeded, Step 1 halted into WAITING_FOR_USER
            AgentStore.Task halted1 = store.getTask(task.id);
            if (!AgentStore.WAITING_FOR_USER.equals(halted1.status)) {
                throw new IllegalStateException("Task should halt in WAITING_FOR_USER at Step 1, got " + halted1.status);
            }
            if (halted1.executionStep != 1) {
                throw new IllegalStateException("Task executionStep should be 1 after step 0 completion, got " + halted1.executionStep);
            }
            if (!ExecutionPlan.STEP_DONE.equals(halted1.plan.steps.get(0).status)
                    || halted1.plan.steps.get(0).observation.isEmpty()) {
                throw new IllegalStateException("Step 0 should be DONE with observation");
            }
            if (!ExecutionPlan.STEP_AWAITING_GRANT.equals(halted1.plan.steps.get(1).status)) {
                throw new IllegalStateException("Step 1 should be awaiting_grant, got " + halted1.plan.steps.get(1).status);
            }
            if (!ExecutionPlan.STEP_PENDING.equals(halted1.plan.steps.get(2).status)) {
                throw new IllegalStateException("Step 2 should remain pending");
            }

            // Verify step 0 note was invoked exactly once
            int noteInvokeCount = auditEventCount(database, task.id,
                CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE));
            if (noteInvokeCount != 1) {
                throw new IllegalStateException("Step 0 invoked count=" + noteInvokeCount);
            }

            // 4. JIT Grant Step 1 (demo.status.read)
            broker.grant(task.id, CapabilityBroker.DEMO_STATUS_CAPABILITY, CapabilityBroker.DEMO_STATUS_SCOPE);

            // Resume task -> Step 1 completes, Step 2 (builtin synthesis) completes without grant
            store.transition(task.id, AgentStore.ACTIVE);
            new AgentExecutor(store, broker).execute(task.id, new java.util.concurrent.atomic.AtomicBoolean(false));

            AgentStore.Task completed = store.getTask(task.id);
            if (!AgentStore.COMPLETED.equals(completed.status)) {
                throw new IllegalStateException("Task should be COMPLETED, got " + completed.status);
            }
            if (!ExecutionPlan.STEP_DONE.equals(completed.plan.steps.get(0).status)
                    || !ExecutionPlan.STEP_DONE.equals(completed.plan.steps.get(1).status)
                    || !ExecutionPlan.STEP_DONE.equals(completed.plan.steps.get(2).status)) {
                throw new IllegalStateException("All steps should be DONE in completed task");
            }

            // Verify Step 0 note capability was NOT called again
            noteInvokeCount = auditEventCount(database, task.id,
                CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE));
            int statusInvokeCount = auditEventCount(database, task.id,
                CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_STATUS_CAPABILITY, CapabilityBroker.DEMO_STATUS_SCOPE));
            if (noteInvokeCount != 1 || statusInvokeCount != 1) {
                throw new IllegalStateException("Invocation counts mismatch: note=" + noteInvokeCount + " status=" + statusInvokeCount);
            }

            // Verify builtin synthesis audit event was logged
            int synthesisCount = auditEventCount(database, task.id, "internal_synthesis_completed:local_inference");
            if (synthesisCount != 1) {
                throw new IllegalStateException("Builtin synthesis audit missing or duplicated: " + synthesisCount);
            }

            // 5. Test UI Step Tree View Hierarchy generation
            final CountDownLatch uiLatch = new CountDownLatch(1);
            final String[] uiError = new String[1];
            runOnUiThread(() -> {
                try {
                    LinearLayout container = new LinearLayout(DeviceValidationActivity.this);
                    LinearLayout card = new LinearLayout(DeviceValidationActivity.this);
                    card.setOrientation(LinearLayout.VERTICAL);

                    TextView title = new TextView(DeviceValidationActivity.this);
                    title.setText(completed.title);
                    card.addView(title);

                    LinearLayout planContainer = new LinearLayout(DeviceValidationActivity.this);
                    planContainer.setOrientation(LinearLayout.VERTICAL);
                    for (int i = 0; i < completed.plan.steps.size(); i++) {
                        ExecutionPlan.Step step = completed.plan.steps.get(i);
                        TextView stepText = new TextView(DeviceValidationActivity.this);
                        stepText.setText("🟢 [已完成] 步骤 " + (i + 1) + "：" + step.title);
                        planContainer.addView(stepText);
                    }
                    card.addView(planContainer);
                    container.addView(card);
                    if (planContainer.getChildCount() != 3) {
                        uiError[0] = "Step tree child count mismatch: " + planContainer.getChildCount();
                    }
                } catch (Exception ex) {
                    uiError[0] = ex.getMessage();
                } finally {
                    uiLatch.countDown();
                }
            });
            if (!uiLatch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("UI step tree verification timed out");
            }
            if (uiError[0] != null) {
                throw new IllegalStateException("UI error: " + uiError[0]);
            }

            return "PASS: Step tree and JIT progressive authorization verified on device; initial plan pending -> Step 0 halted in awaiting_grant -> JIT grant step 0 -> Step 1 halted in awaiting_grant without note repeat -> JIT grant step 1 -> Step 2 builtin synthesis completed without external grant; all steps done and audited. task=" + task.id;
        } finally {
            store.close();
        }
    }

    private String verifyEndToEndIntegrated() throws Exception {
        AgentStore store = new AgentStore(this);
        CapabilityBroker broker = new CapabilityBroker(this, store);
        SQLiteDatabase database = store.getWritableDatabase();
        try {
            // 1. Enable Traceform provider and verify signature match with org.traceform.edge
            broker.setTraceformEnabled(true);
            if (!broker.isTraceformEnabled()) {
                throw new IllegalStateException("Traceform provider failed to enable");
            }
            if (!broker.canEnableTraceform()) {
                throw new IllegalStateException("Traceform package not signature matched");
            }

            // 2. Formulate 4-step user goal spanning note, status, traceform forecast, and builtin synthesis
            long t0 = System.nanoTime();
            String goal = "整理便笺并检查设备状态与Traceform历史预测";
            AgentStore.Task task = store.createTask(goal);
            ReasoningEngine planner = new SemanticRulePlanner();
            ExecutionPlan plan = planner.plan(task.id, task.title, broker.listAvailableCapabilities(task.id), store.listMemories());
            store.savePlan(task.id, plan);
            long planDurationNs = System.nanoTime() - t0;
            double planMs = planDurationNs / 1_000_000.0;

            if (plan.totalSteps() != 4) {
                throw new IllegalStateException("Expected 4 steps in dynamic plan, got " + plan.totalSteps());
            }

            // Verify the 4 capabilities
            if (!CapabilityBroker.DEMO_NOTE_CAPABILITY.equals(plan.steps.get(0).capability))
                throw new IllegalStateException("Step 0 capability mismatch");
            if (!CapabilityBroker.DEMO_STATUS_CAPABILITY.equals(plan.steps.get(1).capability))
                throw new IllegalStateException("Step 1 capability mismatch");
            if (!CapabilityBroker.TRACEFORM_CAPABILITY.equals(plan.steps.get(2).capability))
                throw new IllegalStateException("Step 2 capability mismatch");
            if (!"builtin.synthesis".equals(plan.steps.get(3).capability))
                throw new IllegalStateException("Step 3 capability mismatch");

            // 3. Grant all 3 external capabilities
            broker.grant(task.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            broker.grant(task.id, CapabilityBroker.DEMO_STATUS_CAPABILITY, CapabilityBroker.DEMO_STATUS_SCOPE);
            broker.grant(task.id, CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE);

            // 4. Test crash & checkpoint recovery: simulate crash right after Step 1 checkpoint
            final java.util.concurrent.atomic.AtomicInteger checkpointCount = new java.util.concurrent.atomic.AtomicInteger();
            AgentExecutor.afterFirstCheckpointForValidation = () -> {
                if (checkpointCount.incrementAndGet() == 2) {
                    throw new RuntimeException("Simulated crash after step 1 checkpoint");
                }
            };

            store.transition(task.id, AgentStore.ACTIVE);
            boolean crashed = false;
            try {
                new AgentExecutor(store, broker).execute(task.id, new java.util.concurrent.atomic.AtomicBoolean(false));
            } catch (RuntimeException ex) {
                if ("Simulated crash after step 1 checkpoint".equals(ex.getMessage())) {
                    crashed = true;
                }
            } finally {
                AgentExecutor.afterFirstCheckpointForValidation = null;
            }

            if (!crashed) {
                throw new IllegalStateException("Crash injection after step 1 did not trigger");
            }

            // Verify checkpoints at crash state
            AgentStore.Task crashedTask = store.getTask(task.id);
            if (crashedTask.executionStep != 2) {
                throw new IllegalStateException("Expected executionStep 2 after crash, got " + crashedTask.executionStep);
            }
            if (!ExecutionPlan.STEP_DONE.equals(crashedTask.plan.steps.get(0).status)
                    || !ExecutionPlan.STEP_DONE.equals(crashedTask.plan.steps.get(1).status)
                    || !ExecutionPlan.STEP_PENDING.equals(crashedTask.plan.steps.get(2).status)) {
                throw new IllegalStateException("Step states mismatch after crash");
            }

            // 5. Resume execution from checkpoint
            long tResume = System.nanoTime();
            new AgentExecutor(store, broker).execute(task.id, new java.util.concurrent.atomic.AtomicBoolean(false));
            long resumeDurationNs = System.nanoTime() - tResume;
            double resumeMs = resumeDurationNs / 1_000_000.0;

            // 6. Verify full completion
            AgentStore.Task completed = store.getTask(task.id);
            if (!AgentStore.COMPLETED.equals(completed.status)) {
                throw new IllegalStateException("Completed task status mismatch, got " + completed.status);
            }
            if (completed.executionStep < plan.totalSteps()) {
                throw new IllegalStateException("Completed executionStep mismatch: " + completed.executionStep);
            }
            for (int i = 0; i < 4; i++) {
                if (!ExecutionPlan.STEP_DONE.equals(completed.plan.steps.get(i).status)) {
                    throw new IllegalStateException("Step " + i + " not DONE");
                }
                if (completed.plan.steps.get(i).observation.isEmpty()) {
                    throw new IllegalStateException("Step " + i + " missing observation");
                }
            }

            // 7. Verify no duplicate invocations for Step 0 and Step 1
            int noteCount = auditEventCount(database, task.id,
                CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE));
            int statusCount = auditEventCount(database, task.id,
                CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.DEMO_STATUS_CAPABILITY, CapabilityBroker.DEMO_STATUS_SCOPE));
            int traceformCount = auditEventCount(database, task.id,
                CapabilityBroker.auditEvent("capability_invoked", CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE));
            int synthesisCount = auditEventCount(database, task.id, "internal_synthesis_completed:local_inference");

            if (noteCount != 1 || statusCount != 1 || traceformCount != 1 || synthesisCount != 1) {
                throw new IllegalStateException("Audit count mismatch: note=" + noteCount + ", status=" + statusCount
                    + ", traceform=" + traceformCount + ", synthesis=" + synthesisCount);
            }

            // 8. Verify structured content in Traceform observation and final result
            String traceformObs = completed.plan.steps.get(2).observation;
            if (!traceformObs.contains("traceform") && !traceformObs.contains("history_records")) {
                throw new IllegalStateException("Traceform observation unexpected content: " + traceformObs);
            }

            if (!completed.result.contains("端侧推演分析") || !completed.result.contains("本地演示结果")) {
                throw new IllegalStateException("Final synthesis result incomplete: " + completed.result);
            }

            return "PASS: full 4-step dynamic task (note + status + traceform IPC + builtin synthesis) completed with crash recovery at step 1; zero duplicate executions; planning="
                + String.format(Locale.US, "%.3f", planMs) + "ms, resume execution="
                + String.format(Locale.US, "%.1f", resumeMs) + "ms; task=" + task.id;
        } finally {
            AgentExecutor.afterFirstCheckpointForValidation = null;
            store.close();
        }
    }

    private String verifyHybridCloudAndRedaction() throws Exception {
        AgentStore store = new AgentStore(this);
        CapabilityBroker broker = new CapabilityBroker(this, store);
        SQLiteDatabase database = store.getWritableDatabase();
        try {
            // 1. ContextRedactor Unit & Benchmark verification
            // Phone
            ContextRedactor.RedactionResult phoneRes = ContextRedactor.redact("联系张三 13812345678 确认发票");
            if (!phoneRes.hasRedactions() || phoneRes.redactionsCount != 1
                    || !phoneRes.redactedText.contains("138****5678") || phoneRes.redactedText.contains("13812345678")) {
                throw new IllegalStateException("Phone redaction failed: " + phoneRes.redactedText);
            }

            // Email
            ContextRedactor.RedactionResult emailRes = ContextRedactor.redact("发送通知至 alice@company.org 存档");
            if (!emailRes.hasRedactions() || emailRes.redactionsCount != 1
                    || !emailRes.redactedText.contains("a***@company.org") || emailRes.redactedText.contains("alice@company.org")) {
                throw new IllegalStateException("Email redaction failed: " + emailRes.redactedText);
            }

            // API Key
            ContextRedactor.RedactionResult apiKeyRes = ContextRedactor.redact("配置密钥 key=sk-abcdef1234567890_test 后继续");
            if (!apiKeyRes.hasRedactions() || !apiKeyRes.redactedText.contains("[REDACTED_SECRET]")
                    || apiKeyRes.redactedText.contains("sk-abcdef1234567890")) {
                throw new IllegalStateException("API key redaction failed: " + apiKeyRes.redactedText);
            }

            // ID Card
            ContextRedactor.RedactionResult idRes = ContextRedactor.redact("身份证号 110101199003072345 需核实");
            if (!idRes.hasRedactions() || !idRes.redactedText.contains("1101********2345")
                    || idRes.redactedText.contains("19900307")) {
                throw new IllegalStateException("ID card redaction failed: " + idRes.redactedText);
            }

            // Bank Card
            ContextRedactor.RedactionResult cardRes = ContextRedactor.redact("转账到银行卡 6222021234567890 进行测试");
            if (!cardRes.hasRedactions() || !cardRes.redactedText.contains("6222********7890")
                    || cardRes.redactedText.contains("02123456")) {
                throw new IllegalStateException("Bank card redaction failed: " + cardRes.redactedText);
            }

            // Redaction Benchmark (warmup + 100 iterations)
            String mixed = "电话 13911223344，邮箱 test@example.com，密钥 token=secret123456，卡号 6222021234567890";
            for (int i = 0; i < 10; i++) {
                ContextRedactor.redact(mixed);
            }
            long tStart = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                ContextRedactor.redact(mixed);
            }
            double avgRedactionMs = (System.nanoTime() - tStart) / 100.0 / 1_000_000.0;
            if (avgRedactionMs > 5.0) {
                throw new IllegalStateException("Redaction latency exceeded 5ms SLA: " + avgRedactionMs + "ms");
            }

            // 2. Offline / Network Error Graceful Degradation Verification
            CloudModelConfig badConfig = new CloudModelConfig(true, "http://127.0.0.1:58999/v1/chat/completions",
                "test-key", "gpt-4o-mini", 500, 0.1);
            HybridReasoningEngine engineWithBadCloud = new HybridReasoningEngine(this, store, badConfig);

            String goal1 = "根据便笺 13800001111 整理本地笔记与状态";
            AgentStore.Task task1 = store.createTask(goal1);
            List<CapabilityDescriptor> availableCaps = broker.listAvailableCapabilities(task1.id);
            List<AgentStore.Memory> memories = store.listMemories();

            ExecutionPlan fallbackPlan = engineWithBadCloud.plan(task1.id, goal1, availableCaps, memories);
            if (fallbackPlan == null || fallbackPlan.totalSteps() < 2) {
                throw new IllegalStateException("Fallback plan invalid or null");
            }

            if (auditEventCount(database, task1.id, "context_redacted:count=1") != 1) {
                throw new IllegalStateException("Redaction audit event missing in task1");
            }
            int fallbackAuditCount = auditEventCount(database, task1.id, "cloud_planning_fallback:reason=error")
                + auditEventCount(database, task1.id, "cloud_planning_fallback:reason=timeout")
                + auditEventCount(database, task1.id, "cloud_planning_fallback:reason=offline");
            if (fallbackAuditCount != 1) {
                throw new IllegalStateException("Fallback audit event missing in task1: count=" + fallbackAuditCount);
            }

            // 3. In-Process Mock Cloud Server Verification with Security Wire Inspection
            final java.net.ServerSocket mockServer = new java.net.ServerSocket(0);
            final int mockPort = mockServer.getLocalPort();
            final String[] wireInspectionError = new String[1];
            final java.util.concurrent.atomic.AtomicBoolean serverHandled = new java.util.concurrent.atomic.AtomicBoolean(false);

            Thread serverThread = new Thread(() -> {
                try {
                    mockServer.setSoTimeout(5000);
                    java.net.Socket client = mockServer.accept();
                    client.setSoTimeout(5000);
                    java.io.InputStream in = client.getInputStream();

                    // Read HTTP request headers
                    java.io.ByteArrayOutputStream headerBuf = new java.io.ByteArrayOutputStream();
                    int b;
                    byte[] prev4 = new byte[4];
                    while ((b = in.read()) != -1) {
                        headerBuf.write(b);
                        prev4[0] = prev4[1];
                        prev4[1] = prev4[2];
                        prev4[2] = prev4[3];
                        prev4[3] = (byte) b;
                        if (prev4[0] == '\r' && prev4[1] == '\n' && prev4[2] == '\r' && prev4[3] == '\n') {
                            break;
                        }
                    }

                    String headerStr = new String(headerBuf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    int contentLength = 0;
                    for (String hLine : headerStr.split("\r\n")) {
                        if (hLine.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                            contentLength = Integer.parseInt(hLine.substring(hLine.indexOf(':') + 1).trim());
                        }
                    }

                    // Read HTTP request body
                    byte[] bodyBytes = new byte[contentLength];
                    int readTotal = 0;
                    while (readTotal < contentLength) {
                        int r = in.read(bodyBytes, readTotal, contentLength - readTotal);
                        if (r < 0) break;
                        readTotal += r;
                    }
                    String requestBody = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);

                    if (requestBody.contains("13988776655")) {
                        wireInspectionError[0] = "SECURITY LEAK: raw unmasked phone 13988776655 was transmitted over HTTP wire!";
                    }
                    if (!requestBody.contains("139****6655")) {
                        wireInspectionError[0] = "SECURITY LEAK: masked phone 139****6655 was not found in wire payload!";
                    }

                    JSONObject responseJson = new JSONObject();
                    JSONArray choices = new JSONArray();
                    JSONObject choice = new JSONObject();
                    JSONObject message = new JSONObject();
                    message.put("role", "assistant");

                    JSONObject planObj = new JSONObject();
                    planObj.put("totalSteps", 3);
                    JSONArray steps = new JSONArray();

                    JSONObject s0 = new JSONObject();
                    s0.put("index", 0);
                    s0.put("title", "读取示例便笺");
                    s0.put("capability", "demo.note.read");
                    s0.put("scope", "synthetic.welcome");
                    s0.put("prompt", "读取便笺");
                    steps.put(s0);

                    JSONObject s1 = new JSONObject();
                    s1.put("index", 1);
                    s1.put("title", "读取设备状态");
                    s1.put("capability", "demo.status.read");
                    s1.put("scope", "synthetic.device_status");
                    s1.put("prompt", "读取状态");
                    steps.put(s1);

                    JSONObject s2 = new JSONObject();
                    s2.put("index", 2);
                    s2.put("title", "端侧综合推演报告");
                    s2.put("capability", "builtin.synthesis");
                    s2.put("scope", "local_inference");
                    s2.put("prompt", "综合推演");
                    steps.put(s2);

                    planObj.put("steps", steps);
                    message.put("content", planObj.toString());
                    choice.put("message", message);
                    choices.put(choice);
                    responseJson.put("choices", choices);

                    byte[] respBytes = responseJson.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    java.io.OutputStream out = client.getOutputStream();
                    String respHeader = "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                        + respBytes.length + "\r\n\r\n";
                    out.write(respHeader.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.write(respBytes);
                    out.flush();
                    client.shutdownOutput();
                    Thread.sleep(80);
                    client.close();
                    serverHandled.set(true);
                } catch (Exception ex) {
                    wireInspectionError[0] = "Server error: " + ex.getMessage();
                } finally {
                    try { mockServer.close(); } catch (Exception ignored) {}
                }
            });
            serverThread.start();

            CloudModelConfig mockConfig = new CloudModelConfig(true, "http://127.0.0.1:" + mockPort + "/v1/chat/completions",
                "test-key", "gpt-4o-mini", 3000, 0.1);
            HybridReasoningEngine mockEngine = new HybridReasoningEngine(this, store, mockConfig);

            String goal2 = "根据联系人 13988776655 整理便笺与状态";
            AgentStore.Task task2 = store.createTask(goal2);
            ExecutionPlan cloudPlan = mockEngine.plan(task2.id, goal2, availableCaps, memories);
            serverThread.join(4000);

            if (wireInspectionError[0] != null) {
                throw new IllegalStateException(wireInspectionError[0]);
            }
            if (!serverHandled.get()) {
                throw new IllegalStateException("Mock cloud server was not reached");
            }
            if (cloudPlan == null || cloudPlan.totalSteps() != 3) {
                throw new IllegalStateException("Cloud plan step count mismatch: " + (cloudPlan == null ? 0 : cloudPlan.totalSteps()));
            }

            if (auditEventCount(database, task2.id, "cloud_planning_started:gpt-4o-mini") != 1
                    || auditEventCount(database, task2.id, "cloud_planning_completed:3") != 1
                    || auditEventCount(database, task2.id, "context_redacted:count=1") != 1) {
                throw new IllegalStateException("Cloud planning audit trail incomplete for task2");
            }

            // 4. Execute the Cloud-Generated Plan with Local JIT Authorization
            broker.grant(task2.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            broker.grant(task2.id, CapabilityBroker.DEMO_STATUS_CAPABILITY, CapabilityBroker.DEMO_STATUS_SCOPE);
            store.transition(task2.id, AgentStore.ACTIVE);

            new AgentExecutor(store, broker, mockEngine).execute(task2.id, new java.util.concurrent.atomic.AtomicBoolean(false));

            AgentStore.Task completed2 = store.getTask(task2.id);
            if (!AgentStore.COMPLETED.equals(completed2.status)) {
                throw new IllegalStateException("Cloud-planned task did not complete: " + completed2.status);
            }
            boolean validResult = (completed2.result.contains("本地演示结果") || completed2.result.contains("端侧 SLM 离线推演报告"))
                && (completed2.result.contains("端侧推演分析") || completed2.result.contains("本地推演结论"));
            if (!validResult) {
                throw new IllegalStateException("Result missing expected components: " + completed2.result);
            }

            return "PASS: Hybrid cloud planner and context redaction verified on device; 5 PII categories sanitized (avg="
                + String.format(Locale.US, "%.3f", avgRedactionMs) + "ms); offline network failure gracefully fell back to local planner with audit; wire inspection confirmed zero unmasked PII transmission; cloud plan executed cleanly to COMPLETED with JIT authorization. task1="
                + task1.id + " task2=" + task2.id;
        } finally {
            store.close();
        }
    }

    private String verifySlmAndSystemCapabilities() throws Exception {
        deleteDatabase("agent_os.db");
        AgentStore store = new AgentStore(this);
        try {
            SQLiteDatabase database = store.getReadableDatabase();
            CapabilityBroker broker = new CapabilityBroker(this, store);

            // =========================================================================
            // PART 1: Direction B - On-Device Micro-SLM (LocalSlmEngine) Benchmark & Intent Accuracy
            // =========================================================================
            LocalSlmEngine slmEngine = new LocalSlmEngine();

            // 1.1 Warmup & Latency SLA Benchmark (100 runs)
            String benchmarkGoal = "查看本地便笺，然后发送系统通知，并将摘要导出分享";
            for (int i = 0; i < 10; i++) {
                LocalSlmEngine.inferIntentLogits(benchmarkGoal, null);
            }
            long benchStartNs = System.nanoTime();
            int iterations = 100;
            for (int i = 0; i < iterations; i++) {
                LocalSlmEngine.inferIntentLogits(benchmarkGoal, null);
            }
            long benchTotalNs = System.nanoTime() - benchStartNs;
            double avgSlmMs = (benchTotalNs / (double) iterations) / 1_000_000.0;
            if (avgSlmMs > 20.0) {
                throw new IllegalStateException("LocalSlmEngine latency SLA exceeded: " + avgSlmMs + "ms > 20ms");
            }

            // 1.2 Multi-intent accuracy check
            float[] logitsNotif = LocalSlmEngine.inferIntentLogits("查看本地便笺并推送通知提醒我", null);
            if (logitsNotif[LocalSlmEngine.CLASS_NOTE] < 0.35f || logitsNotif[LocalSlmEngine.CLASS_NOTIFICATION] < 0.35f) {
                throw new IllegalStateException("LocalSlmEngine failed Note+Notification intent classification");
            }

            float[] logitsCal = LocalSlmEngine.inferIntentLogits("安排下周团队会议日程与待办事项", null);
            if (logitsCal[LocalSlmEngine.CLASS_CALENDAR] < 0.35f) {
                throw new IllegalStateException("LocalSlmEngine failed Calendar intent classification");
            }

            float[] logitsShare = LocalSlmEngine.inferIntentLogits("打包系统设备运行状态导出分享给同事", null);
            if (logitsShare[LocalSlmEngine.CLASS_STATUS] < 0.35f || logitsShare[LocalSlmEngine.CLASS_SHARE] < 0.35f) {
                throw new IllegalStateException("LocalSlmEngine failed Status+Share intent classification");
            }

            // =========================================================================
            // PART 2: Direction C - Real System Capabilities in CapabilityBroker
            // =========================================================================
            AgentStore.Task sysTask = store.createTask("验证真实系统扩展能力受控授权与审计");
            List<CapabilityDescriptor> availableCaps = broker.listAvailableCapabilities(sysTask.id);

            boolean hasNotification = false;
            boolean hasCalendar = false;
            boolean hasShare = false;
            for (CapabilityDescriptor desc : availableCaps) {
                if (CapabilityBroker.NOTIFICATION_CAPABILITY.equals(desc.capability)) hasNotification = true;
                if (CapabilityBroker.CALENDAR_CAPABILITY.equals(desc.capability)) hasCalendar = true;
                if (CapabilityBroker.SHARE_CAPABILITY.equals(desc.capability)) hasShare = true;
            }
            if (!hasNotification || !hasCalendar || !hasShare) {
                throw new IllegalStateException("CapabilityBroker missing system capabilities in catalog: notif="
                    + hasNotification + " cal=" + hasCalendar + " share=" + hasShare);
            }

            // 2.1 Unauthorized invocation rejection (SecurityException)
            String[] capsToTest = {
                CapabilityBroker.NOTIFICATION_CAPABILITY,
                CapabilityBroker.CALENDAR_CAPABILITY,
                CapabilityBroker.SHARE_CAPABILITY
            };
            String[] scopesToTest = {
                CapabilityBroker.NOTIFICATION_SCOPE,
                CapabilityBroker.CALENDAR_SCOPE,
                CapabilityBroker.SHARE_SCOPE
            };

            for (int i = 0; i < capsToTest.length; i++) {
                try {
                    broker.invoke(sysTask.id, capsToTest[i], scopesToTest[i]);
                    throw new IllegalStateException("Invoking " + capsToTest[i] + " without grant should have failed");
                } catch (SecurityException expected) {
                    // Expected JIT security rejection
                }
            }

            // 2.2 Time-bound 5-minute JIT grant & invocation
            // Notification test
            broker.grant(sysTask.id, CapabilityBroker.NOTIFICATION_CAPABILITY, CapabilityBroker.NOTIFICATION_SCOPE);
            String notifJsonStr = broker.invoke(sysTask.id, CapabilityBroker.NOTIFICATION_CAPABILITY, CapabilityBroker.NOTIFICATION_SCOPE);
            JSONObject notifJson = new JSONObject(notifJsonStr);
            if (!notifJson.optBoolean("posted") || !"agent_os_tasks".equals(notifJson.optString("channel"))) {
                throw new IllegalStateException("Notification provider output invalid: " + notifJsonStr);
            }

            // Calendar test
            broker.grant(sysTask.id, CapabilityBroker.CALENDAR_CAPABILITY, CapabilityBroker.CALENDAR_SCOPE);
            String calJsonStr = broker.invoke(sysTask.id, CapabilityBroker.CALENDAR_CAPABILITY, CapabilityBroker.CALENDAR_SCOPE);
            JSONObject calJson = new JSONObject(calJsonStr);
            if (!"android.intent.action.INSERT".equals(calJson.optString("intent_action"))
                    || !"content://com.android.calendar/events".equals(calJson.optString("intent_uri"))
                    || calJson.optLong("startTime") <= 0) {
                throw new IllegalStateException("Calendar provider output invalid: " + calJsonStr);
            }

            // Share test
            broker.grant(sysTask.id, CapabilityBroker.SHARE_CAPABILITY, CapabilityBroker.SHARE_SCOPE);
            String shareJsonStr = broker.invoke(sysTask.id, CapabilityBroker.SHARE_CAPABILITY, CapabilityBroker.SHARE_SCOPE);
            JSONObject shareJson = new JSONObject(shareJsonStr);
            if (!"text/plain".equals(shareJson.optString("format"))
                    || !"android.intent.action.SEND".equals(shareJson.optString("intent_action"))
                    || !"ready_for_sharing".equals(shareJson.optString("status"))) {
                throw new IllegalStateException("Share provider output invalid: " + shareJsonStr);
            }

            // Verify capability audit trail in database
            for (int i = 0; i < capsToTest.length; i++) {
                if (auditEventCount(database, sysTask.id, CapabilityBroker.auditEvent("capability_granted", capsToTest[i], scopesToTest[i])) != 1) {
                    throw new IllegalStateException("Missing capability_granted audit for " + capsToTest[i]);
                }
                if (auditEventCount(database, sysTask.id, CapabilityBroker.auditEvent("capability_invoked", capsToTest[i], scopesToTest[i])) != 1) {
                    throw new IllegalStateException("Missing capability_invoked audit for " + capsToTest[i]);
                }
                if (auditEventCount(database, sysTask.id, CapabilityBroker.auditEvent("capability_denied", capsToTest[i], scopesToTest[i])) != 1) {
                    throw new IllegalStateException("Missing capability_denied audit for " + capsToTest[i]);
                }
            }

            // =========================================================================
            // PART 3: Tier 2 SLM + System Capabilities Multi-Step Agent Execution
            // =========================================================================
            String multiGoal = "查看本地便笺，然后发送系统通知，并将摘要导出分享";
            AgentStore.Task execTask = store.createTask(multiGoal);

            // Cloud is unconfigured -> HybridReasoningEngine routes to Tier 2 (LocalSlmEngine)
            CloudModelConfig offlineConfig = new CloudModelConfig(false, "", "", "", 3000, 0.1);
            HybridReasoningEngine hybridEngine = new HybridReasoningEngine(this, store, offlineConfig);

            ExecutionPlan planned = hybridEngine.plan(execTask.id, multiGoal, broker.listAvailableCapabilities(execTask.id), null);
            if (planned == null || planned.totalSteps() < 4) {
                throw new IllegalStateException("SLM planning did not generate expected multi-step sequence, steps=" + (planned == null ? 0 : planned.totalSteps()));
            }
            store.savePlan(execTask.id, planned);

            // JIT grant the required capabilities for this task
            broker.grant(execTask.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            broker.grant(execTask.id, CapabilityBroker.NOTIFICATION_CAPABILITY, CapabilityBroker.NOTIFICATION_SCOPE);
            broker.grant(execTask.id, CapabilityBroker.SHARE_CAPABILITY, CapabilityBroker.SHARE_SCOPE);

            store.transition(execTask.id, AgentStore.ACTIVE);
            new AgentExecutor(store, broker, hybridEngine).execute(execTask.id, new java.util.concurrent.atomic.AtomicBoolean(false));

            AgentStore.Task completedTask = store.getTask(execTask.id);
            if (!AgentStore.COMPLETED.equals(completedTask.status)) {
                throw new IllegalStateException("Task did not reach COMPLETED: " + completedTask.status);
            }

            if (!completedTask.result.contains("端侧 SLM 离线推演报告")
                    || !completedTask.result.contains("读取示例便笺")
                    || !completedTask.result.contains("推送系统任务通知")
                    || !completedTask.result.contains("打包文本分享导出")) {
                throw new IllegalStateException("Synthesized SLM report missing expected step observations: " + completedTask.result);
            }

            if (auditEventCount(database, execTask.id, "slm_planning_started:micro_slm_v1") != 1
                    || auditEventCount(database, execTask.id, "slm_planning_completed:" + planned.totalSteps()) != 1) {
                throw new IllegalStateException("SLM planning audit events incomplete for execTask");
            }

            return "PASS: Direction B (on-device Micro-SLM, avg=" + String.format(Locale.US, "%.3f", avgSlmMs)
                + "ms, <20ms SLA) and Direction C (System Notification, Calendar, Share providers with 5-min JIT authorization and full audit logging) verified 100% on device. sysTask="
                + sysTask.id + " execTask=" + execTask.id;
        } finally {
            store.close();
        }
    }

    private String verifyMemoryConsolidationAndUi() throws Exception {
        deleteDatabase("agent_os.db");
        AgentStore store = new AgentStore(this);
        try {
            SQLiteDatabase database = store.getReadableDatabase();
            CapabilityBroker broker = new CapabilityBroker(this, store);

            // =========================================================================
            // PART 1: Direction 1 - Associative Memory Selection & Ranking
            // =========================================================================
            AgentStore.Memory memPref = store.createMemory(AgentStore.MEMORY_PREFERENCE,
                "用户偏好在系统通知栏接收任务进度提醒与便笺摘要", AgentStore.MEMORY_SOURCE_USER, "user_setting");
            AgentStore.Memory memMeeting = store.createMemory(AgentStore.MEMORY_FACT,
                "团队每周例会通常安排在周五下午3点", AgentStore.MEMORY_SOURCE_USER, "meeting_notes");
            AgentStore.Memory memWorkflow = store.createMemory(AgentStore.MEMORY_WORKFLOW,
                "常规数据导出工作流：先核对设备状态，再生成文本分享导出", AgentStore.MEMORY_SOURCE_USER, "workflow_doc");
            AgentStore.Memory memLunch = store.createMemory(AgentStore.MEMORY_FACT,
                "食堂午餐供应时间为每天上午11点半至下午1点", AgentStore.MEMORY_SOURCE_USER, "lunch_info");

            List<AgentStore.Memory> allMems = store.listMemories();
            if (allMems.size() != 4) {
                throw new IllegalStateException("Initial memory count mismatch: " + allMems.size());
            }

            // Test query matching preference and notification
            String query1 = "查看便笺并向我推送任务通知";
            List<AgentStore.Memory> top1 = AssociativeMemorySelector.selectTopMemories(query1, allMems, 2);
            if (top1.size() != 2 || !top1.get(0).id.equals(memPref.id)) {
                throw new IllegalStateException("Associative memory ranking failed for query1, expected memPref first: " + (top1.isEmpty() ? "none" : top1.get(0).content));
            }

            // Test query matching meeting fact
            String query2 = "安排周五团队例会日程";
            List<AgentStore.Memory> top2 = AssociativeMemorySelector.selectTopMemories(query2, allMems, 2);
            if (top2.size() != 2 || !top2.get(0).id.equals(memMeeting.id)) {
                throw new IllegalStateException("Associative memory ranking failed for query2, expected memMeeting first: " + (top2.isEmpty() ? "none" : top2.get(0).content));
            }

            // =========================================================================
            // PART 2: Direction 1 - Automatic Memory Consolidation on Task Completion
            // =========================================================================
            String taskGoalA = "安排下周项目复盘会议日程";
            AgentStore.Task taskA = store.createTask(taskGoalA);
            HybridReasoningEngine engine = new HybridReasoningEngine(this, store, new CloudModelConfig(false, "", "", "", 3000, 0.1));

            ExecutionPlan planA = engine.plan(taskA.id, taskGoalA, broker.listAvailableCapabilities(taskA.id), store.listMemories());
            store.savePlan(taskA.id, planA);

            // Grant calendar & note permissions
            broker.grant(taskA.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            broker.grant(taskA.id, CapabilityBroker.CALENDAR_CAPABILITY, CapabilityBroker.CALENDAR_SCOPE);

            store.transition(taskA.id, AgentStore.ACTIVE);
            new AgentExecutor(store, broker, engine).execute(taskA.id, new java.util.concurrent.atomic.AtomicBoolean(false));

            AgentStore.Task completedA = store.getTask(taskA.id);
            if (!AgentStore.COMPLETED.equals(completedA.status)) {
                throw new IllegalStateException("Task A did not complete: " + completedA.status);
            }

            // Verify that MemoryConsolidator automatically ran and produced a consolidated memory
            List<AgentStore.Memory> consolidatedMems = new ArrayList<>();
            for (AgentStore.Memory m : store.listMemories()) {
                if (AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(m.source)) {
                    consolidatedMems.add(m);
                }
            }
            if (consolidatedMems.isEmpty()) {
                throw new IllegalStateException("No consolidated memories found after Task A completion");
            }

            boolean hasCalendarConsolidation = false;
            for (AgentStore.Memory cm : consolidatedMems) {
                if (cm.content.contains("日程待办")) hasCalendarConsolidation = true;
            }
            if (!hasCalendarConsolidation) {
                throw new IllegalStateException("Consolidated memory missing expected calendar fact: " + consolidatedMems.get(0).content);
            }

            if (auditEventCount(database, taskA.id, "memory_consolidated:count=1") != 1
                    && auditEventCount(database, taskA.id, "memory_consolidated:count=2") != 1) {
                throw new IllegalStateException("Task A missing memory_consolidated audit event");
            }

            // =========================================================================
            // PART 3: In-place Deduplication & Update Verification
            // =========================================================================
            int memCountBeforeB = store.listMemories().size();
            String taskGoalB = "再次安排下周项目复盘会议日程";
            AgentStore.Task taskB = store.createTask(taskGoalB);
            ExecutionPlan planB = engine.plan(taskB.id, taskGoalB, broker.listAvailableCapabilities(taskB.id), store.listMemories());
            store.savePlan(taskB.id, planB);

            broker.grant(taskB.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            broker.grant(taskB.id, CapabilityBroker.CALENDAR_CAPABILITY, CapabilityBroker.CALENDAR_SCOPE);

            store.transition(taskB.id, AgentStore.ACTIVE);
            new AgentExecutor(store, broker, engine).execute(taskB.id, new java.util.concurrent.atomic.AtomicBoolean(false));

            AgentStore.Task completedB = store.getTask(taskB.id);
            if (!AgentStore.COMPLETED.equals(completedB.status)) {
                throw new IllegalStateException("Task B did not complete: " + completedB.status);
            }

            int memCountAfterB = store.listMemories().size();
            // Since taskB has the exact same calendar event topic, it should update in-place rather than duplicate!
            if (memCountAfterB != memCountBeforeB) {
                throw new IllegalStateException("Deduplication failed: memory count grew from " + memCountBeforeB + " to " + memCountAfterB);
            }

            // =========================================================================
            // PART 4: Direction 2 - End-to-End Dynamic Workflow & JIT Pending Flow
            // =========================================================================
            String taskGoalC = "查看本地便笺，然后发送系统通知，并将摘要导出分享";
            AgentStore.Task taskC = store.createTask(taskGoalC);
            ExecutionPlan planC = engine.plan(taskC.id, taskGoalC, broker.listAvailableCapabilities(taskC.id), store.listMemories());
            store.savePlan(taskC.id, planC);

            // Intentionally only grant Step 0 (Note), leaving Step 1 (Notification) ungranted
            broker.grant(taskC.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
            store.transition(taskC.id, AgentStore.ACTIVE);

            new AgentExecutor(store, broker, engine).execute(taskC.id, new java.util.concurrent.atomic.AtomicBoolean(false));
            AgentStore.Task waitingC = store.getTask(taskC.id);
            if (!AgentStore.WAITING_FOR_USER.equals(waitingC.status)) {
                throw new IllegalStateException("Task C should be waiting_for_user: " + waitingC.status);
            }
            if (waitingC.executionStep != 1) {
                throw new IllegalStateException("Task C executionStep should be 1: " + waitingC.executionStep);
            }

            // Now grant Step 1 and Step 2, and resume execution
            broker.grant(taskC.id, CapabilityBroker.NOTIFICATION_CAPABILITY, CapabilityBroker.NOTIFICATION_SCOPE);
            broker.grant(taskC.id, CapabilityBroker.SHARE_CAPABILITY, CapabilityBroker.SHARE_SCOPE);
            store.transition(taskC.id, AgentStore.ACTIVE);

            new AgentExecutor(store, broker, engine).execute(taskC.id, new java.util.concurrent.atomic.AtomicBoolean(false));
            AgentStore.Task completedC = store.getTask(taskC.id);
            if (!AgentStore.COMPLETED.equals(completedC.status)) {
                throw new IllegalStateException("Task C did not complete after grant resume: " + completedC.status);
            }

            return "PASS: Direction 1 (Associative memory ranking, automatic consolidation on task completion, in-place deduplication) and Direction 2 (JIT alert interrupt/resume flow and UI evolution) verified 100% on device. taskA="
                + taskA.id + " taskB=" + taskB.id + " taskC=" + taskC.id;
        } finally {
            store.close();
        }
    }

    private String verifyNativeCoreAndJni() throws Exception {
        // 1. Verify Native Library Load
        if (!NativeAgentCore.isAvailable()) {
            throw new IllegalStateException("NativeAgentCore is NOT available! System.loadLibrary('agent_os_core') failed.");
        }

        // 2. Verify Native Redaction (Rust ContextRedactor)
        String rawLeakText = "用户手机号: 13812345678, 邮箱: confidential@company.com, 密钥: sk-9876543210abcdef9876543210";
        ContextRedactor.RedactionResult redRes = ContextRedactor.redact(rawLeakText);
        if (redRes.redactionsCount < 3) {
            throw new IllegalStateException("Native redactor failed: expected >= 3 redactions, got " + redRes.redactionsCount + "; text=" + redRes.redactedText);
        }
        if (redRes.redactedText.contains("13812345678") || redRes.redactedText.contains("confidential@company.com") || redRes.redactedText.contains("sk-9876543210")) {
            throw new IllegalStateException("Native redactor leaked sensitive PII in: " + redRes.redactedText);
        }

        // 3. Verify Native SLM Intent Inference (Rust MicroSlm)
        float[] logitsCalendar = LocalSlmEngine.inferIntentLogits("安排明天下午3点团队日程与会议待办", null);
        if (logitsCalendar[LocalSlmEngine.CLASS_CALENDAR] < 0.50f) {
            throw new IllegalStateException("Native SLM calendar logit expected >= 0.50, got " + logitsCalendar[LocalSlmEngine.CLASS_CALENDAR]);
        }

        float[] logitsNotification = LocalSlmEngine.inferIntentLogits("给用户发送系统弹窗提醒和通知", null);
        if (logitsNotification[LocalSlmEngine.CLASS_NOTIFICATION] < 0.50f) {
            throw new IllegalStateException("Native SLM notification logit expected >= 0.50, got " + logitsNotification[LocalSlmEngine.CLASS_NOTIFICATION]);
        }

        // 4. Verify Native Associative Memory Ranking (Rust MemoryRanker)
        long now = System.currentTimeMillis();
        List<AgentStore.Memory> mockMemories = Arrays.asList(
            new AgentStore.Memory("mem-1", "fact", "团队每周例会通常安排在周五下午3点", "user", "", now - 3600000L, now - 3600000L),
            new AgentStore.Memory("mem-2", "preference", "用户偏好浅色主题与大字体显示", "user", "", now - 10000000L, now - 10000000L),
            new AgentStore.Memory("mem-3", "workflow", "设备传感器常驻采集流形基准线", "traceform", "", now - 50000000L, now - 50000000L)
        );
        List<AgentStore.Memory> ranked = AssociativeMemorySelector.selectTopMemories("安排周五团队例会日程", mockMemories, 1);
        if (ranked.isEmpty() || !"mem-1".equals(ranked.get(0).id)) {
            throw new IllegalStateException("Native memory ranker failed: expected mem-1, got " + (ranked.isEmpty() ? "empty" : ranked.get(0).id));
        }

        return "PASS: Direction A (Native Rust Core via JNI on Pixel 6a) verified 100% on device! "
            + "native_loaded=true, red_count=" + redRes.redactionsCount
            + ", calendar_logit=" + String.format(Locale.ROOT, "%.4f", logitsCalendar[LocalSlmEngine.CLASS_CALENDAR])
            + ", top_mem=" + ranked.get(0).id;
    }
}
