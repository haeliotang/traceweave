package org.traceform.agentos;

import android.app.Activity;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class DeviceValidationActivity extends Activity {
    static final String ACTION_SEED_ARCHIVE = "org.traceform.agentos.validation.SEED_ARCHIVE";
    static final String ACTION_VERIFY_IMPORT = "org.traceform.agentos.validation.VERIFY_IMPORT";
    static final String ACTION_VERIFY_CONFLICT = "org.traceform.agentos.validation.VERIFY_CONFLICT";
    static final String ACTION_VERIFY_CAPABILITY_AUDIT = "org.traceform.agentos.validation.VERIFY_CAPABILITY_AUDIT";
    static final String ACTION_VERIFY_STATUS_PROVIDER_AUDIT = "org.traceform.agentos.validation.VERIFY_STATUS_PROVIDER_AUDIT";
    static final String ACTION_VERIFY_REMOTE_PROVIDER_AUDIT = "org.traceform.agentos.validation.VERIFY_REMOTE_PROVIDER_AUDIT";
    static final String ACTION_VERIFY_REMOTE_PROVIDER_FAILURE = "org.traceform.agentos.validation.VERIFY_REMOTE_PROVIDER_FAILURE";
    static final String ACTION_VERIFY_REMOTE_PROVIDER_RESTART = "org.traceform.agentos.validation.VERIFY_REMOTE_PROVIDER_RESTART";
    private static final String TEST_PASSPHRASE = "synthetic-only-agent-os-archive-passphrase";
    private static final String TEST_TASK_TITLE = "SYNTHETIC cross-install task";
    private static final String TEST_MEMORY_CONTENT = "SYNTHETIC preference for cross-install validation";

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
                } else {
                    result = runMigrationAndKdf();
                }
            } catch (Exception error) {
                result = "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage();
            }
            String completed = result;
            runOnUiThread(() -> resultView.setText(completed));
        }, "agent-os-validation").start();
    }

    private String runMigrationAndKdf() throws Exception {
        deleteDatabase("agent_os.db");
        AgentStore original = new AgentStore(this);
        AgentStore.Task task;
        try {
            task = original.createTask("SYNTHETIC database migration task");
            original.transition(task.id, AgentStore.ACTIVE);
        } finally {
            original.close();
        }

        SQLiteDatabase legacy = SQLiteDatabase.openDatabase(
            getDatabasePath("agent_os.db").getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            legacy.execSQL("DROP TABLE memories");
            legacy.execSQL("DROP TABLE tombstones");
            legacy.setVersion(1);
        } finally {
            legacy.close();
        }

        AgentStore migrated = new AgentStore(this);
        try {
            List<AgentStore.Task> tasks = migrated.listTasks();
            if (tasks.size() != 1 || !task.id.equals(tasks.get(0).id)
                    || !"SYNTHETIC database migration task".equals(tasks.get(0).title)
                    || !AgentStore.ACTIVE.equals(tasks.get(0).status)) {
                throw new IllegalStateException("The version 1 task was not preserved");
            }
            if (!migrated.listMemories().isEmpty()) {
                throw new IllegalStateException("The migrated memory table should be empty");
            }

            SQLiteDatabase database = migrated.getReadableDatabase();
            if (database.getVersion() != 2 || !tableExists(database, "memories")
                    || !tableExists(database, "tombstones") || auditCount(database, task.id) != 2) {
                throw new IllegalStateException("The version 1 to 2 migration did not preserve schema and audit data");
            }
        } finally {
            migrated.close();
        }

        return "PASS: database v1→v2 migration preserved its encrypted task and both audit events.\n"
            + "PBKDF2-HMAC-SHA256 600,000 iterations: median of 3 device measurements = "
            + measureKdfMedianMillis() + " ms.";
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
            if (taskId == null || memoryId == null) {
                throw new IllegalArgumentException("Expected source record metadata was not supplied");
            }

            AgentStore.Task task = findTask(store.listTasks(), taskId);
            AgentStore.Memory memory = findMemory(store.listMemories(), memoryId);
            if (task == null || memory == null || !taskTitle.equals(task.title)
                    || !taskStatus.equals(task.status) || taskCreatedAt != task.createdAt
                    || taskUpdatedAt != task.updatedAt || !memoryKind.equals(memory.kind)
                    || !memoryContent.equals(memory.content) || memoryCreatedAt != memory.createdAt
                    || memoryUpdatedAt != memory.updatedAt
                    || auditCount(store.getReadableDatabase(), taskId) != taskAuditCount) {
                throw new IllegalStateException("Imported IDs, content, timestamps, status, or audit history changed");
            }
            return conflictCheck
                ? "PASS: a same-ID archive with different task and memory content did not overwrite local records."
                : "PASS: another installation retained task/memory IDs, content, timestamps, status, and all audit events.";
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
