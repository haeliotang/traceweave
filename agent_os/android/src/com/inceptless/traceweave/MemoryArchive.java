package com.inceptless.traceweave;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class MemoryArchive {
    private static final String FORMAT = ArchiveCompat.FORMAT;
    private static final int SCHEMA_VERSION = 6;
    private static final int ENCRYPTION_VERSION = 3;
    private static final int PBKDF2_ITERATIONS = 600000;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int HEADER_BYTES = 4 + 1 + 4 + SALT_BYTES + NONCE_BYTES;
    private static final int MAX_ARCHIVE_BYTES = 32 * 1024 * 1024;
    static final int MAX_RECORDS = 20000;
    private static final byte[] MAGIC = new byte[]{'T', 'A', 'O', 'S'};
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Data {
        final ArrayList<AgentStore.Task> tasks = new ArrayList<>();
        final ArrayList<AgentStore.Memory> memories = new ArrayList<>();
        final ArrayList<AgentStore.AuditEvent> audit = new ArrayList<>();
        final ArrayList<AgentStore.Tombstone> tombstones = new ArrayList<>();
        long exportedAt;
        /** Chain head of the audit rows inside this archive, so an outside copy can be checked. */
        String auditHead = "";
        int auditCount;
        /** False when the archive's history could not be verified (pre-chain archive). */
        boolean auditVerified;
        String auditVerification = "";
        /** True when the archive carries only the newest audit rows (the trail outgrew the cap). */
        boolean auditTrimmed;
        int auditTotal;
    }

    private MemoryArchive() {}

    static byte[] encrypt(Data data, char[] passphrase) throws Exception {
        validatePassphrase(passphrase, true);
        long recordCount = (long) data.tasks.size() + data.memories.size() + data.audit.size() + data.tombstones.size();
        if (recordCount > MAX_RECORDS) throw new IllegalArgumentException("The archive contains too many records");
        byte[] plaintext = toJson(data).getBytes(StandardCharsets.UTF_8);
        if (plaintext.length + HEADER_BYTES + GCM_TAG_BITS / 8 > MAX_ARCHIVE_BYTES) {
            Arrays.fill(plaintext, (byte) 0);
            throw new IllegalArgumentException("The archive is larger than the 32 MiB limit");
        }

        byte[] keyBytes = null;
        try {
            byte[] salt = new byte[SALT_BYTES];
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(nonce);
            byte[] header = header(salt, nonce);
            keyBytes = deriveKey(passphrase, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(header);
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(header.length + ciphertext.length).put(header).put(ciphertext).array();
        } finally {
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    static Data decrypt(InputStream input, char[] passphrase) throws Exception {
        validatePassphrase(passphrase, false);
        byte[] archive = readBounded(input);
        if (archive.length < HEADER_BYTES + GCM_TAG_BITS / 8)
            throw new IOException("This is not a complete Agent OS memory archive");
        if (archive.length > MAX_ARCHIVE_BYTES) throw new IOException("The archive is larger than the 32 MiB limit");

        ByteBuffer headerBuffer = ByteBuffer.wrap(archive, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        for (byte magicByte : MAGIC) {
            if (headerBuffer.get() != magicByte) throw new IOException("This is not an Agent OS memory archive");
        }
        int version = headerBuffer.get() & 0xff;
        int iterations = headerBuffer.getInt();
        if (version < 1 || version > ENCRYPTION_VERSION) throw new IOException("Unsupported archive encryption version");
        if (iterations != PBKDF2_ITERATIONS) throw new IOException("Unsupported archive key derivation parameters");
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        headerBuffer.get(salt);
        headerBuffer.get(nonce);
        byte[] header = Arrays.copyOfRange(archive, 0, HEADER_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(archive, HEADER_BYTES, archive.length);
        byte[] keyBytes = deriveKey(passphrase, salt);
        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(header);
            plaintext = cipher.doFinal(ciphertext);
        } catch (AEADBadTagException error) {
            throw new SecurityException("The passphrase is incorrect or the archive is damaged");
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(archive, (byte) 0);
        }
        try {
            return fromJson(decodeUtf8(plaintext));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static byte[] header(byte[] salt, byte[] nonce) {
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put((byte) ENCRYPTION_VERSION)
            .putInt(PBKDF2_ITERATIONS)
            .put(salt)
            .put(nonce)
            .array();
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) {
                int oneByte = input.read();
                if (oneByte == -1) break;
                if (output.size() + 1 > MAX_ARCHIVE_BYTES)
                    throw new IOException("The archive is larger than the 32 MiB limit");
                output.write(oneByte);
            } else {
                if (output.size() + count > MAX_ARCHIVE_BYTES)
                    throw new IOException("The archive is larger than the 32 MiB limit");
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    static byte[] deriveKey(char[] passphrase, byte[] salt) throws Exception {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(passphrase));
        byte[] password = new byte[encoded.remaining()];
        encoded.get(password);
        Mac mac = Mac.getInstance("HmacSHA256");
        try {
            mac.init(new SecretKeySpec(password, "HmacSHA256"));
            byte[] firstBlock = ByteBuffer.allocate(salt.length + 4).order(ByteOrder.BIG_ENDIAN)
                .put(salt).putInt(1).array();
            byte[] current = mac.doFinal(firstBlock);
            byte[] result = current.clone();
            for (int iteration = 1; iteration < PBKDF2_ITERATIONS; iteration++) {
                byte[] next = mac.doFinal(current);
                Arrays.fill(current, (byte) 0);
                current = next;
                for (int index = 0; index < result.length; index++) result[index] ^= current[index];
            }
            Arrays.fill(current, (byte) 0);
            Arrays.fill(firstBlock, (byte) 0);
            return result;
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    private static void validatePassphrase(char[] passphrase, boolean requireStrength) {
        if (passphrase == null || passphrase.length == 0) throw new IllegalArgumentException("Enter the archive passphrase");
        int characters = Character.codePointCount(passphrase, 0, passphrase.length);
        if (characters > 256) throw new IllegalArgumentException("The passphrase is limited to 256 characters");
        if (requireStrength && characters < 12)
            throw new IllegalArgumentException("Use a passphrase with at least 12 characters");
    }

    private static String toJson(Data data) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("exportedAt", data.exportedAt);
        JSONArray tasks = new JSONArray();
        for (AgentStore.Task task : data.tasks) {
            JSONObject taskJson = new JSONObject().put("id", task.id).put("title", task.title).put("status", task.status)
                .put("createdAt", task.createdAt).put("updatedAt", task.updatedAt)
                .put("executionVersion", task.executionVersion).put("executionStep", task.executionStep)
                .put("checkpointNote", task.checkpointNote).put("result", task.result);
            if (task.plan != null) {
                taskJson.put("plan", DocumentAction.withoutLocalAccess(task.plan).toJson());
            }
            tasks.put(taskJson);
        }
        root.put("tasks", tasks);
        JSONArray memories = new JSONArray();
        for (AgentStore.Memory memory : data.memories) {
            memories.put(new JSONObject().put("id", memory.id).put("kind", memory.kind).put("content", memory.content)
                .put("source", memory.source).put("sourceReference", memory.sourceReference)
                .put("createdAt", memory.createdAt).put("updatedAt", memory.updatedAt).put("expiresAt", memory.expiresAt)
                .put("userEdited", memory.userEdited));
        }
        root.put("memories", memories);
        JSONArray audit = new JSONArray();
        for (AgentStore.AuditEvent event : data.audit) {
            JSONObject item = new JSONObject().put("id", event.id).put("taskId", event.taskId)
                .put("occurredAt", event.occurredAt).put("event", event.event);
            if (event.hash != null && !event.hash.isEmpty()) {
                item.put("prevHash", event.prevHash).put("hash", event.hash)
                    .put("digestVersion", event.digestVersion);
            }
            audit.put(item);
        }
        root.put("audit", audit);
        JSONArray tombstones = new JSONArray();
        for (AgentStore.Tombstone tombstone : data.tombstones) {
            tombstones.put(new JSONObject().put("entityType", tombstone.entityType)
                .put("entityId", tombstone.entityId).put("deletedAt", tombstone.deletedAt));
        }
        root.put("tombstones", tombstones);
        root.put("auditHead", data.auditHead == null ? "" : data.auditHead);
        root.put("auditCount", data.auditCount);
        if (data.auditTrimmed) {
            root.put("auditTrimmed", true);
            root.put("auditTotal", data.auditTotal);
        }
        return root.toString();
    }

    private static Data fromJson(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (!ArchiveCompat.isFormatAccepted(root.getString("format"))) throw new IOException("Unknown Agent OS archive format");
        int schemaVersion = root.getInt("schemaVersion");
        if (schemaVersion < 1 || schemaVersion > SCHEMA_VERSION)
            throw new IOException("Unsupported archive data version");
        Data data = new Data();
        data.exportedAt = nonNegative(root.getLong("exportedAt"), "exportedAt");
        JSONArray tasks = root.getJSONArray("tasks");
        JSONArray memories = root.getJSONArray("memories");
        JSONArray audit = root.getJSONArray("audit");
        JSONArray tombstones = root.getJSONArray("tombstones");
        data.auditHead = root.optString("auditHead", "");
        data.auditTrimmed = root.optBoolean("auditTrimmed", false);
        data.auditTotal = root.optInt("auditTotal", 0);
        data.auditCount = root.optInt("auditCount", 0);
        long total = (long) tasks.length() + memories.length() + audit.length() + tombstones.length();
        if (total > MAX_RECORDS) throw new IOException("The archive contains too many records");

        Set<String> taskIds = new HashSet<>();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject item = tasks.getJSONObject(index);
            String id = validId(item.getString("id"));
            String title = item.getString("title");
            String status = item.getString("status");
            long createdAt = nonNegative(item.getLong("createdAt"), "task.createdAt");
            long updatedAt = nonNegative(item.getLong("updatedAt"), "task.updatedAt");
            int executionVersion = schemaVersion >= 2 ? item.optInt("executionVersion", 0) : 0;
            int executionStep = schemaVersion >= 2 ? item.optInt("executionStep", 0) : 0;
            String checkpointNote = schemaVersion >= 2 ? item.optString("checkpointNote", "") : "";
            String result = schemaVersion >= 2 ? item.optString("result", "") : "";
            if (!taskIds.add(id)) throw new IOException("The archive contains duplicate task IDs");
            if (title.trim().isEmpty() || title.length() > 2000 || updatedAt < createdAt)
                throw new IOException("The archive contains an invalid task record");
            if (!AgentStore.QUEUED.equals(status) && !AgentStore.ACTIVE.equals(status)
                    && !AgentStore.PAUSED.equals(status) && !AgentStore.WAITING_FOR_USER.equals(status)
                    && !AgentStore.FAILED.equals(status) && !AgentStore.COMPLETED.equals(status)
                    && !AgentStore.CANCELLED.equals(status) && !AgentStore.OUTCOME_UNKNOWN.equals(status))
                throw new IOException("The archive contains an invalid task status");
            int maxStep = executionVersion >= 2 ? AgentStore.MAX_DYNAMIC_STEPS : 2;
            if (executionVersion < 0 || executionVersion > AgentStore.CURRENT_EXECUTION_VERSION
                    || executionStep < 0 || executionStep > maxStep || checkpointNote.length() > 4000 || result.length() > 12000)
                throw new IOException("The archive contains an invalid task checkpoint");
            if (executionVersion == 0 && (executionStep != 0 || !checkpointNote.isEmpty() || !result.isEmpty()))
                throw new IOException("The archive contains an inconsistent task checkpoint");
            if (executionVersion == 1) {
                if ((executionStep >= 1 && checkpointNote.isEmpty()) || (executionStep == 2 && result.isEmpty()))
                    throw new IOException("The archive contains an inconsistent task checkpoint");
            } else if (executionVersion == 2) {
                if (AgentStore.COMPLETED.equals(status) && result.isEmpty())
                    throw new IOException("The archive contains an inconsistent task checkpoint");
            }
            JSONObject planJson = item.has("plan")
                ? new JSONObject(ArchiveCompat.normalizePlanJson(item.getJSONObject("plan").toString()))
                : null;
            if (planJson != null && planJson.getJSONArray("steps").length() > AgentStore.MAX_DYNAMIC_STEPS)
                throw new IOException("The archive contains an execution plan with too many steps");
            ExecutionPlan plan = planJson == null ? null : ExecutionPlan.fromJson(planJson);
            if (schemaVersion >= 6 && plan != null) {
                try { TaskContext.memories(plan); }
                catch (IllegalStateException error) { throw new IOException("The archive contains an invalid task memory snapshot", error); }
            }
            data.tasks.add(new AgentStore.Task(id, title, status, createdAt, updatedAt, 0,
                executionVersion, executionStep, checkpointNote, result, plan));
        }

        Set<String> memoryIds = new HashSet<>();
        for (int index = 0; index < memories.length(); index++) {
            JSONObject item = memories.getJSONObject(index);
            String id = validId(item.getString("id"));
            String kind = item.getString("kind");
            String content = item.getString("content");
            String source = schemaVersion >= 3
                ? ArchiveCompat.normalizeSource(item.optString("source", AgentStore.MEMORY_SOURCE_USER))
                : AgentStore.MEMORY_SOURCE_USER;
            String sourceReference = schemaVersion >= 3 ? item.optString("sourceReference", "") : "";
            long createdAt = nonNegative(item.getLong("createdAt"), "memory.createdAt");
            long updatedAt = nonNegative(item.getLong("updatedAt"), "memory.updatedAt");
            Object rawExpiry = schemaVersion >= 6 ? item.get("expiresAt") : 0;
            if (!(rawExpiry instanceof Integer) && !(rawExpiry instanceof Long)) throw new IOException("Invalid memory expiration");
            long expiresAt = nonNegative(((Number) rawExpiry).longValue(), "memory.expiresAt");
            Object rawEdited = schemaVersion >= 6 && item.has("userEdited") ? item.get("userEdited") : false;
            if (!(rawEdited instanceof Boolean)) throw new IOException("Invalid memory edit marker");
            if (!memoryIds.add(id)) throw new IOException("The archive contains duplicate memory IDs");
            if (!validMemoryKind(kind) || content.trim().isEmpty() || content.length() > 10000 || updatedAt < createdAt
                    || (!AgentStore.MEMORY_SOURCE_USER.equals(source)
                        && !AgentStore.MEMORY_SOURCE_TRACEWEAVE.equals(source)
                        && !AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(source)) || sourceReference.length() > 512)
                throw new IOException("The archive contains an invalid memory record");
            data.memories.add(new AgentStore.Memory(id, kind, content, source, sourceReference, createdAt, updatedAt, expiresAt, (Boolean) rawEdited));
        }

        Set<String> tombstoneKeys = new HashSet<>();
        for (int index = 0; index < tombstones.length(); index++) {
            JSONObject item = tombstones.getJSONObject(index);
            String entityType = item.getString("entityType");
            String entityId = validId(item.getString("entityId"));
            long deletedAt = nonNegative(item.getLong("deletedAt"), "tombstone.deletedAt");
            if (!"task".equals(entityType) && !"memory".equals(entityType))
                throw new IOException("The archive contains an invalid tombstone");
            String key = entityType + ":" + entityId;
            if (!tombstoneKeys.add(key)) throw new IOException("The archive contains duplicate tombstones");
            if (("task".equals(entityType) && taskIds.contains(entityId))
                    || ("memory".equals(entityType) && memoryIds.contains(entityId)))
                throw new IOException("The archive contains deleted content");
            data.tombstones.add(new AgentStore.Tombstone(entityType, entityId, deletedAt));
        }

        Set<String> auditIds = new HashSet<>();
        for (int index = 0; index < audit.length(); index++) {
            JSONObject item = audit.getJSONObject(index);
            String id = validId(item.getString("id"));
            String taskId = validAuditSubject(item.getString("taskId"));
            long occurredAt = nonNegative(item.getLong("occurredAt"), "audit.occurredAt");
            String event = item.getString("event");
            // The audit trail is append-only, so it can reference a task this archive does not
            // carry (the task was deleted and only its history remains). The importer drops
            // events for tasks it does not import.
            if (!auditIds.add(id) || event.trim().isEmpty()
                    || event.length() > AgentStore.MAX_AUDIT_EVENT_LENGTH)
                throw new IOException("The archive contains an invalid task history record");
            String hash = item.optString("hash", "");
            String prevHash = item.optString("prevHash", "");
            int digestVersion = item.has("digestVersion") ? item.getInt("digestVersion")
                : AuditChain.DIGEST_VERSION_LEGACY;
            if (digestVersion != AuditChain.DIGEST_VERSION_LEGACY
                    && digestVersion != AuditChain.DIGEST_VERSION_LENGTH_PREFIXED)
                throw new IOException("The archive contains an unknown audit digest version");
            data.audit.add(new AgentStore.AuditEvent(id, taskId, occurredAt, event, prevHash, hash, digestVersion));
        }
        return data;
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static long nonNegative(long value, String field) throws IOException {
        if (value < 0) throw new IOException("The archive contains an invalid " + field);
        return value;
    }

    private static String validId(String value) throws IOException {
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw new IOException("The archive contains an invalid ID");
        } catch (IllegalArgumentException error) {
            throw new IOException("The archive contains an invalid ID");
        }
        return value;
    }

    /**
     * Audit rows can point at synthetic subjects such as {@code system}; they are not UUIDs,
     * so they get a safe-character check instead of the UUID shape check.
     */
    private static String validAuditSubject(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 64) throw new IOException("The archive contains an invalid task history subject");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            boolean safe = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.' || ch == ':';
            if (!safe) throw new IOException("The archive contains an invalid task history subject");
        }
        return value;
    }

    private static boolean validMemoryKind(String kind) {
        return AgentStore.MEMORY_PREFERENCE.equals(kind) || AgentStore.MEMORY_FACT.equals(kind)
            || AgentStore.MEMORY_WORKFLOW.equals(kind);
    }
}
