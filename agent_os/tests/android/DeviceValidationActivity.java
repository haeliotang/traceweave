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
import android.widget.TextView;

import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
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
}
