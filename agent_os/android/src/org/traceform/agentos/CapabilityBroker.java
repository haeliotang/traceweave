package org.traceform.agentos;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CapabilityBroker {
    static final String DEMO_NOTE_CAPABILITY = "demo.note.read";
    static final String DEMO_NOTE_SCOPE = "synthetic.welcome";
    static final String DEMO_STATUS_CAPABILITY = "demo.status.read";
    static final String DEMO_STATUS_SCOPE = "synthetic.device_status";
    static final String TRACEFORM_CAPABILITY = TraceformProvider.CAPABILITY;
    static final String TRACEFORM_SCOPE = TraceformProvider.SCOPE;
    static final String GRANTED_EVENT = auditEvent("capability_granted", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    static final String INVOKED_EVENT = auditEvent("capability_invoked", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    static final String DENIED_EVENT = auditEvent("capability_denied", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    static final String REVOKED_EVENT = auditEvent("capability_revoked", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    private static final long GRANT_DURATION_MILLIS = 5 * 60 * 1000;
    private static final Object GRANTS_LOCK = new Object();
    private static final Map<String, Map<String, Grant>> grants = new HashMap<>();

    private final AgentStore store;
    private final Map<String, CapabilityProvider> providers = new HashMap<>();
    private final TraceformProvider traceformProvider;

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
        traceformProvider = new TraceformProvider(context);
        registerProvider(new LocalDemoNoteProvider());
        registerProvider(new LocalDemoStatusProvider());
        registerProvider(new AndroidRemoteStatusProvider(context));
        registerProvider(traceformProvider);
    }

    boolean isTraceformEnabled() {
        return traceformProvider.isEnabled();
    }

    boolean canEnableTraceform() {
        return traceformProvider.isSignatureMatched();
    }

    void setTraceformEnabled(boolean enabled) throws Exception {
        traceformProvider.setEnabled(enabled);
        if (!enabled) {
            for (AgentStore.Task task : store.listTasks()) {
                revoke(task.id, TRACEFORM_CAPABILITY, TRACEFORM_SCOPE);
            }
        }
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

    static String friendlyCapabilityName(String capability) {
        if (DEMO_NOTE_CAPABILITY.equals(capability)) return "示例便笺";
        if (DEMO_STATUS_CAPABILITY.equals(capability)) return "设备运行状态";
        if (TRACEFORM_CAPABILITY.equals(capability)) return "Traceform 本机历史摘要";
        if (AndroidRemoteStatusProvider.CAPABILITY.equals(capability)) return "跨应用状态";
        return capability == null ? "未知能力" : capability;
    }

    boolean isGranted(String taskId, String capability, String scope) {
        return remainingMillis(taskId, capability, scope) > 0;
    }

    List<CapabilityDescriptor> listAvailableCapabilities(String taskId) {
        List<CapabilityDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new CapabilityDescriptor(DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE,
            "示例便笺", "读取本地固定的合成便笺内容", true));
        descriptors.add(new CapabilityDescriptor(DEMO_STATUS_CAPABILITY, DEMO_STATUS_SCOPE,
            "合成状态", "读取本地设备合成运行状态", true));
        if (hasRemoteResource(taskId)) {
            descriptors.add(new CapabilityDescriptor(AndroidRemoteStatusProvider.CAPABILITY,
                AndroidRemoteStatusProvider.SCOPE, "跨应用状态", "从外部应用授权 URI 读取状态", true));
        }
        if (isTraceformEnabled()) {
            descriptors.add(new CapabilityDescriptor(TRACEFORM_CAPABILITY, TRACEFORM_SCOPE,
                "Traceform 本机历史摘要", "通过受控签名 Binder 读取 Traceform 本机历史与模拟摘要", canEnableTraceform()));
        }
        return descriptors;
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
        synchronized (GRANTS_LOCK) {
            grants.computeIfAbsent(taskId, ignored -> new HashMap<>())
                .put(grantKey(capability, scope), new Grant(taskId, capability, scope,
                    SystemClock.elapsedRealtime() + GRANT_DURATION_MILLIS));
        }
    }

    void deny(String taskId, String capability, String scope) {
        store.appendCapabilityAudit(taskId, auditEvent("capability_denied", capability, scope));
    }

    String invoke(String taskId, String capability, String scope) {
        String key = grantKey(capability, scope);
        Grant grant;
        synchronized (GRANTS_LOCK) {
            Map<String, Grant> taskGrants = grants.get(taskId);
            grant = taskGrants == null ? null : taskGrants.get(key);
            if (grant != null && grant.expiresAtElapsed <= SystemClock.elapsedRealtime()) {
                removeGrant(taskId, key);
                grant = null;
            }
        }
        CapabilityProvider provider = providers.get(capability);
        if (grant == null
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
        String key = grantKey(capability, scope);
        Grant grant;
        synchronized (GRANTS_LOCK) {
            Map<String, Grant> taskGrants = grants.get(taskId);
            grant = taskGrants == null ? null : taskGrants.get(key);
            if (grant == null || grant.expiresAtElapsed <= SystemClock.elapsedRealtime()) {
                removeGrant(taskId, key);
                grant = null;
            } else {
                store.appendCapabilityAudit(taskId, auditEvent("capability_revoked", capability, scope));
                removeGrant(taskId, key);
            }
        }
        if (grant == null) {
            CapabilityProvider provider = providers.get(capability);
            if (provider != null) provider.releaseTask(taskId);
            return false;
        }
        CapabilityProvider provider = providers.get(capability);
        if (provider != null) provider.releaseTask(taskId);
        return true;
    }

    long remainingMillis(String taskId, String capability, String scope) {
        synchronized (GRANTS_LOCK) {
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
    }

    private void removeGrant(String taskId, String key) {
        synchronized (GRANTS_LOCK) {
            Map<String, Grant> taskGrants = grants.get(taskId);
            if (taskGrants == null) return;
            taskGrants.remove(key);
            if (taskGrants.isEmpty()) grants.remove(taskId);
        }
    }

    private String grantKey(String capability, String scope) {
        return capability + "\n" + scope;
    }
}
