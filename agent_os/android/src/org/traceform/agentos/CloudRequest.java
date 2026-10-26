package org.traceform.agentos;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** A previewed, immutable request with a process-local, single-use approval. */
final class CloudRequest {
    final String taskId;
    final String goal;
    final String endpoint;
    final String body;
    final String sha256;
    final String contextFingerprint;
    final CloudModelConfig config;
    final List<CapabilityDescriptor> capabilities;
    final boolean documentDraft;
    private final long expiresAt = SystemClock.elapsedRealtime() + 5 * 60 * 1000;
    private final AtomicBoolean approved = new AtomicBoolean();
    private final AtomicBoolean consumed = new AtomicBoolean();
    private volatile boolean revoked;

    CloudRequest(AgentStore.Task task, String body, List<CapabilityDescriptor> capabilities, CloudModelConfig config) throws Exception {
        this(task, body, capabilities, config, false);
    }

    CloudRequest(AgentStore.Task task, String body, List<CapabilityDescriptor> capabilities, CloudModelConfig config,
                 boolean documentDraft) throws Exception {
        this.taskId = task.id;
        this.goal = task.title;
        this.body = body;
        this.endpoint = CloudModelClient.normalizeEndpointUrl(config.endpointUrl);
        this.sha256 = DocumentAction.sha256(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.contextFingerprint = TaskContext.fingerprint(task);
        this.capabilities = Collections.unmodifiableList(new ArrayList<>(capabilities));
        this.config = config;
        this.documentDraft = documentDraft;
    }

    void approveOnce() {
        if (revoked || consumed.get() || SystemClock.elapsedRealtime() >= expiresAt)
            throw new SecurityException("This cloud preview expired or was revoked");
        approved.set(true);
    }

    void revoke() { revoked = true; approved.set(false); }

    void consume(AgentStore store) throws Exception {
        if (revoked || SystemClock.elapsedRealtime() >= expiresAt || !approved.compareAndSet(true, false)
                || !consumed.compareAndSet(false, true))
            throw new SecurityException("No current single-request cloud consent");
        AgentStore.Task task = store.getTask(taskId);
        if (task == null || !AgentStore.QUEUED.equals(task.status) || !contextFingerprint.equals(TaskContext.fingerprint(task)))
            throw new SecurityException("Task or selected context changed after preview");
    }
}
