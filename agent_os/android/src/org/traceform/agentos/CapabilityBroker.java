package org.traceform.agentos;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CapabilityBroker {
    public static final String DEMO_NOTE_CAPABILITY = "demo.note.read";
    public static final String DEMO_NOTE_SCOPE = "synthetic.welcome";
    public static final String DEMO_STATUS_CAPABILITY = "demo.status.read";
    public static final String DEMO_STATUS_SCOPE = "synthetic.device_status";
    public static final String TRACEFORM_CAPABILITY = TraceformProvider.CAPABILITY;
    public static final String TRACEFORM_SCOPE = TraceformProvider.SCOPE;
    public static final String NOTIFICATION_CAPABILITY = LocalNotificationProvider.CAPABILITY;
    public static final String NOTIFICATION_SCOPE = LocalNotificationProvider.SCOPE;
    public static final String CALENDAR_CAPABILITY = LocalCalendarProvider.CAPABILITY;
    public static final String CALENDAR_SCOPE = LocalCalendarProvider.SCOPE;
    public static final String SHARE_CAPABILITY = LocalShareProvider.CAPABILITY;
    public static final String SHARE_SCOPE = LocalShareProvider.SCOPE;
    public static final String GRANTED_EVENT = auditEvent("capability_granted", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    public static final String INVOKED_EVENT = auditEvent("capability_invoked", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    public static final String DENIED_EVENT = auditEvent("capability_denied", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    public static final String REVOKED_EVENT = auditEvent("capability_revoked", DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE);
    private static final long GRANT_DURATION_MILLIS = 5 * 60 * 1000;
    private static final Object GRANTS_LOCK = new Object();
    private static final Map<String, Map<String, Grant>> grants = new HashMap<>();

    private final AgentStore store;
    private final Map<String, CapabilityProvider> providers = new HashMap<>();
    private final TraceformProvider traceformProvider;

    public static final class Grant {
        public final String agentId;
        public final String taskId;
        public final String capability;
        public final String scope;
        public final long expiresAtElapsed;

        public Grant(String agentId, String taskId, String capability, String scope, long expiresAtElapsed) {
            this.agentId = agentId == null ? "local_agent" : agentId;
            this.taskId = taskId;
            this.capability = capability;
            this.scope = scope;
            this.expiresAtElapsed = expiresAtElapsed;
        }

        public Grant(String taskId, String capability, String scope, long expiresAtElapsed) {
            this("local_agent", taskId, capability, scope, expiresAtElapsed);
        }
    }

    public CapabilityBroker(android.content.Context context, AgentStore store) {
        this.store = store;
        traceformProvider = new TraceformProvider(context);
        registerProvider(new LocalDemoNoteProvider());
        registerProvider(new LocalDemoStatusProvider());
        registerProvider(new AndroidRemoteStatusProvider(context));
        registerProvider(new LocalNotificationProvider(context));
        registerProvider(new LocalCalendarProvider());
        registerProvider(new LocalShareProvider());
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
        if (NOTIFICATION_CAPABILITY.equals(capability)) return "系统任务通知";
        if (CALENDAR_CAPABILITY.equals(capability)) return "系统日历待办";
        if (SHARE_CAPABILITY.equals(capability)) return "系统分享导出";
        if (AndroidRemoteStatusProvider.CAPABILITY.equals(capability)) return "跨应用状态";
        return capability == null ? "未知能力" : capability;
    }

    boolean isGranted(String taskId, String capability, String scope) {
        return remainingMillis(taskId, capability, scope) > 0;
    }

    public List<CapabilityDescriptor> listAvailableCapabilities(String taskId) {
        List<CapabilityDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new CapabilityDescriptor(DEMO_NOTE_CAPABILITY, DEMO_NOTE_SCOPE,
            "示例便笺", "读取本地固定的合成便笺内容", true));
        descriptors.add(new CapabilityDescriptor(DEMO_STATUS_CAPABILITY, DEMO_STATUS_SCOPE,
            "合成状态", "读取本地设备合成运行状态", true));
        descriptors.add(new CapabilityDescriptor(NOTIFICATION_CAPABILITY, NOTIFICATION_SCOPE,
            "任务通知推送", "在系统通知栏发送本地任务状态与提醒", true));
        descriptors.add(new CapabilityDescriptor(CALENDAR_CAPABILITY, CALENDAR_SCOPE,
            "日历日程待办", "生成规范的系统日历日程事项", true));
        descriptors.add(new CapabilityDescriptor(SHARE_CAPABILITY, SHARE_SCOPE,
            "文本分享导出", "打包任务输出为可供系统分享的文本摘要", true));
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

    public void grant(String agentId, String taskId, String capability, String scope) {
        CapabilityProvider provider = providers.get(capability);
        if (provider == null || !provider.isAvailable(taskId, scope)) {
            deny(agentId, taskId, capability, scope);
            throw new SecurityException("不支持此能力或数据范围");
        }
        String agentTag = (agentId != null && !agentId.isEmpty() && !"local_agent".equals(agentId)) ? ":" + agentId : "";
        store.appendCapabilityAudit(taskId, auditEvent("capability_granted", capability, scope) + agentTag);
        synchronized (GRANTS_LOCK) {
            grants.computeIfAbsent(taskId, ignored -> new HashMap<>())
                .put(grantKey(capability, scope), new Grant(agentId, taskId, capability, scope,
                    SystemClock.elapsedRealtime() + GRANT_DURATION_MILLIS));
        }
    }

    public void grant(String taskId, String capability, String scope) {
        grant("local_agent", taskId, capability, scope);
    }

    public void deny(String agentId, String taskId, String capability, String scope) {
        String agentTag = (agentId != null && !agentId.isEmpty() && !"local_agent".equals(agentId)) ? ":" + agentId : "";
        store.appendCapabilityAudit(taskId, auditEvent("capability_denied", capability, scope) + agentTag);
    }

    public void deny(String taskId, String capability, String scope) {
        deny("local_agent", taskId, capability, scope);
    }

    public String invoke(String agentId, String taskId, String capability, String scope) {
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
            String agentTag = (agentId != null && !agentId.isEmpty() && !"local_agent".equals(agentId)) ? ":" + agentId : "";
            store.appendCapabilityAudit(taskId, auditEvent("capability_denied", capability, scope) + agentTag);
            throw new SecurityException("该任务没有有效的能力授权");
        }
        String result;
        try {
            result = provider.read(taskId, scope);
        } catch (RuntimeException error) {
            String agentTag = (agentId != null && !agentId.isEmpty() && !"local_agent".equals(agentId)) ? ":" + agentId : "";
            store.appendCapabilityAudit(taskId, auditEvent("capability_failed", capability, scope) + agentTag);
            throw error;
        }
        String agentTag = (agentId != null && !agentId.isEmpty() && !"local_agent".equals(agentId)) ? ":" + agentId : "";
        store.appendCapabilityAudit(taskId, auditEvent("capability_invoked", capability, scope) + agentTag);
        return result;
    }

    public String invoke(String taskId, String capability, String scope) {
        return invoke("local_agent", taskId, capability, scope);
    }

    public boolean revoke(String agentId, String taskId, String capability, String scope) {
        String key = grantKey(capability, scope);
        Grant grant;
        synchronized (GRANTS_LOCK) {
            Map<String, Grant> taskGrants = grants.get(taskId);
            grant = taskGrants == null ? null : taskGrants.get(key);
            if (grant == null || grant.expiresAtElapsed <= SystemClock.elapsedRealtime()) {
                removeGrant(taskId, key);
                grant = null;
            } else {
                String agentTag = (agentId != null && !agentId.isEmpty() && !"local_agent".equals(agentId)) ? ":" + agentId : "";
                store.appendCapabilityAudit(taskId, auditEvent("capability_revoked", capability, scope) + agentTag);
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

    public boolean revoke(String taskId, String capability, String scope) {
        return revoke("local_agent", taskId, capability, scope);
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
