package com.inceptless.traceweave;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/** One claimed write, followed by bounded readback. Recovery never writes. */
final class DocumentAction {
    static final String CAPABILITY = "system.document.write_verified";
    static final String SCOPE = "user_selected_document";
    private static final int MAX_BYTES = 32000;
    static volatile Runnable afterWriteForValidation;

    private DocumentAction() {}

    static String markdown(String goal, List<AgentStore.Memory> memories) {
        StringBuilder text = new StringBuilder("# ").append(goal.trim()).append("\n\n## 已选择的记忆 / Selected memories\n\n");
        for (AgentStore.Memory memory : memories)
            text.append("- ").append(memory.content).append("\n");
        if (memories.isEmpty()) text.append("未选择记忆 / No memories selected.\n");
        text.append("\n## 执行清单 / Checklist\n\n- [ ] 核对目标与选定信息 / Review the goal and selected information\n")
            .append("- [ ] 按上述信息安排下一步 / Decide the next action\n");
        validateContent(text.toString());
        return text.toString();
    }

    static JSONObject draft(String content, JSONArray sources) throws Exception {
        validateContent(content);
        if (sources == null || sources.toString().length() > 12000)
            throw new IllegalArgumentException("Document sources exceed size limit");
        return new JSONObject().put("draftId", UUID.randomUUID().toString()).put("status", "prepared")
            .put("fileName", "agent-os-" + System.currentTimeMillis() + ".md")
            .put("content", content).put("sha256", sha256(content.getBytes(StandardCharsets.UTF_8)))
            .put("memorySources", sources);
    }

    static void validateContent(String content) {
        if (content == null || content.trim().isEmpty() || content.length() > 8000
                || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Markdown must contain 1–8000 characters (up to 32000 UTF-8 bytes)");
    }

    static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    static boolean hasReceipt(JSONObject value) {
        return ("created".equals(value.optString("status")) || "verified_existing".equals(value.optString("status")))
            && "document_readback".equals(value.optString("verification"))
            && value.optString("sha256").matches("[0-9a-f]{64}")
            && !value.optString("attemptId").isEmpty() && !value.optString("taskId").isEmpty()
            && value.optLong("byteCount", -1) > 0 && value.optLong("verifiedAt", 0) > 0;
    }

    static boolean locallyVerified(AgentStore.Task task) {
        try {
            if (!AgentStore.COMPLETED.equals(task.status) || task.plan == null || task.plan.totalSteps() != 1
                    || !CAPABILITY.equals(task.plan.steps.get(0).capability)) return false;
            JSONObject receipt = new JSONObject(task.plan.steps.get(0).observation);
            return hasReceipt(receipt) && receipt.optBoolean("localAccess");
        } catch (Exception error) { return false; }
    }

    static String readVerifiedContent(ContentResolver resolver, AgentStore.Task task) throws Exception {
        if (!locallyVerified(task)) throw new IllegalStateException("Verify the document on this device first");
        JSONObject receipt = new JSONObject(task.plan.steps.get(0).observation);
        Uri uri = contentUri(receipt.getString("uri"));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new java.io.IOException("Document provider is unavailable");
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new java.io.IOException("Document exceeds the verified size limit");
                bytes.write(buffer, 0, count);
            }
        }
        byte[] data = bytes.toByteArray();
        if (data.length != receipt.getInt("byteCount") || !sha256(data).equals(receipt.getString("sha256")))
            throw new IllegalStateException("The document changed since verification; verify it again before sharing");
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();
        validateContent(text);
        return text;
    }

    static Uri contentUri(String value) {
        Uri uri = Uri.parse(value);
        if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null || value.length() > 2000)
            throw new IllegalArgumentException("Select a document through the system file picker");
        return uri;
    }

    static ExecutionPlan withoutLocalAccess(ExecutionPlan plan) throws Exception {
        if (plan == null) return null;
        ExecutionPlan copied = plan;
        for (int index = 0; index < plan.totalSteps(); index++) {
            ExecutionPlan.Step step = plan.steps.get(index);
            if (!CAPABILITY.equals(step.capability) || step.observation.isEmpty()) continue;
            JSONObject value = new JSONObject(step.observation);
            if (value.has("uri")) value.put("originUri", value.getString("uri"));
            value.remove("uri");
            value.put("localAccess", false).put("receiptOrigin", "archive");
            copied = copied.withStepUpdated(index, step.withStatusAndObservation(step.status, value.toString(), step.executedAt), copied.currentStepIndex);
        }
        return copied;
    }

    static void writeOnce(ContentResolver resolver, AgentStore store, String taskId) throws Exception {
        JSONObject payload = store.claimDocumentWrite(taskId);
        String attempt = payload.getString("attemptId");
        Uri uri = contentUri(payload.getString("uri"));
        byte[] content = payload.getString("content").getBytes(StandardCharsets.UTF_8);
        try {
            try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException("Document provider is unavailable");
                output.write(content);
                output.flush();
            }
            Runnable hook = afterWriteForValidation;
            if (hook != null) hook.run();
            verify(resolver, store, taskId, attempt, uri);
        } catch (Exception error) {
            store.documentOutcomeUnknown(taskId, attempt, payload.optString("verificationId", attempt), "write_or_readback_interrupted");
            throw error;
        } finally { java.util.Arrays.fill(content, (byte) 0); }
    }

    static void verify(ContentResolver resolver, AgentStore store, String taskId, String attempt, Uri uri) throws Exception {
        JSONObject active = new JSONObject(store.getTask(taskId).plan.steps.get(0).observation);
        if (!attempt.equals(active.optString("attemptId")) || !uri.toString().equals(active.optString("uri")) || !active.optBoolean("localAccess"))
            throw new SecurityException("Readback does not match the current selected file");
        String verificationId = active.optString("verificationId", attempt);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int size = 0;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("Document provider is unavailable");
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > MAX_BYTES) throw new java.io.IOException("Document is larger than the approved content");
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            store.completeDocumentVerification(taskId, attempt, verificationId, uri.toString(), hex.toString(), size);
        } catch (Exception error) {
            store.documentOutcomeUnknown(taskId, attempt, verificationId, "readback_failed_or_access_lost");
            throw error;
        }
    }
}
