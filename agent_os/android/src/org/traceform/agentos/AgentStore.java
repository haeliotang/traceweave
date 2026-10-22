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
    private static final int DATABASE_VERSION = 2;
    private static final String KEY_ALIAS = "org.traceform.agentos.local.v1";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_NONCE_BYTES = 12;

    static final String QUEUED = "queued";
    static final String ACTIVE = "active";
    static final String PAUSED = "paused";
    static final String COMPLETED = "completed";
    static final String CANCELLED = "cancelled";
    static final String MEMORY_PREFERENCE = "preference";
    static final String MEMORY_FACT = "fact";
    static final String MEMORY_WORKFLOW = "workflow";

    static final class Task {
        final String id;
        final String title;
        final String status;
        final long createdAt;
        final long updatedAt;

        Task(String id, String title, String status, long createdAt, long updatedAt) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    static final class Memory {
        final String id;
        final String kind;
        final String content;
        final long createdAt;
        final long updatedAt;

        Memory(String id, String kind, String content, long createdAt, long updatedAt) {
            this.id = id;
            this.kind = kind;
            this.content = content;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
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
        final int recordsSkipped;

        ImportResult(int tasksImported, int memoriesImported, int tombstonesApplied, int recordsSkipped) {
            this.tasksImported = tasksImported;
            this.memoriesImported = memoriesImported;
            this.tombstonesApplied = tombstonesApplied;
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
            + "sealed_payload BLOB NOT NULL)");
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
            } else {
                throw new IllegalStateException("No Agent OS database migration exists for version " + version);
            }
        }
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
        JSONObject payload = new JSONObject().put("title", normalized);
        byte[] sealed = seal(id, payload.toString().getBytes(StandardCharsets.UTF_8));

        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("id", id);
            values.put("created_at", now);
            values.put("updated_at", now);
            values.put("status", QUEUED);
            values.put("sealed_payload", sealed);
            database.insertOrThrow("tasks", null, values);
            appendAudit(database, id, "task_created", now);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return new Task(id, normalized, QUEUED, now, now);
    }

    List<Task> listTasks() throws Exception {
        ArrayList<Task> tasks = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "tasks", new String[]{"id", "created_at", "updated_at", "status", "sealed_payload"},
                null, null, null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                JSONObject payload = new JSONObject(new String(unseal(id, cursor.getBlob(4)), StandardCharsets.UTF_8));
                tasks.add(new Task(id, payload.getString("title"), cursor.getString(3), cursor.getLong(1), cursor.getLong(2)));
            }
        }
        return tasks;
    }

    void transition(String taskId, String targetStatus) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            String currentStatus = null;
            try (Cursor cursor = database.query("tasks", new String[]{"status"}, "id=?",
                    new String[]{taskId}, null, null, null)) {
                if (cursor.moveToFirst()) currentStatus = cursor.getString(0);
            }
            if (currentStatus == null) throw new IllegalArgumentException("Task no longer exists");
            if (!isAllowedTransition(currentStatus, targetStatus))
                throw new IllegalStateException("This task cannot move from " + currentStatus + " to " + targetStatus);

            long now = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put("status", targetStatus);
            values.put("updated_at", now);
            database.update("tasks", values, "id=?", new String[]{taskId});
            appendAudit(database, taskId, "status_" + targetStatus, now);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
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
        String normalized = validateMemory(kind, content);
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        byte[] sealed = seal(memoryAad(id), normalized.getBytes(StandardCharsets.UTF_8));
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("kind", kind);
        values.put("created_at", now);
        values.put("updated_at", now);
        values.put("sealed_payload", sealed);
        getWritableDatabase().insertOrThrow("memories", null, values);
        return new Memory(id, kind, normalized, now, now);
    }

    List<Memory> listMemories() throws Exception {
        ArrayList<Memory> memories = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "memories", new String[]{"id", "kind", "created_at", "updated_at", "sealed_payload"},
                null, null, null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String content = new String(unseal(memoryAad(id), cursor.getBlob(4)), StandardCharsets.UTF_8);
                memories.add(new Memory(id, cursor.getString(1), content, cursor.getLong(2), cursor.getLong(3)));
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
                "tasks", new String[]{"id", "created_at", "updated_at", "status", "sealed_payload"},
                null, null, null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                JSONObject payload = new JSONObject(new String(unseal(id, cursor.getBlob(4)), StandardCharsets.UTF_8));
                data.tasks.add(new Task(id, payload.getString("title"), cursor.getString(3), cursor.getLong(1), cursor.getLong(2)));
            }
        }
        try (Cursor cursor = database.query(
                "memories", new String[]{"id", "kind", "created_at", "updated_at", "sealed_payload"},
                null, null, null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String content = new String(unseal(memoryAad(id), cursor.getBlob(4)), StandardCharsets.UTF_8);
                data.memories.add(new Memory(id, cursor.getString(1), content, cursor.getLong(2), cursor.getLong(3)));
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
        MemoryArchive.Data data = MemoryArchive.decrypt(input, passphrase);
        SQLiteDatabase database = getWritableDatabase();
        int tasksImported = 0;
        int memoriesImported = 0;
        int tombstonesApplied = 0;
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
                ContentValues values = new ContentValues();
                values.put("id", task.id);
                values.put("created_at", task.createdAt);
                values.put("updated_at", task.updatedAt);
                values.put("status", task.status);
                values.put("sealed_payload", seal(task.id,
                    new JSONObject().put("title", task.title).toString().getBytes(StandardCharsets.UTF_8)));
                database.insertOrThrow("tasks", null, values);
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
                    recordsSkipped++;
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put("id", memory.id);
                values.put("kind", memory.kind);
                values.put("created_at", memory.createdAt);
                values.put("updated_at", memory.updatedAt);
                values.put("sealed_payload", seal(memoryAad(memory.id), memory.content.getBytes(StandardCharsets.UTF_8)));
                database.insertOrThrow("memories", null, values);
                memoriesImported++;
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return new ImportResult(tasksImported, memoriesImported, tombstonesApplied, recordsSkipped);
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

    private static boolean isAllowedTransition(String from, String to) {
        if (QUEUED.equals(from)) return ACTIVE.equals(to) || CANCELLED.equals(to);
        if (ACTIVE.equals(from)) return PAUSED.equals(to) || COMPLETED.equals(to) || CANCELLED.equals(to);
        if (PAUSED.equals(from)) return ACTIVE.equals(to) || COMPLETED.equals(to) || CANCELLED.equals(to);
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
