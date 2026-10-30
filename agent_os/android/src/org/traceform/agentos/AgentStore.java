package org.traceform.agentos;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class AgentStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "agent_os.db";
    private static final int DATABASE_VERSION = 8;
    private static final String KEY_ALIAS = "org.traceform.agentos.local.v1";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_NONCE_BYTES = 12;
    private static final String MEMORY_PAYLOAD_FORMAT = "org.traceform.agentos.memory_record";

    static final String QUEUED = "queued";
    static final String ACTIVE = "active";
    static final String PAUSED = "paused";
    static final String WAITING_FOR_USER = "waiting_for_user";
    static final String FAILED = "failed";
    static final String COMPLETED = "completed";
    static final String CANCELLED = "cancelled";
    static final String OUTCOME_UNKNOWN = "outcome_unknown";
    static final int CURRENT_EXECUTION_VERSION = 2;
    static final int MAX_DYNAMIC_STEPS = 10;
    static final int MAX_AUDIT_EVENT_LENGTH = 2048;
    static final String MEMORY_PREFERENCE = "preference";
    static final String MEMORY_FACT = "fact";
    static final String MEMORY_WORKFLOW = "workflow";
    static final String MEMORY_SOURCE_USER = "user";
    static final String LOCAL_ACTOR = "local-device";
    static final String MEMORY_SOURCE_TRACEFORM = "traceform";
    static final String MEMORY_SOURCE_CONSOLIDATION = "agent_consolidation";

    static final class Task {
        final String id;
        final String title;
        final String status;
        final long createdAt;
        final long updatedAt;
        final int jobId;
        final int executionVersion;
        final int executionStep;
        final String checkpointNote;
        final String result;
        final ExecutionPlan plan;

        Task(String id, String title, String status, long createdAt, long updatedAt, int jobId,
                int executionVersion, int executionStep, String checkpointNote, String result,
                ExecutionPlan plan) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.jobId = jobId;
            this.executionVersion = executionVersion;
            this.executionStep = executionStep;
            this.checkpointNote = checkpointNote;
            this.result = result;
            this.plan = plan;
        }

        Task(String id, String title, String status, long createdAt, long updatedAt, int jobId,
                int executionVersion, int executionStep, String checkpointNote, String result) {
            this(id, title, status, createdAt, updatedAt, jobId, executionVersion, executionStep, checkpointNote, result, null);
        }

        Task(String id, String title, String status, long createdAt, long updatedAt) {
            this(id, title, status, createdAt, updatedAt, 0, 0, 0, "", "", null);
        }
    }

    static final class Memory {
        final String id;
        final String kind;
        final String content;
        final String source;
        final String sourceReference;
        final long createdAt;
        final long updatedAt;
        final long expiresAt;
        final boolean userEdited;

        Memory(String id, String kind, String content, String source, String sourceReference,
                long createdAt, long updatedAt) {
            this(id, kind, content, source, sourceReference, createdAt, updatedAt, 0);
        }

        Memory(String id, String kind, String content, String source, String sourceReference,
                long createdAt, long updatedAt, long expiresAt) {
            this(id, kind, content, source, sourceReference, createdAt, updatedAt, expiresAt, false);
        }

        Memory(String id, String kind, String content, String source, String sourceReference,
                long createdAt, long updatedAt, long expiresAt, boolean userEdited) {
            this.id = id;
            this.kind = kind;
            this.content = content;
            this.source = source;
            this.sourceReference = sourceReference;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.expiresAt = expiresAt;
            this.userEdited = userEdited;
        }

        boolean isExpired(long now) { return expiresAt > 0 && now >= expiresAt; }

        Memory(String id, String kind, String content, long createdAt, long updatedAt) {
            this(id, kind, content, MEMORY_SOURCE_USER, "", createdAt, updatedAt);
        }
    }

    static final class AuditEvent {
        final String id;
        final String taskId;
        final long occurredAt;
        final String event;

        AuditEvent(String id, String taskId, long occurredAt, String event) {
            this.id = id;
            this.taskId = taskId;
            this.occurredAt = occurredAt;
            this.event = event;
        }
    }

    static final class Tombstone {
        final String entityType;
        final String entityId;
        final long deletedAt;

        Tombstone(String entityType, String entityId, long deletedAt) {
            this.entityType = entityType;
            this.entityId = entityId;
            this.deletedAt = deletedAt;
        }
    }

    static final class ImportResult {
        final int tasksImported;
        final int memoriesImported;
        final int tombstonesApplied;
        final int memoryConflicts;
        final int memoriesReplaced;
        final int recordsSkipped;

        ImportResult(int tasksImported, int memoriesImported, int tombstonesApplied,
                int memoryConflicts, int memoriesReplaced, int recordsSkipped) {
            this.tasksImported = tasksImported;
            this.memoriesImported = memoriesImported;
            this.tombstonesApplied = tombstonesApplied;
            this.memoryConflicts = memoryConflicts;
            this.memoriesReplaced = memoriesReplaced;
            this.recordsSkipped = recordsSkipped;
        }
    }

    AgentStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        createTaskTables(database);
        createMemoryTables(database);
        createMemoryVersionTable(database);
        createPairingTable(database);
    }

    private static void createTaskTables(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE tasks ("
            + "id TEXT PRIMARY KEY NOT NULL, "
            + "created_at INTEGER NOT NULL, "
            + "updated_at INTEGER NOT NULL, "
            + "status TEXT NOT NULL, "
            + "job_id INTEGER NOT NULL, "
            + "execution_version INTEGER NOT NULL, "
            + "execution_step INTEGER NOT NULL, "
            + "sealed_payload BLOB NOT NULL)");
        database.execSQL("CREATE UNIQUE INDEX tasks_job_id_unique ON tasks(job_id)");
        database.execSQL("CREATE TABLE audit ("
            + "id TEXT PRIMARY KEY NOT NULL, "
            + "task_id TEXT NOT NULL, "
            + "occurred_at INTEGER NOT NULL, "
            + "event TEXT NOT NULL)");
    }

    private static void createMemoryTables(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE memories ("
            + "id TEXT PRIMARY KEY NOT NULL, "
            + "kind TEXT NOT NULL, "
            + "created_at INTEGER NOT NULL, "
            + "updated_at INTEGER NOT NULL, "
            + "sealed_payload BLOB NOT NULL)");
        database.execSQL("CREATE TABLE tombstones ("
            + "entity_type TEXT NOT NULL, "
            + "entity_id TEXT NOT NULL, "
            + "deleted_at INTEGER NOT NULL, "
            + "PRIMARY KEY (entity_type, entity_id))");
    }

    private static void createPairingTable(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE pairings ("
            + "device_id TEXT PRIMARY KEY NOT NULL, "
            + "fingerprint TEXT NOT NULL, "
            + "peer_id TEXT NOT NULL, "
            + "status TEXT NOT NULL, "
            + "paired_at INTEGER NOT NULL)");
    }

    private static void createMemoryVersionTable(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE memory_versions ("
            + "memory_id TEXT NOT NULL, "
            + "clock TEXT NOT NULL, "
            + "content_hash TEXT, "
            + "tombstone INTEGER NOT NULL, "
            + "PRIMARY KEY (memory_id, clock))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        int version = oldVersion;
        while (version < newVersion) {
            if (version == 1) {
                createMemoryTables(database);
                version = 2;
            } else if (version == 2) {
                upgradeTasksForExecutor(database);
                version = 3;
            } else if (version == 3) {
                upgradeMemoryProvenance(database);
                version = 4;
            } else if (version == 4) {
                upgradeExternalOutcomes(database);
                version = 5;
            } else if (version == 5) {
                // Gate older code from reopening task-context and document-receipt semantics.
                version = 6;
            } else if (version == 6) {
                createMemoryVersionTable(database);
                version = 7;
            } else if (version == 7) {
                createPairingTable(database);
                version = 8;
            } else {
                throw new IllegalStateException("No Agent OS database migration exists for version " + version);
            }
        }
    }

    private void upgradeExternalOutcomes(SQLiteDatabase database) {
        ArrayList<Task> historical = new ArrayList<>();
        try (Cursor cursor = database.query("tasks", new String[]{"id", "created_at", "updated_at", "status", "sealed_payload",
                "job_id", "execution_version", "execution_step"}, "status=?", new String[]{COMPLETED}, null, null, null)) {
            while (cursor.moveToNext()) historical.add(taskFromCursor(cursor));
        } catch (Exception error) { throw new IllegalStateException("Could not read historical task outcomes", error); }
        for (Task task : historical) {
            int index = firstUnverifiedAction(task.plan);
            if (index < 0) continue;
            try {
                ExecutionPlan plan = task.plan.withStepUpdated(index, unknownStep(task.plan.steps.get(index)), index);
                persistPlan(database, task, plan, OUTCOME_UNKNOWN, "historical_external_outcome_unknown:step_" + (index + 1));
                ContentValues values = new ContentValues();
                values.put("execution_step", index);
                database.update("tasks", values, "id=?", new String[]{task.id});
            } catch (Exception error) { throw new IllegalStateException("Could not preserve historical action outcome", error); }
        }
    }

    private static int firstUnverifiedAction(ExecutionPlan plan) {
        if (plan != null) {
            for (int index = 0; index < plan.totalSteps(); index++) {
                ExecutionPlan.Step step = plan.steps.get(index);
                if (step.isExternalAction() && !ExternalAction.hasVerifiedEffect(step)) return index;
            }
        }
        return -1;
    }

    private static ExecutionPlan.Step unknownStep(ExecutionPlan.Step step) {
        String observation = step.observation;
        try {
            JSONObject payload = new JSONObject(observation);
            payload.put("previousStatus", payload.optString("status", ""));
            payload.put("status", "outcome_unknown").put("verification", "none");
            observation = payload.toString();
        } catch (Exception ignored) { /* Preserve non-JSON historical observations. */ }
        return step.withStatusAndObservation(ExecutionPlan.STEP_UNKNOWN, observation, step.executedAt);
    }

    private void upgradeMemoryProvenance(SQLiteDatabase database) {
        ArrayList<Object[]> rows = new ArrayList<>();
        try (Cursor cursor = database.query("memories", new String[]{"id", "sealed_payload"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                rows.add(new Object[]{cursor.getString(0), cursor.getBlob(1)});
            }
        }
        for (Object[] row : rows) {
            String id = (String) row[0];
            byte[] sealed = (byte[]) row[1];
            try {
                Memory memory = memoryFromPayload(id, MEMORY_FACT, 0, 0, unseal(memoryAad(id), sealed));
                byte[] payload = memoryPayload(memory.content, memory.source, memory.sourceReference);
                ContentValues values = new ContentValues();
                values.put("sealed_payload", seal(memoryAad(id), payload));
                database.update("memories", values, "id=?", new String[]{id});
            } catch (Exception error) {
                throw new IllegalStateException("Could not preserve encrypted memory provenance", error);
            }
        }
    }

    private static void upgradeTasksForExecutor(SQLiteDatabase database) {
        database.execSQL("ALTER TABLE tasks ADD COLUMN job_id INTEGER NOT NULL DEFAULT 0");
        database.execSQL("ALTER TABLE tasks ADD COLUMN execution_version INTEGER NOT NULL DEFAULT 0");
        database.execSQL("ALTER TABLE tasks ADD COLUMN execution_step INTEGER NOT NULL DEFAULT 0");

        ArrayList<String> taskIds = new ArrayList<>();
        ArrayList<String> activeTaskIds = new ArrayList<>();
        try (Cursor cursor = database.query("tasks", new String[]{"id", "status"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                taskIds.add(cursor.getString(0));
                if (ACTIVE.equals(cursor.getString(1))) activeTaskIds.add(cursor.getString(0));
            }
        }
        Set<Integer> jobIds = new HashSet<>();
        for (String taskId : taskIds) {
            int jobId = allocateJobId(taskId, jobIds);
            ContentValues values = new ContentValues();
            values.put("job_id", jobId);
            database.update("tasks", values, "id=?", new String[]{taskId});
        }
        long now = System.currentTimeMillis();
        for (String taskId : activeTaskIds) {
            ContentValues values = new ContentValues();
            values.put("status", QUEUED);
            values.put("updated_at", now);
            database.update("tasks", values, "id=?", new String[]{taskId});
            appendAudit(database, taskId, "legacy_active_requeued_for_executor", now);
        }
        database.execSQL("CREATE UNIQUE INDEX tasks_job_id_unique ON tasks(job_id)");
    }

    @Override
    public void onDowngrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("Agent OS database downgrade is not supported");
    }

    Task createTask(String title) throws Exception {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Enter a task description");
        if (normalized.length() > 2000) throw new IllegalArgumentException("Task descriptions are limited to 2000 characters");

        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            int jobId = allocateJobId(database, id);
            JSONObject payload = new JSONObject().put("title", normalized)
                .put("checkpointNote", "").put("result", "");
            byte[] sealed = seal(id, payload.toString().getBytes(StandardCharsets.UTF_8));
            ContentValues values = new ContentValues();
            values.put("id", id);
            values.put("created_at", now);
            values.put("updated_at", now);
            values.put("status", QUEUED);
            values.put("job_id", jobId);
            values.put("execution_version", CURRENT_EXECUTION_VERSION);
            values.put("execution_step", 0);
            values.put("sealed_payload", sealed);
            database.insertOrThrow("tasks", null, values);
            appendAudit(database, id, "task_created", now);
            database.setTransactionSuccessful();
            return new Task(id, normalized, QUEUED, now, now, jobId,
                CURRENT_EXECUTION_VERSION, 0, "", "");
        } finally {
            database.endTransaction();
        }
    }

    List<Task> listTasks() throws Exception {
        ArrayList<Task> tasks = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "tasks", new String[]{"id", "created_at", "updated_at", "status", "sealed_payload",
                    "job_id", "execution_version", "execution_step"},
                null, null, null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) {
                tasks.add(taskFromCursor(cursor));
            }
        }
        return tasks;
    }

    Task getTask(String taskId) throws Exception {
        try (Cursor cursor = getReadableDatabase().query(
                "tasks", new String[]{"id", "created_at", "updated_at", "status", "sealed_payload",
                    "job_id", "execution_version", "execution_step"},
                "id=?", new String[]{taskId}, null, null, null)) {
            return cursor.moveToFirst() ? taskFromCursor(cursor) : null;
        }
    }

    private Task taskFromCursor(Cursor cursor) throws Exception {
        String id = cursor.getString(0);
        JSONObject payload = new JSONObject(new String(unseal(id, cursor.getBlob(4)), StandardCharsets.UTF_8));
        ExecutionPlan plan = payload.has("plan") ? ExecutionPlan.fromJson(payload.getJSONObject("plan")) : null;
        return new Task(id, payload.getString("title"), cursor.getString(3), cursor.getLong(1), cursor.getLong(2),
            cursor.getInt(5), cursor.getInt(6), cursor.getInt(7), payload.optString("checkpointNote", ""),
            payload.optString("result", ""), plan);
    }

    void transition(String taskId, String targetStatus) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            String currentStatus = null;
            int executionVersion = 0;
            try (Cursor cursor = database.query("tasks", new String[]{"status", "execution_version", "sealed_payload"}, "id=?",
                    new String[]{taskId}, null, null, null)) {
                if (cursor.moveToFirst()) {
                    currentStatus = cursor.getString(0);
                    executionVersion = cursor.getInt(1);
                }
            }
            if (currentStatus == null) throw new IllegalArgumentException("Task no longer exists");
            if (!isAllowedTransition(currentStatus, targetStatus))
                throw new IllegalStateException("This task cannot move from " + currentStatus + " to " + targetStatus);

            long now = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put("status", targetStatus);
            values.put("updated_at", now);
            Task current = getTask(taskId);
            if (ACTIVE.equals(targetStatus) && TaskContext.isDocument(current.plan) && current.plan.steps.isEmpty())
                throw new IllegalStateException("Review the document workflow before execution");
            if (current.plan != null && current.executionStep < current.plan.totalSteps()) {
                ExecutionPlan.Step step = current.plan.steps.get(current.executionStep);
                if (ACTIVE.equals(targetStatus) && step.isExternalAction()
                        && (ExecutionPlan.STEP_DRAFT_READY.equals(step.status)
                            || ExecutionPlan.STEP_HANDED_OFF.equals(step.status)
                            || ExecutionPlan.STEP_UNKNOWN.equals(step.status)))
                    throw new IllegalStateException("Review the external action; it cannot be replayed by resume");
                if (CANCELLED.equals(targetStatus) && step.isExternalAction()) {
                    boolean dispatched = ExecutionPlan.STEP_HANDED_OFF.equals(step.status);
                    String observation = step.observation;
                    if (dispatched && DocumentAction.CAPABILITY.equals(step.capability))
                        observation = new JSONObject(observation).put("trackingStopped", true).toString();
                    JSONObject payload = taskPayload(database, taskId);
                    ExecutionPlan updated = current.plan.withStepUpdated(current.executionStep,
                        step.withStatusAndObservation(dispatched ? ExecutionPlan.STEP_UNKNOWN : ExecutionPlan.STEP_CANCELLED,
                            observation, now), current.executionStep);
                    payload.put("plan", updated.toJson());
                    values.put("sealed_payload", seal(taskId, payload.toString().getBytes(StandardCharsets.UTF_8)));
                    if (dispatched) values.put("status", OUTCOME_UNKNOWN);
                }
            }
            if (ACTIVE.equals(targetStatus) && executionVersion == 0) {
                byte[] sealed = null;
                try (Cursor cursor = database.query("tasks", new String[]{"sealed_payload"}, "id=?",
                        new String[]{taskId}, null, null, null)) {
                    if (cursor.moveToFirst()) sealed = cursor.getBlob(0);
                }
                if (sealed == null) throw new IllegalArgumentException("Task no longer exists");
                JSONObject payload = new JSONObject(new String(unseal(taskId, sealed), StandardCharsets.UTF_8));
                payload.put("checkpointNote", "").put("result", "");
                payload.remove("plan");
                values.put("sealed_payload", seal(taskId, payload.toString().getBytes(StandardCharsets.UTF_8)));
                values.put("execution_version", CURRENT_EXECUTION_VERSION);
                values.put("execution_step", 0);
            }
            database.update("tasks", values, "id=?", new String[]{taskId});
            appendAudit(database, taskId, "status_" + values.getAsString("status"), now);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void recordStepStarted(String taskId, int step) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            requireActiveStep(database, taskId, step);
            appendAudit(database, taskId, "executor_step_" + (step + 1) + "_started", System.currentTimeMillis());
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void savePlan(String taskId, ExecutionPlan plan) throws Exception {
        if (plan == null) throw new IllegalArgumentException("Plan cannot be null");
        if (plan.totalSteps() > MAX_DYNAMIC_STEPS)
            throw new IllegalArgumentException("Execution plans are limited to " + MAX_DYNAMIC_STEPS + " steps");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            JSONObject payload = taskPayload(database, taskId);
            if (plan.context.isEmpty() && payload.has("plan")) {
                ExecutionPlan previous = ExecutionPlan.fromJson(payload.getJSONObject("plan"));
                plan = plan.withContext(previous.context);
            }
            payload.put("plan", plan.toJson());
            ContentValues values = new ContentValues();
            values.put("updated_at", System.currentTimeMillis());
            values.put("sealed_payload", seal(taskId, payload.toString().getBytes(StandardCharsets.UTF_8)));
            database.update("tasks", values, "id=?", new String[]{taskId});
            appendAudit(database, taskId, "plan_generated:" + plan.totalSteps(), System.currentTimeMillis());
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void saveStepCheckpoint(String taskId, int stepIndex, String observation) throws Exception {
        if (observation == null) observation = "";
        if (observation.length() > 12000)
            throw new IllegalArgumentException("Step observation exceeded size limit");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            requireActiveStep(database, taskId, stepIndex);
            JSONObject payload = taskPayload(database, taskId);
            ExecutionPlan plan = payload.has("plan") ? ExecutionPlan.fromJson(payload.getJSONObject("plan")) : null;
            if (plan != null && stepIndex < plan.totalSteps()) {
                ExecutionPlan.Step current = plan.steps.get(stepIndex);
                if (current.isExternalAction())
                    throw new IllegalStateException("An external action draft is not a completed effect");
                long now = System.currentTimeMillis();
                ExecutionPlan.Step updated = current.withStatusAndObservation(ExecutionPlan.STEP_DONE, observation, now);
                plan = plan.withStepUpdated(stepIndex, updated, stepIndex + 1);
                payload.put("plan", plan.toJson());
                if (stepIndex == 0 && CapabilityBroker.DEMO_NOTE_CAPABILITY.equals(current.capability)) {
                    payload.put("checkpointNote", observation);
                }
            } else {
                payload.put("checkpointNote", observation);
            }
            ContentValues values = new ContentValues();
            values.put("execution_step", stepIndex + 1);
            values.put("updated_at", System.currentTimeMillis());
            values.put("sealed_payload", seal(taskId, payload.toString().getBytes(StandardCharsets.UTF_8)));
            database.update("tasks", values, "id=?", new String[]{taskId});
            appendAudit(database, taskId, "executor_step_" + (stepIndex + 1) + "_saved", System.currentTimeMillis());
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void saveReviewedPlan(String taskId, ExecutionPlan plan, String expectedContextFingerprint) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = getTask(taskId);
            if (task == null || !QUEUED.equals(task.status) || !expectedContextFingerprint.equals(TaskContext.fingerprint(task)))
                throw new SecurityException("Task or context changed before the reviewed plan was saved");
            savePlan(taskId, plan);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    void prepareExternalDraft(String taskId, int stepIndex, String draft) throws Exception {
        if (draft == null || draft.length() > 128000) throw new IllegalArgumentException("Invalid action draft");
        new JSONObject(draft).getString("draftId");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            requireActiveStep(database, taskId, stepIndex);
            Task task = getTask(taskId);
            ExecutionPlan.Step step = task.plan.steps.get(stepIndex);
            if (!DocumentAction.CAPABILITY.equals(step.capability) && !LocalShareProvider.CAPABILITY.equals(step.capability)
                    && draft.length() > 12000) throw new IllegalArgumentException("Invalid action draft");
            if (!step.isExternalAction() || ExecutionPlan.STEP_DRAFT_READY.equals(step.status)
                    || ExecutionPlan.STEP_HANDED_OFF.equals(step.status) || ExecutionPlan.STEP_UNKNOWN.equals(step.status))
                throw new IllegalStateException("External draft already prepared or dispatched");
            persistPlan(database, task, task.plan.withStepUpdated(stepIndex,
                step.withStatusAndObservation(ExecutionPlan.STEP_DRAFT_READY, draft, System.currentTimeMillis()), stepIndex),
                WAITING_FOR_USER, "action_draft_ready:step_" + (stepIndex + 1));
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    String claimExternalAction(String taskId, int stepIndex, String draftId, JSONObject draft) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = getTask(taskId);
            if (task == null || !WAITING_FOR_USER.equals(task.status) || task.executionStep != stepIndex
                    || task.plan == null || stepIndex >= task.plan.totalSteps())
                throw new IllegalStateException("This action is no longer awaiting confirmation");
            ExecutionPlan.Step step = task.plan.steps.get(stepIndex);
            if (!step.isExternalAction() || !ExecutionPlan.STEP_DRAFT_READY.equals(step.status)
                    || !draftId.equals(new JSONObject(step.observation).getString("draftId")))
                throw new IllegalStateException("Stale or already dispatched action");
            ExternalAction.intent(step.capability, draft);
            String attempt = UUID.randomUUID().toString();
            draft.put("draftId", draftId).put("attemptId", attempt).put("status", "handed_off");
            if (DocumentAction.CAPABILITY.equals(step.capability)) {
                draft.put("phase", "picking").put("taskId", taskId)
                    .put("sha256", DocumentAction.sha256(draft.getString("content").getBytes(StandardCharsets.UTF_8)));
                draft.remove("uri");
            }
            int draftLimit = DocumentAction.CAPABILITY.equals(step.capability) || LocalShareProvider.CAPABILITY.equals(step.capability)
                ? 128000 : 12000;
            if (draft.toString().length() > draftLimit) throw new IllegalArgumentException("Action draft is too large");
            persistPlan(database, task, task.plan.withStepUpdated(stepIndex,
                step.withStatusAndObservation(ExecutionPlan.STEP_HANDED_OFF, draft.toString(), System.currentTimeMillis()), stepIndex),
                WAITING_FOR_USER, "action_handed_off:step_" + (stepIndex + 1) + ":attempt_" + attempt);
            database.setTransactionSuccessful();
            return attempt;
        } finally { database.endTransaction(); }
    }

    boolean recordExternalOutcome(String taskId, int stepIndex, String attempt, boolean launchFailed, String reason) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = getTask(taskId);
            if (task == null || task.plan == null || task.executionStep != stepIndex
                    || stepIndex >= task.plan.totalSteps()) return false;
            ExecutionPlan.Step step = task.plan.steps.get(stepIndex);
            if (!ExecutionPlan.STEP_HANDED_OFF.equals(step.status)) return false;
            JSONObject observation = new JSONObject(step.observation);
            if (!attempt.equals(observation.optString("attemptId"))) return false;
            observation.put("status", launchFailed ? "launch_failed" : "outcome_unknown");
            observation.put("verification", "none");
            String state = launchFailed ? ExecutionPlan.STEP_FAILED : ExecutionPlan.STEP_UNKNOWN;
            persistPlan(database, task, task.plan.withStepUpdated(stepIndex,
                step.withStatusAndObservation(state, observation.toString(), System.currentTimeMillis()), stepIndex),
                launchFailed ? FAILED : OUTCOME_UNKNOWN,
                "action_" + state + ":step_" + (stepIndex + 1) + ":" + reason);
            database.setTransactionSuccessful();
            return true;
        } finally { database.endTransaction(); }
    }

    int recoverExternalActions() throws Exception {
        int recovered = 0;
        for (Task task : listTasks()) {
            if (task.plan == null || task.executionStep >= task.plan.totalSteps()) continue;
            ExecutionPlan.Step step = task.plan.steps.get(task.executionStep);
            if (ExecutionPlan.STEP_HANDED_OFF.equals(step.status)
                    && recordExternalOutcome(task.id, task.executionStep,
                        new JSONObject(step.observation).getString("attemptId"), false, "recovered_without_receipt")) recovered++;
        }
        return recovered;
    }

    Task createDocumentTask(String goal, String content, org.json.JSONArray sources) throws Exception {
        JSONObject draft = DocumentAction.draft(content, sources);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = createTask(goal);
            ExecutionPlan.Step step = new ExecutionPlan.Step(0, "保存 Markdown 并读回核验", DocumentAction.CAPABILITY,
                DocumentAction.SCOPE, "", ExecutionPlan.STEP_PENDING, "", 0);
            savePlan(task.id, new ExecutionPlan(task.id, task.title, java.util.Collections.singletonList(step),
                System.currentTimeMillis(), 0));
            transition(task.id, ACTIVE);
            prepareExternalDraft(task.id, 0, draft.toString());
            database.setTransactionSuccessful();
            return getTask(task.id);
        } finally { database.endTransaction(); }
    }

    Task createCalendarFollowup(String parentId) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task parent = requireDocument(parentId);
            if (!DocumentAction.locallyVerified(parent))
                throw new IllegalStateException("Verify the document on this device first");
            for (Task existing : listTasks()) {
                if (existing.plan == null || existing.plan.context.isEmpty()) continue;
                JSONObject link = new JSONObject(existing.plan.context).optJSONObject("workflowLink");
                if (link != null && "document_to_calendar".equals(link.optString("kind"))
                        && parentId.equals(link.optString("parentTaskId"))) {
                    if (CANCELLED.equals(existing.status) && existing.plan.totalSteps() == 1) {
                        ExecutionPlan.Step step = existing.plan.steps.get(0);
                        if (ExecutionPlan.STEP_CANCELLED.equals(step.status)
                                && new JSONObject(step.observation).optString("attemptId").isEmpty()) continue;
                    }
                    database.setTransactionSuccessful();
                    return existing;
                }
            }
            String hash = new JSONObject(parent.plan.steps.get(0).observation).getString("sha256");
            JSONObject context = new JSONObject(TaskContext.selected(java.util.Collections.emptyList(), true));
            context.put("workflowLink", new JSONObject().put("kind", "document_to_calendar")
                .put("parentTaskId", parentId).put("parentSha256", hash));
            Task child = createTask(parent.title);
            ExecutionPlan.Step step = new ExecutionPlan.Step(0, "创建日历草稿", LocalCalendarProvider.CAPABILITY,
                LocalCalendarProvider.SCOPE, "", ExecutionPlan.STEP_PENDING, "", 0);
            savePlan(child.id, new ExecutionPlan(child.id, child.title, java.util.Collections.singletonList(step),
                System.currentTimeMillis(), 0).withContext(context.toString()));
            transition(child.id, ACTIVE);
            Task active = getTask(child.id);
            prepareExternalDraft(child.id, 0, ExternalAction.prepare(active, active.plan.steps.get(0)).toString());
            database.setTransactionSuccessful();
            return getTask(child.id);
        } finally { database.endTransaction(); }
    }

    Task createShareFollowup(String parentId, String content, String expectedUri) throws Exception {
        DocumentAction.validateContent(content);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task parent = requireDocument(parentId);
            if (!DocumentAction.locallyVerified(parent)) throw new IllegalStateException("Verify the document on this device first");
            JSONObject receipt = new JSONObject(parent.plan.steps.get(0).observation);
            String hash = DocumentAction.sha256(content.getBytes(StandardCharsets.UTF_8));
            if (!expectedUri.equals(receipt.optString("uri")) || !hash.equals(receipt.optString("sha256")))
                throw new IllegalStateException("The document changed during review");
            for (Task existing : listTasks()) {
                if (existing.plan == null || existing.plan.context.isEmpty()) continue;
                JSONObject link = new JSONObject(existing.plan.context).optJSONObject("workflowLink");
                if (link == null || !"document_to_share".equals(link.optString("kind")) || !parentId.equals(link.optString("parentTaskId"))) continue;
                if (CANCELLED.equals(existing.status) && existing.plan.totalSteps() == 1) {
                    ExecutionPlan.Step step = existing.plan.steps.get(0);
                    if (ExecutionPlan.STEP_CANCELLED.equals(step.status) && new JSONObject(step.observation).optString("attemptId").isEmpty()) continue;
                }
                database.setTransactionSuccessful();
                return existing;
            }
            JSONObject context = new JSONObject(TaskContext.selected(java.util.Collections.emptyList(), true));
            context.put("workflowLink", new JSONObject().put("kind", "document_to_share").put("parentTaskId", parentId).put("parentSha256", hash));
            Task child = createTask(parent.title);
            ExecutionPlan.Step step = new ExecutionPlan.Step(0, "审核分享草稿", LocalShareProvider.CAPABILITY,
                LocalShareProvider.SCOPE, "", ExecutionPlan.STEP_PENDING, "", 0);
            savePlan(child.id, new ExecutionPlan(child.id, child.title, java.util.Collections.singletonList(step),
                System.currentTimeMillis(), 0).withContext(context.toString()));
            transition(child.id, ACTIVE);
            JSONObject draft = new JSONObject().put("provider", "system.share").put("status", "ready_for_sharing")
                .put("subject", parent.title).put("content", content).put("draftId", UUID.randomUUID().toString());
            prepareExternalDraft(child.id, 0, draft.toString());
            database.setTransactionSuccessful();
            return getTask(child.id);
        } finally { database.endTransaction(); }
    }

    Task prepareReviewedDocument(String taskId, String content, String expectedFingerprint, String requestSha256) throws Exception {
        return prepareReviewedDocument(taskId, content, expectedFingerprint, requestSha256,
            requestSha256.isEmpty() ? "local_template" : "model");
    }

    Task prepareLocalModelDocument(String taskId, String content, String expectedFingerprint) throws Exception {
        return prepareReviewedDocument(taskId, content, expectedFingerprint, "", "on_device_model");
    }

    private Task prepareReviewedDocument(String taskId, String content, String expectedFingerprint,
                                         String requestSha256, String origin) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = getTask(taskId);
            if (task == null || !QUEUED.equals(task.status) || !expectedFingerprint.equals(TaskContext.fingerprint(task)))
                throw new SecurityException("Task changed while its document was being prepared");
            if ("on_device_model".equals(origin) && (!TaskContext.isDocument(task.plan) || !TaskContext.localOnly(task.plan)))
                throw new SecurityException("On-device drafts require a local-only document task");
            org.json.JSONArray sources = new org.json.JSONArray();
            for (Memory memory : TaskContext.memories(task.plan))
                sources.put(new JSONObject().put("id", memory.id).put("kind", memory.kind).put("source", memory.source)
                    .put("sourceReference", memory.sourceReference).put("updatedAt", memory.updatedAt));
            JSONObject draft = DocumentAction.draft(content, sources).put("draftOrigin", origin);
            if (!requestSha256.isEmpty()) draft.put("requestSha256", requestSha256);
            ExecutionPlan.Step step = new ExecutionPlan.Step(0, "保存 Markdown 并读回核验", DocumentAction.CAPABILITY,
                DocumentAction.SCOPE, "", ExecutionPlan.STEP_PENDING, "", 0);
            savePlan(taskId, new ExecutionPlan(taskId, task.title, java.util.Collections.singletonList(step),
                System.currentTimeMillis(), 0).withContext(task.plan.context));
            transition(taskId, ACTIVE);
            prepareExternalDraft(taskId, 0, draft.toString());
            database.setTransactionSuccessful();
            return getTask(taskId);
        } finally { database.endTransaction(); }
    }

    private Task requireDocument(String taskId) throws Exception {
        Task task = getTask(taskId);
        if (task == null || task.plan == null || task.plan.totalSteps() != 1
                || !DocumentAction.CAPABILITY.equals(task.plan.steps.get(0).capability))
            throw new IllegalStateException("This is not a document task");
        return task;
    }

    void bindDocumentTarget(String taskId, String attempt, String uri) throws Exception {
        DocumentAction.contentUri(uri);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = requireDocument(taskId);
            ExecutionPlan.Step step = task.plan.steps.get(0);
            JSONObject value = new JSONObject(step.observation);
            if (!WAITING_FOR_USER.equals(task.status) || !ExecutionPlan.STEP_HANDED_OFF.equals(step.status)
                    || !attempt.equals(value.optString("attemptId")) || !"picking".equals(value.optString("phase")))
                throw new IllegalStateException("The file selection is stale or already consumed");
            value.put("uri", uri).put("phase", "target_ready").put("localAccess", true).put("verificationId", attempt);
            persistPlan(database, task, task.plan.withStepUpdated(0,
                step.withStatusAndObservation(ExecutionPlan.STEP_HANDED_OFF, value.toString(), System.currentTimeMillis()), 0),
                WAITING_FOR_USER, "document_target_selected");
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    JSONObject claimDocumentWrite(String taskId) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = requireDocument(taskId);
            ExecutionPlan.Step step = task.plan.steps.get(0);
            JSONObject value = new JSONObject(step.observation);
            if (!WAITING_FOR_USER.equals(task.status) || !ExecutionPlan.STEP_HANDED_OFF.equals(step.status)
                    || !"target_ready".equals(value.optString("phase")) || !value.optBoolean("localAccess"))
                throw new IllegalStateException("Document write is not authorized or was already claimed");
            DocumentAction.contentUri(value.getString("uri"));
            value.put("phase", "writing");
            persistPlan(database, task, task.plan.withStepUpdated(0,
                step.withStatusAndObservation(ExecutionPlan.STEP_HANDED_OFF, value.toString(), System.currentTimeMillis()), 0),
                WAITING_FOR_USER, "document_write_claimed:attempt_" + value.getString("attemptId"));
            database.setTransactionSuccessful();
            return value;
        } finally { database.endTransaction(); }
    }

    void selectDocumentForVerification(String taskId, String uri) throws Exception {
        DocumentAction.contentUri(uri);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = requireDocument(taskId);
            ExecutionPlan.Step step = task.plan.steps.get(0);
            JSONObject value = new JSONObject(step.observation);
            if ((!OUTCOME_UNKNOWN.equals(task.status) && !COMPLETED.equals(task.status))
                    || value.optString("attemptId").isEmpty())
                throw new IllegalStateException("No dispatched document exists to verify");
            value.put("uri", uri).put("phase", "verify_only").put("localAccess", true).put("trackingStopped", false)
                .put("verificationId", UUID.randomUUID().toString());
            persistPlan(database, task, task.plan.withStepUpdated(0,
                step.withStatusAndObservation(ExecutionPlan.STEP_UNKNOWN, value.toString(), System.currentTimeMillis()), 0),
                OUTCOME_UNKNOWN, "document_read_access_selected");
            ContentValues checkpoint = new ContentValues();
            checkpoint.put("execution_step", 0);
            database.update("tasks", checkpoint, "id=?", new String[]{taskId});
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    void completeDocumentVerification(String taskId, String attempt, String verificationId, String uri, String hash, int size) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = requireDocument(taskId);
            ExecutionPlan.Step step = task.plan.steps.get(0);
            JSONObject value = new JSONObject(step.observation);
            boolean currentRead = attempt.equals(value.optString("attemptId"))
                && verificationId.equals(value.optString("verificationId", attempt)) && uri.equals(value.optString("uri"));
            if (currentRead && COMPLETED.equals(task.status) && DocumentAction.hasReceipt(value)
                    && hash.equals(value.optString("sha256")) && size == value.optInt("byteCount")) return;
            if ((!WAITING_FOR_USER.equals(task.status) && !OUTCOME_UNKNOWN.equals(task.status))
                    || !currentRead
                    || !value.optBoolean("localAccess") || value.optBoolean("trackingStopped")
                    || (!"writing".equals(value.optString("phase")) && !"verify_only".equals(value.optString("phase"))))
                throw new IllegalStateException("This readback does not match a current document attempt");
            byte[] expected = value.getString("content").getBytes(StandardCharsets.UTF_8);
            if (size != expected.length || !hash.equals(DocumentAction.sha256(expected)))
                throw new IllegalStateException("Document readback does not match the approved content");
            long now = System.currentTimeMillis();
            value.put("status", "verify_only".equals(value.optString("phase")) ? "verified_existing" : "created")
                .put("phase", "verified").put("verification", "document_readback").put("sha256", hash)
                .put("byteCount", size).put("verifiedAt", now).put("receiptOrigin", "this_installation");
            persistPlan(database, task, task.plan.withStepUpdated(0,
                step.withStatusAndObservation(ExecutionPlan.STEP_DONE, value.toString(), now), 1), COMPLETED,
                "document_readback_verified:sha256_" + hash);
            JSONObject payload = taskPayload(database, taskId);
            payload.put("result", "Markdown 读回核验一致 / Markdown readback matched\nSHA-256: " + hash);
            ContentValues completed = new ContentValues();
            completed.put("execution_step", 1);
            completed.put("sealed_payload", seal(taskId, payload.toString().getBytes(StandardCharsets.UTF_8)));
            database.update("tasks", completed, "id=?", new String[]{taskId});
            appendAudit(database, taskId, "task_succeeded", now);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    void documentOutcomeUnknown(String taskId, String attempt, String verificationId, String reason) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = requireDocument(taskId);
            ExecutionPlan.Step step = task.plan.steps.get(0);
            JSONObject value = new JSONObject(step.observation);
            if (!attempt.equals(value.optString("attemptId")) || !verificationId.equals(value.optString("verificationId", attempt))
                    || CANCELLED.equals(task.status)) return;
            if (OUTCOME_UNKNOWN.equals(task.status) && "none".equals(value.optString("verification"))) return;
            value.put("status", "outcome_unknown").put("verification", "none");
            persistPlan(database, task, task.plan.withStepUpdated(0,
                step.withStatusAndObservation(ExecutionPlan.STEP_UNKNOWN, value.toString(), System.currentTimeMillis()), 0),
                OUTCOME_UNKNOWN, "document_outcome_unknown:" + reason);
            ContentValues checkpoint = new ContentValues();
            checkpoint.put("execution_step", 0);
            database.update("tasks", checkpoint, "id=?", new String[]{taskId});
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    void cancelDocumentPicker(String taskId, String attempt) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = requireDocument(taskId);
            ExecutionPlan.Step step = task.plan.steps.get(0);
            JSONObject value = new JSONObject(step.observation);
            if (!ExecutionPlan.STEP_HANDED_OFF.equals(step.status) || !attempt.equals(value.optString("attemptId"))
                    || !"picking".equals(value.optString("phase")) || value.has("uri"))
                throw new IllegalStateException("The file picker is no longer pending");
            value.put("status", "cancelled");
            persistPlan(database, task, task.plan.withStepUpdated(0,
                step.withStatusAndObservation(ExecutionPlan.STEP_CANCELLED, value.toString(), System.currentTimeMillis()), 0),
                CANCELLED, "document_picker_cancelled");
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    int reviewMemoryCandidates(String taskId, boolean accept) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = getTask(taskId);
            if (task == null || !COMPLETED.equals(task.status) || task.plan == null
                    || !"pending".equals(task.plan.memoryReview)) return 0;
            List<MemoryConsolidator.ConsolidatedItem> candidates = MemoryConsolidator.extractItems(task);
            int saved = 0;
            for (int index = 0; index < candidates.size(); index++)
                saved += reviewMemoryCandidate(taskId, index, accept, candidates.get(index).content);
            database.setTransactionSuccessful();
            return saved;
        } finally { database.endTransaction(); }
    }

    int reviewMemoryCandidate(String taskId, int index, boolean accept, String editedContent) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Task task = getTask(taskId);
            if (task == null || !COMPLETED.equals(task.status) || task.plan == null
                    || !"pending".equals(task.plan.memoryReview)) return 0;
            List<MemoryConsolidator.ConsolidatedItem> candidates = MemoryConsolidator.extractItems(task);
            if (index < 0 || index >= candidates.size()) throw new IllegalArgumentException("Candidate no longer exists");
            JSONObject decisions = new JSONObject(task.plan.memoryDecisions);
            String key = String.valueOf(index);
            if (decisions.has(key)) return 0;
            String content = editedContent == null ? "" : editedContent.trim();
            if (accept && (content.isEmpty() || content.length() > 10000))
                throw new IllegalArgumentException("Confirmed memory must contain 1–10000 characters");
            JSONObject choice = new JSONObject().put("decision", accept ? "accepted" : "dismissed")
                .put("kind", candidates.get(index).kind).put("reviewedAt", System.currentTimeMillis());
            if (accept) {
                Memory memory = createMemory(candidates.get(index).kind, content, MEMORY_SOURCE_CONSOLIDATION, "confirmed:" + task.id);
                choice.put("memoryId", memory.id).put("content", content);
            }
            decisions.put(key, choice);
            boolean accepted = false;
            for (int item = 0; item < candidates.size(); item++)
                if (decisions.has(String.valueOf(item)) && "accepted".equals(decisions.getJSONObject(String.valueOf(item)).optString("decision"))) accepted = true;
            String review = decisions.length() < candidates.size() ? "pending" : accepted ? "accepted" : "dismissed";
            persistPlan(database, task, task.plan.withMemoryDecisions(review, decisions.toString()), task.status,
                "memory_candidate_" + (accept ? "confirmed" : "dismissed") + ":index=" + index);
            database.setTransactionSuccessful();
            return accept ? 1 : 0;
        } finally { database.endTransaction(); }
    }

    List<Memory> listPlanningMemories() throws Exception {
        ArrayList<Memory> trusted = new ArrayList<>();
        for (Memory memory : listMemories()) {
            if (!memory.isExpired(System.currentTimeMillis()) && (!MEMORY_SOURCE_CONSOLIDATION.equals(memory.source) || memory.sourceReference.startsWith("confirmed:")))
                trusted.add(memory);
        }
        return trusted;
    }

    boolean reviewLegacyMemory(String memoryId, long expectedUpdatedAt) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Memory memory;
            try (Cursor cursor = database.query("memories", new String[]{"kind", "created_at", "updated_at", "sealed_payload"},
                    "id=?", new String[]{memoryId}, null, null, null)) {
                if (!cursor.moveToFirst()) throw new IllegalArgumentException("Memory no longer exists");
                memory = memoryFromPayload(memoryId, cursor.getString(0), cursor.getLong(1), cursor.getLong(2),
                    unseal(memoryAad(memoryId), cursor.getBlob(3)));
            }
            if (!MEMORY_SOURCE_CONSOLIDATION.equals(memory.source) || memory.sourceReference.startsWith("confirmed:")) return false;
            if (memory.updatedAt != expectedUpdatedAt) throw new IllegalStateException("Memory changed; review its latest version");
            updateMemory(memory.id, memory.kind, memory.content, memory.source, "confirmed:" + memory.sourceReference);
            if (exists(database, "tasks", memory.sourceReference))
                appendAudit(database, memory.sourceReference, "legacy_memory_confirmed:" + memory.id, System.currentTimeMillis());
            database.setTransactionSuccessful();
            return true;
        } finally { database.endTransaction(); }
    }

    private void persistPlan(SQLiteDatabase database, Task task, ExecutionPlan plan, String status, String event) throws Exception {
        JSONObject payload = taskPayload(database, task.id);
        payload.put("plan", plan.toJson());
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("updated_at", System.currentTimeMillis());
        values.put("sealed_payload", seal(task.id, payload.toString().getBytes(StandardCharsets.UTF_8)));
        database.update("tasks", values, "id=?", new String[]{task.id});
        appendAudit(database, task.id, event, System.currentTimeMillis());
    }

    void saveNoteCheckpoint(String taskId, String note) throws Exception {
        saveStepCheckpoint(taskId, 0, note);
    }

    void completeExecution(String taskId, String result) throws Exception {
        if (result == null || result.trim().isEmpty() || result.length() > 12000)
            throw new IllegalArgumentException("The local executor returned invalid output");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            int currentStep = 0;
            int execVersion = 1;
            try (Cursor cursor = database.query("tasks", new String[]{"execution_step", "execution_version"}, "id=?",
                    new String[]{taskId}, null, null, null)) {
                if (cursor.moveToFirst()) {
                    currentStep = cursor.getInt(0);
                    execVersion = cursor.getInt(1);
                }
            }
            requireActiveStep(database, taskId, currentStep);
            JSONObject payload = taskPayload(database, taskId);
            if (execVersion >= 2 && (!payload.has("plan")
                    || !ExecutionPlan.fromJson(payload.getJSONObject("plan")).isComplete()))
                throw new IllegalStateException("Unverified steps cannot complete a task");
            if (execVersion == 1 && payload.optString("checkpointNote", "").isEmpty())
                throw new IllegalStateException("The local note checkpoint is missing");
            payload.put("result", result);
            long now = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put("status", COMPLETED);
            values.put("execution_step", execVersion == 1 ? currentStep + 1 : currentStep);
            values.put("updated_at", now);
            values.put("sealed_payload", seal(taskId, payload.toString().getBytes(StandardCharsets.UTF_8)));
            database.update("tasks", values, "id=?", new String[]{taskId});
            if (execVersion == 1) appendAudit(database, taskId, "executor_step_" + (currentStep + 1) + "_saved", now);
            appendAudit(database, taskId, "task_succeeded", now);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void markWaitingForUser(String taskId, int stepIndex) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            requireActiveStep(database, taskId, stepIndex);
            JSONObject payload = taskPayload(database, taskId);
            ExecutionPlan plan = payload.has("plan") ? ExecutionPlan.fromJson(payload.getJSONObject("plan")) : null;
            if (plan != null && stepIndex >= 0 && stepIndex < plan.totalSteps()) {
                ExecutionPlan.Step current = plan.steps.get(stepIndex);
                ExecutionPlan.Step updated = current.withStatusAndObservation(
                    ExecutionPlan.STEP_AWAITING_GRANT, current.observation, System.currentTimeMillis());
                plan = plan.withStepUpdated(stepIndex, updated, stepIndex);
                payload.put("plan", plan.toJson());
                ContentValues values = new ContentValues();
                values.put("status", WAITING_FOR_USER);
                values.put("updated_at", System.currentTimeMillis());
                values.put("sealed_payload", seal(taskId, payload.toString().getBytes(StandardCharsets.UTF_8)));
                database.update("tasks", values, "id=?", new String[]{taskId});
            } else {
                ContentValues values = new ContentValues();
                values.put("status", WAITING_FOR_USER);
                values.put("updated_at", System.currentTimeMillis());
                database.update("tasks", values, "id=?", new String[]{taskId});
            }
            appendAudit(database, taskId, "task_waiting_for_user:step_" + (stepIndex + 1), System.currentTimeMillis());
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void markWaitingForUser(String taskId) throws Exception {
        Task task = getTask(taskId);
        int step = task != null ? task.executionStep : 0;
        markWaitingForUser(taskId, step);
    }

    void markExecutionFailed(String taskId) {
        try {
            transition(taskId, FAILED);
        } catch (Exception ignored) {
            // The task may have been paused or cancelled while the provider was running.
        }
    }

    boolean isTaskActive(String taskId) {
        try {
            Task task = getTask(taskId);
            return task != null && ACTIVE.equals(task.status);
        } catch (Exception error) {
            return false;
        }
    }

    private static void requireActiveStep(SQLiteDatabase database, String taskId, int expectedStep) {
        try (Cursor cursor = database.query("tasks", new String[]{"status", "execution_step"}, "id=?",
                new String[]{taskId}, null, null, null)) {
            if (!cursor.moveToFirst()) throw new IllegalArgumentException("Task no longer exists");
            if (!ACTIVE.equals(cursor.getString(0)) || cursor.getInt(1) != expectedStep)
                throw new IllegalStateException("The task is no longer ready for this execution step");
        }
    }

    private JSONObject taskPayload(SQLiteDatabase database, String taskId) throws Exception {
        try (Cursor cursor = database.query("tasks", new String[]{"sealed_payload"}, "id=?",
                new String[]{taskId}, null, null, null)) {
            if (!cursor.moveToFirst()) throw new IllegalArgumentException("Task no longer exists");
            return new JSONObject(new String(unseal(taskId, cursor.getBlob(0)), StandardCharsets.UTF_8));
        }
    }

    void appendCapabilityAudit(String taskId, String event) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            try (Cursor cursor = database.query("tasks", new String[]{"id"}, "id=?",
                    new String[]{taskId}, null, null, null)) {
                if (!cursor.moveToFirst()) throw new IllegalArgumentException("Task no longer exists");
            }
            appendAudit(database, taskId, event, System.currentTimeMillis());
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void appendTaskEvent(String taskId, String event) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            if (!exists(database, "tasks", taskId)) throw new IllegalArgumentException("Task no longer exists");
            appendAudit(database, taskId, event, System.currentTimeMillis());
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public List<String> listAuditEvents(String taskId, int limit) {
        SQLiteDatabase database = getReadableDatabase();
        List<String> list = new ArrayList<>();
        String selection = (taskId != null && !taskId.isEmpty()) ? "task_id=?" : null;
        String[] selectionArgs = (taskId != null && !taskId.isEmpty()) ? new String[]{taskId} : null;
        String limitStr = limit > 0 ? String.valueOf(limit) : "50";
        try (Cursor cursor = database.query("audit", new String[]{"occurred_at", "event"},
                selection, selectionArgs, null, null, "occurred_at DESC", limitStr)) {
            while (cursor.moveToNext()) {
                list.add(cursor.getLong(0) + ":" + cursor.getString(1));
            }
        }
        return list;
    }

    void deleteTask(String taskId) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            addTombstone(database, "task", taskId, now);
            int deleted = database.delete("tasks", "id=?", new String[]{taskId});
            if (deleted == 0) throw new IllegalArgumentException("Task no longer exists");
            database.delete("audit", "task_id=?", new String[]{taskId});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    static final class Pairing {
        final String deviceId;
        final String fingerprint;
        final String peerId;
        final String status;
        final long pairedAt;

        Pairing(String deviceId, String fingerprint, String peerId, String status, long pairedAt) {
            this.deviceId = deviceId;
            this.fingerprint = fingerprint;
            this.peerId = peerId;
            this.status = status;
            this.pairedAt = pairedAt;
        }
    }

    void savePairing(String deviceId, String fingerprint, String peerId, String status) throws Exception {
        if (deviceId == null || deviceId.isEmpty() || deviceId.length() > 64
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || peerId == null || peerId.length() > 128
                || status == null || !status.matches("[a-z_]{1,32}"))
            throw new IllegalArgumentException("Invalid pairing record");
        ContentValues values = new ContentValues();
        values.put("device_id", deviceId);
        values.put("fingerprint", fingerprint);
        values.put("peer_id", peerId);
        values.put("status", status);
        values.put("paired_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("pairings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    List<Pairing> listPairings() {
        ArrayList<Pairing> pairings = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("pairings",
                new String[]{"device_id", "fingerprint", "peer_id", "status", "paired_at"},
                null, null, null, null, "paired_at")) {
            while (cursor.moveToNext())
                pairings.add(new Pairing(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4)));
        }
        return pairings;
    }

    void deletePairing(String deviceId) {
        getWritableDatabase().delete("pairings", "device_id=?", new String[]{deviceId});
    }

    /** Versions known for one memory id, as persisted by merge or resolve. */
    List<MemoryMerge.Version> listMemoryVersions(String memoryId) {
        ArrayList<MemoryMerge.Version> versions = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("memory_versions",
                new String[]{"clock", "content_hash", "tombstone"}, "memory_id=?", new String[]{memoryId},
                null, null, "clock")) {
            while (cursor.moveToNext()) {
                versions.add(new MemoryMerge.Version(decodeClock(cursor.getString(0)),
                    cursor.isNull(1) ? null : cursor.getString(1), cursor.getInt(2) == 1));
            }
        }
        return versions;
    }

    /**
     * Merge remote versions for one memory inside a transaction.
     * A single surviving tombstone deletes the memory row and keeps the deletion marker.
     */
    List<MemoryMerge.Version> mergeMemoryVersions(String memoryId, List<MemoryMerge.Version> incoming) throws Exception {
        if (memoryId == null || memoryId.isEmpty() || memoryId.length() > 64)
            throw new IllegalArgumentException("Invalid memory identifier");
        if (incoming == null) throw new IllegalArgumentException("Missing incoming versions");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            List<MemoryMerge.Version> merged = MemoryMerge.merge(listMemoryVersions(memoryId), incoming);
            replaceMemoryVersions(database, memoryId, merged);
            if (merged.size() == 1 && merged.get(0).tombstone) {
                database.delete("memories", "id=?", new String[]{memoryId});
                ContentValues marker = new ContentValues();
                marker.put("entity_type", "memory");
                marker.put("entity_id", memoryId);
                marker.put("deleted_at", System.currentTimeMillis());
                database.insertWithOnConflict("tombstones", null, marker, SQLiteDatabase.CONFLICT_REPLACE);
            }
            database.setTransactionSuccessful();
            return merged;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Apply a version the user picked from an earlier review. The reviewed candidate
     * set is re-read inside the transaction and must be unchanged.
     */
    void resolveMemoryVersion(String memoryId, List<MemoryMerge.Version> reviewed, MemoryMerge.Version chosen,
                              String kind, String content, String source, String reference, long expiresAt) throws Exception {
        if (reviewed == null || chosen == null) throw new IllegalArgumentException("Choose a reviewed version");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            List<MemoryMerge.Version> current = listMemoryVersions(memoryId);
            if (!sameVersions(current, reviewed))
                throw new IllegalStateException("Reviewed versions changed; review again");
            if (!containsVersion(reviewed, chosen)) throw new IllegalArgumentException("Chosen version was not reviewed");
            java.util.TreeMap<String, Long> resolved = MemoryMerge.resolvedClock(reviewed, LOCAL_ACTOR);
            if (chosen.tombstone) {
                database.delete("memories", "id=?", new String[]{memoryId});
                ContentValues marker = new ContentValues();
                marker.put("entity_type", "memory");
                marker.put("entity_id", memoryId);
                marker.put("deleted_at", System.currentTimeMillis());
                database.insertWithOnConflict("tombstones", null, marker, SQLiteDatabase.CONFLICT_REPLACE);
                replaceMemoryVersions(database, memoryId,
                    java.util.Collections.singletonList(new MemoryMerge.Version(resolved, null, true)));
            } else {
                String normalized = validateMemory(kind, content);
                String hash = sha256Hex(normalized);
                if (!hash.equals(chosen.contentHash))
                    throw new IllegalArgumentException("Content does not match the reviewed version");
                String trimmedReference = reference == null ? "" : reference.trim();
                byte[] sealed = seal(memoryAad(memoryId), memoryPayload(normalized, source, trimmedReference, expiresAt));
                boolean exists;
                try (Cursor cursor = database.query("memories", new String[]{"id"}, "id=?", new String[]{memoryId}, null, null, null)) {
                    exists = cursor.moveToFirst();
                }
                ContentValues values = new ContentValues();
                values.put("kind", kind);
                values.put("updated_at", System.currentTimeMillis());
                values.put("sealed_payload", sealed);
                if (exists) {
                    database.update("memories", values, "id=?", new String[]{memoryId});
                } else {
                    values.put("id", memoryId);
                    values.put("created_at", System.currentTimeMillis());
                    database.insertOrThrow("memories", null, values);
                }
                database.delete("tombstones", "entity_type=? AND entity_id=?", new String[]{"memory", memoryId});
                replaceMemoryVersions(database, memoryId,
                    java.util.Collections.singletonList(new MemoryMerge.Version(resolved, hash, false)));
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private static void replaceMemoryVersions(SQLiteDatabase database, String memoryId, List<MemoryMerge.Version> versions) {
        database.delete("memory_versions", "memory_id=?", new String[]{memoryId});
        for (MemoryMerge.Version version : versions) {
            ContentValues values = new ContentValues();
            values.put("memory_id", memoryId);
            values.put("clock", encodeClock(version.clock));
            if (version.contentHash == null) values.putNull("content_hash");
            else values.put("content_hash", version.contentHash);
            values.put("tombstone", version.tombstone ? 1 : 0);
            database.insertOrThrow("memory_versions", null, values);
        }
    }

    private static boolean sameVersions(List<MemoryMerge.Version> left, List<MemoryMerge.Version> right) {
        if (left.size() != right.size()) return false;
        for (MemoryMerge.Version version : left) if (!containsVersion(right, version)) return false;
        return true;
    }

    private static boolean containsVersion(List<MemoryMerge.Version> versions, MemoryMerge.Version wanted) {
        for (MemoryMerge.Version version : versions) if (version.sameAs(wanted)) return true;
        return false;
    }

    private static String encodeClock(Map<String, Long> clock) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Long> entry : new java.util.TreeMap<>(clock).entrySet()) {
            if (builder.length() > 0) builder.append('|');
            builder.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return builder.toString();
    }

    private static java.util.TreeMap<String, Long> decodeClock(String value) {
        java.util.TreeMap<String, Long> clock = new java.util.TreeMap<>();
        if (value == null || value.isEmpty()) return clock;
        for (String part : value.split("\\|")) {
            int index = part.lastIndexOf(':');
            if (index <= 0) throw new IllegalStateException("Corrupt memory clock");
            clock.put(part.substring(0, index), Long.parseLong(part.substring(index + 1)));
        }
        return clock;
    }

    private static String sha256Hex(String content) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte item : hashed) builder.append(String.format("%02x", item));
        return builder.toString();
    }

    Memory createMemory(String kind, String content) throws Exception {
        return createMemory(kind, content, MEMORY_SOURCE_USER, "");
    }

    Memory createMemory(String kind, String content, String source, String sourceReference) throws Exception {
        return createMemory(kind, content, source, sourceReference, 0);
    }

    Memory createMemory(String kind, String content, String source, String sourceReference, long expiresAt) throws Exception {
        if (expiresAt < 0) throw new IllegalArgumentException("Memory expiration must be non-negative");
        String normalized = validateMemory(kind, content);
        String reference = sourceReference == null ? "" : sourceReference.trim();
        if (!MEMORY_SOURCE_USER.equals(source) && !MEMORY_SOURCE_TRACEFORM.equals(source)
                && !MEMORY_SOURCE_CONSOLIDATION.equals(source))
            throw new IllegalArgumentException("Choose a valid memory source");
        if (reference.length() > 512) throw new IllegalArgumentException("Memory source references are limited to 512 characters");
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        byte[] sealed = seal(memoryAad(id), memoryPayload(normalized, source, reference, expiresAt));
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("kind", kind);
        values.put("created_at", now);
        values.put("updated_at", now);
        values.put("sealed_payload", sealed);
        getWritableDatabase().insertOrThrow("memories", null, values);
        return new Memory(id, kind, normalized, source, reference, now, now, expiresAt);
    }

    Memory confirmSensorObservation(JSONObject event, String content, long expiresAt) throws Exception {
        if (event == null || !"motion_change".equals(event.optString("kind"))
                || !"accelerometer+gyroscope".equals(event.optString("source"))
                || !"three_second_rule_window".equals(event.optString("method")) || event.optLong("observedAt") <= 0)
            throw new IllegalArgumentException("Invalid sensor observation");
        String from = event.optString("from"), to = event.optString("to");
        if (!("LowMotion".equals(from) || "MotionDetected".equals(from))
                || !("LowMotion".equals(to) || "MotionDetected".equals(to)) || from.equals(to))
            throw new IllegalArgumentException("Invalid sensor observation");
        String reference = "confirmed:sensor:motion:" + event.getLong("observedAt");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            for (Memory memory : listMemories()) if (reference.equals(memory.sourceReference))
                throw new IllegalStateException("This observation has already been saved");
            Memory memory = createMemory(MEMORY_FACT, content, MEMORY_SOURCE_CONSOLIDATION, reference, expiresAt);
            database.setTransactionSuccessful();
            return memory;
        } finally { database.endTransaction(); }
    }

    void updateMemory(String id, String kind, String content, String source, String sourceReference) throws Exception {
        Memory current = getMemory(id);
        if (current == null) throw new IllegalArgumentException("Memory not found: " + id);
        updateMemoryChecked(current, kind, content, source, sourceReference, current.expiresAt, false);
    }

    void reviseMemory(Memory expected, String content, long expiresAt) throws Exception {
        if (expected == null) throw new IllegalArgumentException("Memory is required");
        updateMemoryChecked(expected, expected.kind, content, expected.source, expected.sourceReference, expiresAt, true);
    }

    private Memory getMemory(String id) throws Exception {
        try (Cursor cursor = getReadableDatabase().query("memories", new String[]{"kind", "created_at", "updated_at", "sealed_payload"},
                "id=?", new String[]{id}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            return memoryFromPayload(id, cursor.getString(0), cursor.getLong(1), cursor.getLong(2),
                unseal(memoryAad(id), cursor.getBlob(3)));
        }
    }

    private void updateMemoryChecked(Memory expected, String kind, String content, String source, String sourceReference, long expiresAt, boolean userEdit) throws Exception {
        if (expiresAt < 0) throw new IllegalArgumentException("Memory expiration must be non-negative");
        String normalized = validateMemory(kind, content);
        String reference = sourceReference == null ? "" : sourceReference.trim();
        if (!MEMORY_SOURCE_USER.equals(source) && !MEMORY_SOURCE_TRACEFORM.equals(source)
                && !MEMORY_SOURCE_CONSOLIDATION.equals(source))
            throw new IllegalArgumentException("Choose a valid memory source");
        if (reference.length() > 512) throw new IllegalArgumentException("Memory source references are limited to 512 characters");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Memory current = getMemory(expected.id);
            if (current == null || current.updatedAt != expected.updatedAt || current.expiresAt != expected.expiresAt || current.userEdited != expected.userEdited
                    || !current.kind.equals(expected.kind) || !current.content.equals(expected.content)
                    || !current.source.equals(expected.source) || !current.sourceReference.equals(expected.sourceReference))
                throw new IllegalStateException("Memory changed or was deleted; review it again");
            if (current.updatedAt == Long.MAX_VALUE) throw new IllegalStateException("Memory timestamp cannot be advanced");
            long now = Math.max(System.currentTimeMillis(), current.updatedAt + 1);
            boolean edited = current.userEdited || (userEdit && !current.content.equals(normalized));
            byte[] sealed = seal(memoryAad(current.id), memoryPayload(normalized, source, reference, expiresAt, edited));
            ContentValues values = new ContentValues();
            values.put("kind", kind);
            values.put("updated_at", now);
            values.put("sealed_payload", sealed);
            database.update("memories", values, "id=?", new String[]{current.id});
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    List<Memory> listMemories() throws Exception {
        ArrayList<Memory> memories = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "memories", new String[]{"id", "kind", "created_at", "updated_at", "sealed_payload"},
                null, null, null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                memories.add(memoryFromPayload(id, cursor.getString(1), cursor.getLong(2), cursor.getLong(3),
                    unseal(memoryAad(id), cursor.getBlob(4))));
            }
        }
        return memories;
    }

    void deleteMemory(String memoryId) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            addTombstone(database, "memory", memoryId, now);
            int deleted = database.delete("memories", "id=?", new String[]{memoryId});
            if (deleted == 0) throw new IllegalArgumentException("Memory no longer exists");
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void deleteMemoriesReviewed(List<Memory> expected) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (Memory item : expected) {
                if (item == null || !ids.add(item.id)) throw new IllegalArgumentException("Invalid memory selection");
                Memory current = getMemory(item.id);
                if (current == null || current.updatedAt != item.updatedAt || current.expiresAt != item.expiresAt || current.userEdited != item.userEdited
                        || !current.kind.equals(item.kind) || !current.content.equals(item.content)
                        || !current.source.equals(item.source) || !current.sourceReference.equals(item.sourceReference))
                    throw new IllegalStateException("A memory changed or was deleted; review the selection again");
            }
            long now = System.currentTimeMillis();
            for (Memory item : expected) {
                addTombstone(database, "memory", item.id, now);
                database.delete("memories", "id=?", new String[]{item.id});
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    List<Task> memoryReferences(String memoryId) throws Exception {
        ArrayList<Task> references = new ArrayList<>();
        for (Task task : listTasks()) {
            if (task.plan != null && !task.plan.context.isEmpty()) {
                JSONObject workflow = new JSONObject(task.plan.context).optJSONObject("workflow");
                if (workflow != null && memoryId.equals(workflow.optString("id"))) { references.add(task); continue; }
            }
            for (Memory memory : TaskContext.memories(task.plan)) {
                if (memoryId.equals(memory.id)) { references.add(task); break; }
            }
        }
        return references;
    }

    byte[] exportArchive(char[] passphrase) throws Exception {
        SQLiteDatabase database = getReadableDatabase();
        MemoryArchive.Data data = new MemoryArchive.Data();
        database.beginTransactionNonExclusive();
        try {
            try (Cursor cursor = database.query(
                    "tasks", new String[]{"id", "created_at", "updated_at", "status", "sealed_payload",
                        "job_id", "execution_version", "execution_step"},
                    null, null, null, null, "updated_at DESC")) {
                while (cursor.moveToNext()) {
                    data.tasks.add(taskFromCursor(cursor));
                }
            }
            try (Cursor cursor = database.query(
                    "memories", new String[]{"id", "kind", "created_at", "updated_at", "sealed_payload"},
                    null, null, null, null, "updated_at DESC")) {
                while (cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    data.memories.add(memoryFromPayload(id, cursor.getString(1), cursor.getLong(2), cursor.getLong(3),
                        unseal(memoryAad(id), cursor.getBlob(4))));
                }
            }
            try (Cursor cursor = database.query("audit", new String[]{"id", "task_id", "occurred_at", "event"},
                    null, null, null, null, "occurred_at ASC")) {
                while (cursor.moveToNext()) {
                    data.audit.add(new AuditEvent(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3)));
                }
            }
            try (Cursor cursor = database.query("tombstones", new String[]{"entity_type", "entity_id", "deleted_at"},
                    null, null, null, null, "deleted_at ASC")) {
                while (cursor.moveToNext()) {
                    data.tombstones.add(new Tombstone(cursor.getString(0), cursor.getString(1), cursor.getLong(2)));
                }
            }
            data.exportedAt = System.currentTimeMillis();
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        return MemoryArchive.encrypt(data, passphrase);
    }

    ImportResult importArchive(java.io.InputStream input, char[] passphrase) throws Exception {
        return importArchive(input, passphrase, false);
    }

    ImportResult importArchive(java.io.InputStream input, char[] passphrase, boolean preferArchiveMemories) throws Exception {
        MemoryArchive.Data data = MemoryArchive.decrypt(input, passphrase);
        return importArchiveData(data, preferArchiveMemories, null, null);
    }

    static final class ArchiveConflict {
        final Memory local;
        final Memory incoming;
        ArchiveConflict(Memory local, Memory incoming) { this.local = local; this.incoming = incoming; }
    }

    static final class ArchiveReview {
        private final MemoryArchive.Data data;
        final Map<String, ArchiveConflict> conflicts;
        private final Map<String, String> deletionSnapshots;
        final int localMemoryDeletions;
        final int localTaskDeletions;
        private ArchiveReview(MemoryArchive.Data data, Map<String, ArchiveConflict> conflicts,
                              Map<String, String> deletionSnapshots, int localMemoryDeletions, int localTaskDeletions) {
            this.data = data;
            this.conflicts = java.util.Collections.unmodifiableMap(conflicts);
            this.deletionSnapshots = java.util.Collections.unmodifiableMap(deletionSnapshots);
            this.localMemoryDeletions = localMemoryDeletions;
            this.localTaskDeletions = localTaskDeletions;
        }
    }

    ArchiveReview reviewArchive(java.io.InputStream input, char[] passphrase) throws Exception {
        MemoryArchive.Data data = MemoryArchive.decrypt(input, passphrase);
        SQLiteDatabase database = getReadableDatabase();
        database.beginTransactionNonExclusive();
        try {
            Map<String, ArchiveConflict> conflicts = archiveConflicts(database, data);
            Map<String, String> deletions = deletionSnapshots(database, data);
            int memories = 0, tasks = 0;
            for (Tombstone item : data.tombstones) {
                if ("memory".equals(item.entityType) && exists(database, "memories", item.entityId)) memories++;
                if ("task".equals(item.entityType) && exists(database, "tasks", item.entityId)) tasks++;
            }
            database.setTransactionSuccessful();
            return new ArchiveReview(data, conflicts, deletions, memories, tasks);
        } finally { database.endTransaction(); }
    }

    ImportResult importReviewedArchive(ArchiveReview review, Map<String, Boolean> choices) throws Exception {
        if (review == null || choices == null) throw new IllegalArgumentException("A choice for every reviewed conflict is required");
        Map<String, Boolean> snapshot = new LinkedHashMap<>(choices);
        if (!snapshot.keySet().equals(review.conflicts.keySet()) || snapshot.containsValue(null))
            throw new IllegalArgumentException("A choice for every reviewed conflict is required");
        return importArchiveData(review.data, false, review, snapshot);
    }

    private Map<String, ArchiveConflict> archiveConflicts(SQLiteDatabase database, MemoryArchive.Data data) throws Exception {
        Set<String> deleted = new HashSet<>();
        for (Tombstone tombstone : data.tombstones) if ("memory".equals(tombstone.entityType)) deleted.add(tombstone.entityId);
        Map<String, ArchiveConflict> conflicts = new LinkedHashMap<>();
        for (Memory incoming : data.memories) {
            if (deleted.contains(incoming.id) || hasTombstone(database, "memory", incoming.id)) continue;
            Memory local = getMemory(incoming.id);
            if (local != null && !sameMemory(local, incoming)) conflicts.put(incoming.id, new ArchiveConflict(local, incoming));
        }
        return conflicts;
    }

    private Map<String, String> deletionSnapshots(SQLiteDatabase database, MemoryArchive.Data data) throws Exception {
        Map<String, String> snapshots = new LinkedHashMap<>();
        for (Tombstone item : data.tombstones) {
            String table = "memory".equals(item.entityType) ? "memories" : "tasks";
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            hashRows(digest, database, table, "id=?", item.entityId, null);
            if ("task".equals(item.entityType)) hashRows(digest, database, "audit", "task_id=?", item.entityId, "id ASC");
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            snapshots.put(item.entityType + ":" + item.entityId, result.toString());
        }
        return snapshots;
    }

    private static void hashRows(java.security.MessageDigest digest, SQLiteDatabase database, String table,
                                 String selection, String id, String order) {
        try (Cursor cursor = database.query(table, null, selection, new String[]{id}, null, null, order)) {
            while (cursor.moveToNext()) {
                digest.update((byte) 1);
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    int type = cursor.getType(i);
                    byte[] value = type == Cursor.FIELD_TYPE_NULL ? new byte[0] : type == Cursor.FIELD_TYPE_BLOB
                        ? cursor.getBlob(i) : cursor.getString(i).getBytes(StandardCharsets.UTF_8);
                    digest.update((byte) type);
                    digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
                    digest.update(value);
                }
            }
        }
        digest.update((byte) 0);
    }

    private static boolean sameMemory(Memory a, Memory b) {
        return a.id.equals(b.id) && a.kind.equals(b.kind) && a.content.equals(b.content)
            && a.source.equals(b.source) && a.sourceReference.equals(b.sourceReference)
            && a.createdAt == b.createdAt && a.updatedAt == b.updatedAt
            && a.expiresAt == b.expiresAt && a.userEdited == b.userEdited;
    }

    private ImportResult importArchiveData(MemoryArchive.Data data, boolean preferArchiveMemories,
            ArchiveReview review, Map<String, Boolean> choices) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        int tasksImported = 0;
        int memoriesImported = 0;
        int tombstonesApplied = 0;
        int memoryConflicts = 0;
        int memoriesReplaced = 0;
        int recordsSkipped = 0;
        database.beginTransaction();
        try {
            if (review != null) {
                if (!review.deletionSnapshots.equals(deletionSnapshots(database, data)))
                    throw new IllegalStateException("Records affected by archive deletions changed; review again");
                Map<String, ArchiveConflict> current = archiveConflicts(database, data);
                if (!current.keySet().equals(review.conflicts.keySet())) throw new IllegalStateException("Memory conflicts changed since review");
                for (String id : current.keySet()) {
                    if (!sameMemory(current.get(id).local, review.conflicts.get(id).local))
                        throw new IllegalStateException("Memory conflicts changed since review");
                }
            }
            for (Tombstone tombstone : data.tombstones) {
                boolean newTombstone = addTombstone(database, tombstone.entityType, tombstone.entityId, tombstone.deletedAt);
                int deleted;
                if ("task".equals(tombstone.entityType)) {
                    deleted = database.delete("tasks", "id=?", new String[]{tombstone.entityId});
                    database.delete("audit", "task_id=?", new String[]{tombstone.entityId});
                } else {
                    deleted = database.delete("memories", "id=?", new String[]{tombstone.entityId});
                }
                if (newTombstone || deleted > 0) tombstonesApplied++;
            }

            Set<String> importedTaskIds = new HashSet<>();
            for (Task task : data.tasks) {
                if (hasTombstone(database, "task", task.id)) {
                    recordsSkipped++;
                    continue;
                }
                if (exists(database, "tasks", task.id)) {
                    recordsSkipped++;
                    continue;
                }
                String importedStatus = ACTIVE.equals(task.status) ? PAUSED : task.status;
                int jobId = allocateJobId(database, task.id);
                int importedStep = task.executionStep;
                JSONObject payload = new JSONObject().put("title", task.title)
                    .put("checkpointNote", task.checkpointNote).put("result", task.result);
                ExecutionPlan importedPlan = DocumentAction.withoutLocalAccess(task.plan);
                if (importedPlan != null) importedPlan = importedPlan.withContext(TaskContext.imported(importedPlan.context));
                if (importedPlan != null && task.executionStep < importedPlan.totalSteps()) {
                    ExecutionPlan.Step step = importedPlan.steps.get(task.executionStep);
                    if (ExecutionPlan.STEP_HANDED_OFF.equals(step.status)) {
                        JSONObject observation = new JSONObject(step.observation);
                        observation.put("status", "outcome_unknown").put("verification", "none");
                        importedPlan = importedPlan.withStepUpdated(task.executionStep,
                            step.withStatusAndObservation(ExecutionPlan.STEP_UNKNOWN, observation.toString(), step.executedAt), task.executionStep);
                        importedStatus = OUTCOME_UNKNOWN;
                    }
                    if (DocumentAction.CAPABILITY.equals(step.capability)
                            && !ExecutionPlan.STEP_DRAFT_READY.equals(step.status) && !ExecutionPlan.STEP_CANCELLED.equals(step.status)
                            && !DocumentAction.hasReceipt(new JSONObject(step.observation))) {
                        importedPlan = importedPlan.withStepUpdated(task.executionStep, unknownStep(step), task.executionStep);
                        importedStatus = OUTCOME_UNKNOWN;
                    }
                }
                int historicalAction = COMPLETED.equals(task.status) ? firstUnverifiedAction(importedPlan) : -1;
                if (historicalAction >= 0) {
                    importedPlan = importedPlan.withStepUpdated(historicalAction, unknownStep(importedPlan.steps.get(historicalAction)), historicalAction);
                    importedStep = historicalAction;
                    importedStatus = OUTCOME_UNKNOWN;
                }
                if (importedPlan != null) payload.put("plan", importedPlan.toJson());
                ContentValues values = new ContentValues();
                values.put("id", task.id);
                values.put("created_at", task.createdAt);
                values.put("updated_at", task.updatedAt);
                values.put("status", importedStatus);
                values.put("job_id", jobId);
                values.put("execution_version", task.executionVersion);
                values.put("execution_step", importedStep);
                values.put("sealed_payload", seal(task.id, payload.toString().getBytes(StandardCharsets.UTF_8)));
                database.insertOrThrow("tasks", null, values);
                if (ACTIVE.equals(task.status)) appendAudit(database, task.id, "task_imported_paused", System.currentTimeMillis());
                if (OUTCOME_UNKNOWN.equals(importedStatus) && !OUTCOME_UNKNOWN.equals(task.status))
                    appendAudit(database, task.id, "imported_action_outcome_unknown", System.currentTimeMillis());
                tasksImported++;
                importedTaskIds.add(task.id);
            }
            for (AuditEvent event : data.audit) {
                if (!importedTaskIds.contains(event.taskId)) continue;
                ContentValues auditValues = new ContentValues();
                auditValues.put("id", event.id);
                auditValues.put("task_id", event.taskId);
                auditValues.put("occurred_at", event.occurredAt);
                auditValues.put("event", event.event);
                database.insertWithOnConflict("audit", null, auditValues, SQLiteDatabase.CONFLICT_IGNORE);
            }

            for (Memory memory : data.memories) {
                if (hasTombstone(database, "memory", memory.id)) {
                    recordsSkipped++;
                    continue;
                }
                if (exists(database, "memories", memory.id)) {
                    if (review != null && sameMemory(getMemory(memory.id), memory)) {
                        recordsSkipped++;
                        continue;
                    }
                    memoryConflicts++;
                    if (!(review == null ? preferArchiveMemories : Boolean.TRUE.equals(choices.get(memory.id)))) {
                        recordsSkipped++;
                        continue;
                    }
                    ContentValues values = new ContentValues();
                    values.put("kind", memory.kind);
                    values.put("created_at", memory.createdAt);
                    values.put("updated_at", memory.updatedAt);
                    values.put("sealed_payload", seal(memoryAad(memory.id), memoryPayload(
                        memory.content, memory.source, memory.sourceReference, memory.expiresAt, memory.userEdited)));
                    database.update("memories", values, "id=?", new String[]{memory.id});
                    memoriesReplaced++;
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put("id", memory.id);
                values.put("kind", memory.kind);
                values.put("created_at", memory.createdAt);
                values.put("updated_at", memory.updatedAt);
                values.put("sealed_payload", seal(memoryAad(memory.id), memoryPayload(
                    memory.content, memory.source, memory.sourceReference, memory.expiresAt, memory.userEdited)));
                database.insertOrThrow("memories", null, values);
                memoriesImported++;
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return new ImportResult(tasksImported, memoriesImported, tombstonesApplied,
            memoryConflicts, memoriesReplaced, recordsSkipped);
    }

    private static byte[] memoryPayload(String content, String source, String sourceReference) throws Exception {
        return memoryPayload(content, source, sourceReference, 0);
    }

    private static byte[] memoryPayload(String content, String source, String sourceReference, long expiresAt) throws Exception {
        return memoryPayload(content, source, sourceReference, expiresAt, false);
    }

    private static byte[] memoryPayload(String content, String source, String sourceReference, long expiresAt, boolean userEdited) throws Exception {
        return new JSONObject().put("format", MEMORY_PAYLOAD_FORMAT).put("content", content).put("source", source)
            .put("sourceReference", sourceReference).put("expiresAt", expiresAt).put("userEdited", userEdited).toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Memory memoryFromPayload(String id, String kind, long createdAt, long updatedAt,
            byte[] plaintext) throws Exception {
        String decoded = new String(plaintext, StandardCharsets.UTF_8);
        try {
            JSONObject payload = new JSONObject(decoded);
            if (MEMORY_PAYLOAD_FORMAT.equals(payload.optString("format"))) {
                Object rawExpiry = payload.has("expiresAt") ? payload.get("expiresAt") : 0;
                if (!(rawExpiry instanceof Integer) && !(rawExpiry instanceof Long)) throw new IllegalStateException("Invalid memory expiration");
                long expiry = ((Number) rawExpiry).longValue();
                if (expiry < 0) throw new IllegalStateException("Invalid memory expiration");
                Object rawEdited = payload.has("userEdited") ? payload.get("userEdited") : false;
                if (!(rawEdited instanceof Boolean)) throw new IllegalStateException("Invalid memory edit marker");
                return new Memory(id, kind, payload.getString("content"),
                    payload.optString("source", MEMORY_SOURCE_USER), payload.optString("sourceReference", ""),
                    createdAt, updatedAt, expiry, (Boolean) rawEdited);
            }
        } catch (org.json.JSONException ignored) {}
        return new Memory(id, kind, decoded, MEMORY_SOURCE_USER, "", createdAt, updatedAt);
    }

    private static String validateMemory(String kind, String content) {
        if (!MEMORY_PREFERENCE.equals(kind) && !MEMORY_FACT.equals(kind) && !MEMORY_WORKFLOW.equals(kind))
            throw new IllegalArgumentException("Choose a valid memory type");
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Enter memory content");
        if (normalized.length() > 10000) throw new IllegalArgumentException("Memory entries are limited to 10000 characters");
        return normalized;
    }

    private static String memoryAad(String id) {
        return "memory:" + id;
    }

    private static boolean addTombstone(SQLiteDatabase database, String entityType, String entityId, long deletedAt) {
        ContentValues values = new ContentValues();
        values.put("entity_type", entityType);
        values.put("entity_id", entityId);
        values.put("deleted_at", deletedAt);
        return database.insertWithOnConflict("tombstones", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    private static boolean hasTombstone(SQLiteDatabase database, String entityType, String entityId) {
        try (Cursor cursor = database.query("tombstones", new String[]{"entity_id"}, "entity_type=? AND entity_id=?",
                new String[]{entityType, entityId}, null, null, null)) {
            return cursor.moveToFirst();
        }
    }

    private static boolean exists(SQLiteDatabase database, String table, String id) {
        try (Cursor cursor = database.query(table, new String[]{"id"}, "id=?", new String[]{id}, null, null, null)) {
            return cursor.moveToFirst();
        }
    }

    private static int allocateJobId(SQLiteDatabase database, String taskId) {
        Set<Integer> allocated = new HashSet<>();
        try (Cursor cursor = database.query("tasks", new String[]{"job_id"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) allocated.add(cursor.getInt(0));
        }
        return allocateJobId(taskId, allocated);
    }

    private static int allocateJobId(String taskId, Set<Integer> allocated) {
        int candidate = taskId.hashCode() & Integer.MAX_VALUE;
        if (candidate == 0) candidate = 1;
        while (allocated.contains(candidate)) candidate = candidate == Integer.MAX_VALUE ? 1 : candidate + 1;
        allocated.add(candidate);
        return candidate;
    }

    private static boolean isAllowedTransition(String from, String to) {
        if (QUEUED.equals(from)) return ACTIVE.equals(to) || CANCELLED.equals(to);
        if (ACTIVE.equals(from)) return QUEUED.equals(to) || PAUSED.equals(to)
            || WAITING_FOR_USER.equals(to) || FAILED.equals(to) || CANCELLED.equals(to);
        if (PAUSED.equals(from) || WAITING_FOR_USER.equals(from) || FAILED.equals(from))
            return ACTIVE.equals(to) || CANCELLED.equals(to);
        return false;
    }

    private static void appendAudit(SQLiteDatabase database, String taskId, String event, long occurredAt) {
        if (event == null || event.isEmpty() || event.length() > MAX_AUDIT_EVENT_LENGTH)
            throw new IllegalArgumentException("Task history event is invalid or too long");
        ContentValues values = new ContentValues();
        values.put("id", UUID.randomUUID().toString());
        values.put("task_id", taskId);
        values.put("occurred_at", occurredAt);
        values.put("event", event);
        database.insertOrThrow("audit", null, values);
    }

    public static boolean initVault(String vaultPath, String passphrase) {
        return NativeAgentCore.vaultInit(vaultPath, passphrase);
    }

    public static boolean unlockVault(String vaultPath, String passphrase) {
        return NativeAgentCore.vaultUnlock(vaultPath, passphrase);
    }

    public static boolean isVaultUnlocked() {
        return NativeAgentCore.vaultIsUnlocked();
    }

    private byte[] seal(String taskId, byte[] plaintext) throws Exception {
        if (NativeAgentCore.isAvailable() && NativeAgentCore.vaultIsUnlocked()) {
            byte[] sealed = NativeAgentCore.vaultSeal(taskId.getBytes(StandardCharsets.UTF_8), plaintext);
            if (sealed != null && sealed.length > GCM_NONCE_BYTES) {
                return sealed;
            }
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, localKey());
        cipher.updateAAD(taskId.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(plaintext);
        byte[] nonce = cipher.getIV();
        if (nonce.length != GCM_NONCE_BYTES) throw new IllegalStateException("Unexpected AES-GCM nonce size");
        return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
    }

    private byte[] unseal(String taskId, byte[] sealed) throws Exception {
        if (sealed == null || sealed.length <= GCM_NONCE_BYTES) throw new SecurityException("Invalid encrypted task payload");
        if (NativeAgentCore.isAvailable() && NativeAgentCore.vaultIsUnlocked()) {
            try {
                byte[] unsealed = NativeAgentCore.vaultUnseal(taskId.getBytes(StandardCharsets.UTF_8), sealed);
                if (unsealed != null) {
                    return unsealed;
                }
            } catch (Exception ignored) {
                // Fallback to local Android Keystore if sealed with Keystore key
            }
        }
        ByteBuffer buffer = ByteBuffer.wrap(sealed);
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, localKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(taskId.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(ciphertext);
    }

    private SecretKey localKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return (SecretKey) keyStore.getKey(KEY_ALIAS, null);

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
