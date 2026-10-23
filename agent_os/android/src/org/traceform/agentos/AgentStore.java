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
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class AgentStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "agent_os.db";
    private static final int DATABASE_VERSION = 4;
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
    static final int CURRENT_EXECUTION_VERSION = 2;
    static final int MAX_DYNAMIC_STEPS = 10;
    static final String MEMORY_PREFERENCE = "preference";
    static final String MEMORY_FACT = "fact";
    static final String MEMORY_WORKFLOW = "workflow";
    static final String MEMORY_SOURCE_USER = "user";
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

        Memory(String id, String kind, String content, String source, String sourceReference,
                long createdAt, long updatedAt) {
            this.id = id;
            this.kind = kind;
            this.content = content;
            this.source = source;
            this.sourceReference = sourceReference;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

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
            } else {
                throw new IllegalStateException("No Agent OS database migration exists for version " + version);
            }
        }
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
            appendAudit(database, taskId, "status_" + targetStatus, now);
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
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            JSONObject payload = taskPayload(database, taskId);
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
            if (execVersion == 1 && payload.optString("checkpointNote", "").isEmpty())
                throw new IllegalStateException("The local note checkpoint is missing");
            payload.put("result", result);
            long now = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put("status", COMPLETED);
            values.put("execution_step", currentStep + 1);
            values.put("updated_at", now);
            values.put("sealed_payload", seal(taskId, payload.toString().getBytes(StandardCharsets.UTF_8)));
            database.update("tasks", values, "id=?", new String[]{taskId});
            appendAudit(database, taskId, "executor_step_" + (currentStep + 1) + "_saved", now);
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

    Memory createMemory(String kind, String content) throws Exception {
        return createMemory(kind, content, MEMORY_SOURCE_USER, "");
    }

    Memory createMemory(String kind, String content, String source, String sourceReference) throws Exception {
        String normalized = validateMemory(kind, content);
        String reference = sourceReference == null ? "" : sourceReference.trim();
        if (!MEMORY_SOURCE_USER.equals(source) && !MEMORY_SOURCE_TRACEFORM.equals(source)
                && !MEMORY_SOURCE_CONSOLIDATION.equals(source))
            throw new IllegalArgumentException("Choose a valid memory source");
        if (reference.length() > 512) throw new IllegalArgumentException("Memory source references are limited to 512 characters");
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        byte[] sealed = seal(memoryAad(id), memoryPayload(normalized, source, reference));
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("kind", kind);
        values.put("created_at", now);
        values.put("updated_at", now);
        values.put("sealed_payload", sealed);
        getWritableDatabase().insertOrThrow("memories", null, values);
        return new Memory(id, kind, normalized, source, reference, now, now);
    }

    void updateMemory(String id, String kind, String content, String source, String sourceReference) throws Exception {
        String normalized = validateMemory(kind, content);
        String reference = sourceReference == null ? "" : sourceReference.trim();
        if (!MEMORY_SOURCE_USER.equals(source) && !MEMORY_SOURCE_TRACEFORM.equals(source)
                && !MEMORY_SOURCE_CONSOLIDATION.equals(source))
            throw new IllegalArgumentException("Choose a valid memory source");
        if (reference.length() > 512) throw new IllegalArgumentException("Memory source references are limited to 512 characters");
        long now = System.currentTimeMillis();
        byte[] sealed = seal(memoryAad(id), memoryPayload(normalized, source, reference));
        ContentValues values = new ContentValues();
        values.put("kind", kind);
        values.put("updated_at", now);
        values.put("sealed_payload", sealed);
        int rows = getWritableDatabase().update("memories", values, "id=?", new String[]{id});
        if (rows == 0) throw new IllegalArgumentException("Memory not found: " + id);
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

    byte[] exportArchive(char[] passphrase) throws Exception {
        SQLiteDatabase database = getReadableDatabase();
        MemoryArchive.Data data = new MemoryArchive.Data();
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
        return MemoryArchive.encrypt(data, passphrase);
    }

    ImportResult importArchive(java.io.InputStream input, char[] passphrase) throws Exception {
        return importArchive(input, passphrase, false);
    }

    ImportResult importArchive(java.io.InputStream input, char[] passphrase, boolean preferArchiveMemories) throws Exception {
        MemoryArchive.Data data = MemoryArchive.decrypt(input, passphrase);
        SQLiteDatabase database = getWritableDatabase();
        int tasksImported = 0;
        int memoriesImported = 0;
        int tombstonesApplied = 0;
        int memoryConflicts = 0;
        int memoriesReplaced = 0;
        int recordsSkipped = 0;
        database.beginTransaction();
        try {
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
                JSONObject payload = new JSONObject().put("title", task.title)
                    .put("checkpointNote", task.checkpointNote).put("result", task.result);
                if (task.plan != null) {
                    payload.put("plan", task.plan.toJson());
                }
                ContentValues values = new ContentValues();
                values.put("id", task.id);
                values.put("created_at", task.createdAt);
                values.put("updated_at", task.updatedAt);
                values.put("status", importedStatus);
                values.put("job_id", jobId);
                values.put("execution_version", task.executionVersion);
                values.put("execution_step", task.executionStep);
                values.put("sealed_payload", seal(task.id, payload.toString().getBytes(StandardCharsets.UTF_8)));
                database.insertOrThrow("tasks", null, values);
                if (ACTIVE.equals(task.status)) appendAudit(database, task.id, "task_imported_paused", System.currentTimeMillis());
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
                    memoryConflicts++;
                    if (!preferArchiveMemories) {
                        recordsSkipped++;
                        continue;
                    }
                    ContentValues values = new ContentValues();
                    values.put("kind", memory.kind);
                    values.put("created_at", memory.createdAt);
                    values.put("updated_at", memory.updatedAt);
                    values.put("sealed_payload", seal(memoryAad(memory.id), memoryPayload(
                        memory.content, memory.source, memory.sourceReference)));
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
                    memory.content, memory.source, memory.sourceReference)));
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
        return new JSONObject().put("format", MEMORY_PAYLOAD_FORMAT).put("content", content).put("source", source)
            .put("sourceReference", sourceReference).toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Memory memoryFromPayload(String id, String kind, long createdAt, long updatedAt,
            byte[] plaintext) throws Exception {
        String decoded = new String(plaintext, StandardCharsets.UTF_8);
        try {
            JSONObject payload = new JSONObject(decoded);
            if (MEMORY_PAYLOAD_FORMAT.equals(payload.optString("format"))) {
                return new Memory(id, kind, payload.getString("content"),
                    payload.optString("source", MEMORY_SOURCE_USER), payload.optString("sourceReference", ""),
                    createdAt, updatedAt);
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
        ContentValues values = new ContentValues();
        values.put("id", UUID.randomUUID().toString());
        values.put("task_id", taskId);
        values.put("occurred_at", occurredAt);
        values.put("event", event);
        database.insertOrThrow("audit", null, values);
    }

    private byte[] seal(String taskId, byte[] plaintext) throws Exception {
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
