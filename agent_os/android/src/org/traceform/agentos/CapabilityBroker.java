package org.traceform.agentos;

import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

final class CapabilityBroker {
    static final String DEMO_NOTE_CAPABILITY = "demo.note.read";
    static final String DEMO_NOTE_SCOPE = "synthetic.welcome";
    static final String DEMO_STATUS_CAPABILITY = "demo.status.read";
    static final String DEMO_STATUS_SCOPE = "synthetic.device_status";
    static final String GRANTED_EVENT = auditEvent("capability_granted", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    static final String INVOKED_EVENT = auditEvent("capability_invoked", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    static final String DENIED_EVENT = auditEvent("capability_denied", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    static final String REVOKED_EVENT = auditEvent("capability_revoked", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    private static final long GRANT_DURATION_MILLIS = 5 * 60 * 1000;

    private final AgentStore store;
    private final Map<String, CapabilityProvider> providers = new HashMap<>();
    private final Map<String, Map<String, Grant>> grants = new HashMap<>();

    private static final class Grant {
        final String taskId;
        final String capability;
        final String scope;
        final long expiresAtElapsed;

        Grant(String taskId, String capability, String scope, long expiresAtElapsed) {
            this.taskId = taskId;
            this.capability = capability;
            this.scope = scope;
            this.expiresAtElapsed = expiresAtElapsed;
        }
    }

    CapabilityBroker(android.content.Context context, AgentStore store) {
        this.store = store;
        registerProvider(new LocalDemoNoteProvider());
        registerProvider(new LocalDemoStatusProvider());
        registerProvider(new AndroidRemoteStatusProvider(context));
    }

    static String auditEvent(String eventType, String capability, String scope) {
        return eventType + ":" + capability + ":" + scope;
    }

    private void registerProvider(CapabilityProvider provider) {
        providers.put(provider.capability(), provider);
    }

    boolean hasRemoteResource(String taskId) {
        return ((AndroidRemoteStatusProvider) providers.get(AndroidRemoteStatusProvider.CAPABILITY))
            .hasResource(taskId);
    }

    boolean acceptRemoteUri(String taskId, android.net.Uri uri) {
        AndroidRemoteStatusProvider provider = (AndroidRemoteStatusProvider) providers.get(AndroidRemoteStatusProvider.CAPABILITY);
        if (!provider.acceptUri(taskId, uri)) return false;
        store.appendCapabilityAudit(taskId, auditEvent("capability_source_selected",
            AndroidRemoteStatusProvider.CAPABILITY, AndroidRemoteStatusProvider.SCOPE));
        return true;
    }

    void releaseRemoteUri(String taskId) {
        ((AndroidRemoteStatusProvider) providers.get(AndroidRemoteStatusProvider.CAPABILITY)).releaseTask(taskId);
    }

    void grant(String taskId, String capability, String scope) {
        CapabilityProvider provider = providers.get(capability);
        if (provider == null || !provider.isAvailable(taskId, scope)) {
            deny(taskId, capability, scope);
            throw new SecurityException("不支持此能力或数据范围");
        }
        store.appendCapabilityAudit(taskId, auditEvent("capability_granted", capability, scope));
        grants.computeIfAbsent(taskId, ignored -> new HashMap<>())
            .put(grantKey(capability, scope), new Grant(taskId, capability, scope,
                SystemClock.elapsedRealtime() + GRANT_DURATION_MILLIS));
    }

    void deny(String taskId, String capability, String scope) {
        store.appendCapabilityAudit(taskId, auditEvent("capability_denied", capability, scope));
    }

    String invoke(String taskId, String capability, String scope) {
        Map<String, Grant> taskGrants = grants.get(taskId);
        String key = grantKey(capability, scope);
        Grant grant = taskGrants == null ? null : taskGrants.get(key);
        CapabilityProvider provider = providers.get(capability);
        if (grant == null || grant.expiresAtElapsed <= SystemClock.elapsedRealtime()
                || !taskId.equals(grant.taskId)
                || !capability.equals(grant.capability)
                || !scope.equals(grant.scope)
                || provider == null || !provider.isAvailable(taskId, scope)) {
            removeGrant(taskId, key);
            if (provider != null) provider.releaseTask(taskId);
            store.appendCapabilityAudit(taskId, auditEvent("capability_denied", capability, scope));
            throw new SecurityException("该任务没有有效的能力授权");
        }
        String result;
        try {
            result = provider.read(taskId, scope);
        } catch (RuntimeException error) {
            store.appendCapabilityAudit(taskId, auditEvent("capability_failed", capability, scope));
            throw error;
        }
        store.appendCapabilityAudit(taskId, auditEvent("capability_invoked", capability, scope));
        return result;
    }

    boolean revoke(String taskId, String capability, String scope) {
        Map<String, Grant> taskGrants = grants.get(taskId);
        String key = grantKey(capability, scope);
        Grant grant = taskGrants == null ? null : taskGrants.get(key);
        if (grant == null || grant.expiresAtElapsed <= SystemClock.elapsedRealtime()) {
            removeGrant(taskId, key);
            CapabilityProvider provider = providers.get(capability);
            if (provider != null) provider.releaseTask(taskId);
            return false;
        }
        store.appendCapabilityAudit(taskId, auditEvent("capability_revoked", capability, scope));
        removeGrant(taskId, key);
        CapabilityProvider provider = providers.get(capability);
        if (provider != null) provider.releaseTask(taskId);
        return true;
    }

    long remainingMillis(String taskId, String capability, String scope) {
        Map<String, Grant> taskGrants = grants.get(taskId);
        String key = grantKey(capability, scope);
        Grant grant = taskGrants == null ? null : taskGrants.get(key);
        if (grant == null) return 0;
        long remaining = grant.expiresAtElapsed - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
            removeGrant(taskId, key);
            CapabilityProvider provider = providers.get(capability);
            if (provider != null) provider.releaseTask(taskId);
            return 0;
        }
        return remaining;
    }

    private void removeGrant(String taskId, String key) {
        Map<String, Grant> taskGrants = grants.get(taskId);
        if (taskGrants == null) return;
        taskGrants.remove(key);
        if (taskGrants.isEmpty()) grants.remove(taskId);
    }

    private String grantKey(String capability, String scope) {
        return capability + "\n" + scope;
    }
}
