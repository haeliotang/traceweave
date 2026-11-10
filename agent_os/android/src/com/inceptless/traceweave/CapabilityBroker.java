// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

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
    public static final String TRACEWEAVE_CAPABILITY = TraceweaveProvider.CAPABILITY;
    public static final String TRACEWEAVE_SCOPE = TraceweaveProvider.SCOPE;
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
    
    // Tiered permission duration options for adaptive real-world user scenarios
    public static final long DURATION_ONE_SHOT = -1; // Single-use: revoked immediately upon first invocation
    public static final long DURATION_5_MINS = 5 * 60 * 1000; // Default interactive window (5 minutes)
    public static final long DURATION_15_MINS = 15 * 60 * 1000; // Multi-step workflow (15 minutes)
    public static final long DURATION_1_HOUR = 60 * 60 * 1000; // Long session / continuous meeting (1 hour)
    public static final long DURATION_SESSION = Long.MAX_VALUE; // Session persistent until explicit revocation
    private static final long GRANT_DURATION_MILLIS = DURATION_5_MINS;
    private static final Object GRANTS_LOCK = new Object();
    private static final Map<String, Map<String, Grant>> grants = new HashMap<>();

    private final AgentStore store;
    private final Map<String, CapabilityProvider> providers = new HashMap<>();
    private final TraceweaveProvider traceweaveProvider;

    public static final class Grant {
        public final String agentId;
        public final String taskId;
        public final String capability;
        public final String scope;
        public final long expiresAtElapsed;
        public final boolean isOneShot;

        public Grant(String agentId, String taskId, String capability, String scope, long expiresAtElapsed, boolean isOneShot) {
            this.agentId = agentId == null ? "local_agent" : agentId;
            this.taskId = taskId;
            this.capability = capability;
            this.scope = scope;
            this.expiresAtElapsed = expiresAtElapsed;
            this.isOneShot = isOneShot;
        }

        public Grant(String agentId, String taskId, String capability, String scope, long expiresAtElapsed) {
            this(agentId, taskId, capability, scope, expiresAtElapsed, false);
        }

        public Grant(String taskId, String capability, String scope, long expiresAtElapsed) {
            this("local_agent", taskId, capability, scope, expiresAtElapsed, false);
        }
    }

    public CapabilityBroker(android.content.Context context, AgentStore store) {
        this.store = store;
        traceweaveProvider = new TraceweaveProvider(context);
        registerProvider(new LocalDemoNoteProvider());
        registerProvider(new LocalDemoStatusProvider(context));
        registerProvider(new AndroidRemoteStatusProvider(context));
        registerProvider(new LocalNotificationProvider(context));
        registerProvider(new LocalCalendarProvider());
        registerProvider(new LocalShareProvider());
        registerProvider(traceweaveProvider);
    }

    boolean isTraceweaveEnabled() {
        return traceweaveProvider.isEnabled();
    }

    boolean canEnableTraceweave() {
        return traceweaveProvider.hasTrustedService();
    }

    void setTraceweaveEnabled(boolean enabled) throws Exception {
        traceweaveProvider.setEnabled(enabled);
        if (!enabled) {
            for (AgentStore.Task task : store.listTasks()) {
                revoke(task.id, TRACEWEAVE_CAPABILITY, TRACEWEAVE_SCOPE);
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

    static String friendlyCapabilityName(String capability, boolean en) {
        if (capability == null) return T.s("cap.unknown", en);
        if (DEMO_NOTE_CAPABILITY.equals(capability)) return T.s("cap.demo_note", en);
        if (DEMO_STATUS_CAPABILITY.equals(capability)) return T.s("cap.demo_status", en);
        if (TRACEWEAVE_CAPABILITY.equals(capability)) return T.s("cap.traceweave", en);
        if (NOTIFICATION_CAPABILITY.equals(capability)) return T.s("cap.notification", en);
        if (CALENDAR_CAPABILITY.equals(capability)) return T.s("cap.calendar", en);
        if (SHARE_CAPABILITY.equals(capability)) return T.s("cap.share", en);
        if (AndroidRemoteStatusProvider.CAPABILITY.equals(capability)) return T.s("cap.remote_status", en);
        return capability;
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
            "任务通知推送", "向系统提交通知；需用户开启通知权限", providers.get(NOTIFICATION_CAPABILITY).isAvailable(taskId, NOTIFICATION_SCOPE)));
        descriptors.add(new CapabilityDescriptor(CALENDAR_CAPABILITY, CALENDAR_SCOPE,
            "日历日程草稿", "准备草稿；用户确认后交给日历应用，保存结果可能未知", true));
        descriptors.add(new CapabilityDescriptor(SHARE_CAPABILITY, SHARE_SCOPE,
            "文本分享导出", "打包任务输出为可供系统分享的文本摘要", true));
        if (hasRemoteResource(taskId)) {
            descriptors.add(new CapabilityDescriptor(AndroidRemoteStatusProvider.CAPABILITY,
                AndroidRemoteStatusProvider.SCOPE, "跨应用状态", "从外部应用授权 URI 读取状态", true));
        }
        if (isTraceweaveEnabled()) {
            descriptors.add(new CapabilityDescriptor(TRACEWEAVE_CAPABILITY, TRACEWEAVE_SCOPE,
                "Traceweave 本机历史摘要", "通过受控 Binder 读取 Traceweave 本机历史模拟摘要", canEnableTraceweave()));
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

    void releaseAllRemoteUris() {
        AndroidRemoteStatusProvider.releaseAllTasks();
    }

    public void grant(String agentId, String taskId, String capability, String scope, long durationMillis) {
        CapabilityProvider provider = providers.get(capability);
        if (provider == null || !provider.isAvailable(taskId, scope)) {
            deny(agentId, taskId, capability, scope);
            throw new SecurityException("不支持此能力或数据范围");
        }
        String effectiveAgent = (agentId != null && !agentId.isEmpty()) ? agentId : "local_agent";
        String agentTag = !"local_agent".equals(effectiveAgent) ? ":" + effectiveAgent : "";
        boolean isOneShot = durationMillis <= 0;
        long durationTag = isOneShot ? 0 : durationMillis;
        store.appendCapabilityAudit(taskId, auditEvent("capability_granted", capability, scope) + agentTag + ":ttl=" + durationTag);
        long now = SystemClock.elapsedRealtime();
        long expiresAt = isOneShot || durationMillis >= Long.MAX_VALUE - now
            ? Long.MAX_VALUE : now + durationMillis;
        synchronized (GRANTS_LOCK) {
            grants.computeIfAbsent(taskId, ignored -> new HashMap<>())
                .put(grantKey(effectiveAgent, capability, scope), new Grant(effectiveAgent, taskId, capability, scope,
                    expiresAt, isOneShot));
        }
    }

    public void grant(String agentId, String taskId, String capability, String scope) {
        grant(agentId, taskId, capability, scope, GRANT_DURATION_MILLIS);
    }

    public void grant(String taskId, String capability, String scope) {
        grant("local_agent", taskId, capability, scope, GRANT_DURATION_MILLIS);
    }

    public void deny(String agentId, String taskId, String capability, String scope) {
        String effectiveAgent = (agentId != null && !agentId.isEmpty()) ? agentId : "local_agent";
        String agentTag = !"local_agent".equals(effectiveAgent) ? ":" + effectiveAgent : "";
        store.appendCapabilityAudit(taskId, auditEvent("capability_denied", capability, scope) + agentTag);
    }

    public void deny(String taskId, String capability, String scope) {
        deny("local_agent", taskId, capability, scope);
    }

    public String invoke(String agentId, String taskId, String capability, String scope) {
        String effectiveAgent = (agentId != null && !agentId.isEmpty()) ? agentId : "local_agent";
        String key = grantKey(effectiveAgent, capability, scope);
        CapabilityProvider provider = providers.get(capability);
        Grant grant;
        synchronized (GRANTS_LOCK) {
            Map<String, Grant> taskGrants = grants.get(taskId);
            grant = taskGrants == null ? null : taskGrants.get(key);
            if (grant != null && !grant.isOneShot && grant.expiresAtElapsed <= SystemClock.elapsedRealtime()) {
                removeGrant(taskId, key);
                grant = null;
            }
            if (grant != null && (!effectiveAgent.equals(grant.agentId)
                    || !taskId.equals(grant.taskId)
                    || !capability.equals(grant.capability)
                    || !scope.equals(grant.scope)
                    || provider == null || !provider.isAvailable(taskId, scope))) {
                removeGrant(taskId, key);
                grant = null;
            }
            if (grant != null && grant.isOneShot) removeGrant(taskId, key);
        }
        if (grant == null) {
            removeGrant(taskId, key);
            String agentTag = !"local_agent".equals(effectiveAgent) ? ":" + effectiveAgent : "";
            store.appendCapabilityAudit(taskId, auditEvent("capability_denied", capability, scope) + agentTag);
            throw new SecurityException("该任务没有针对此 Agent 的有效能力授权");
        }
        String result;
        try {
            result = provider.read(taskId, scope);
        } catch (RuntimeException error) {
            String agentTag = !"local_agent".equals(effectiveAgent) ? ":" + effectiveAgent : "";
            store.appendCapabilityAudit(taskId, auditEvent("capability_failed", capability, scope) + agentTag);
            throw error;
        }
        String agentTag = !"local_agent".equals(effectiveAgent) ? ":" + effectiveAgent : "";
        store.appendCapabilityAudit(taskId, auditEvent("capability_invoked", capability, scope) + agentTag);
        return result;
    }

    public String invoke(String taskId, String capability, String scope) {
        return invoke("local_agent", taskId, capability, scope);
    }

    /** Drops every grant an agent identity holds, used when the user revokes the agent. */
    public static void revokeForAgent(String agentId) {
        synchronized (grants) {
            for (java.util.Iterator<Map.Entry<String, Map<String, Grant>>> task = grants.entrySet().iterator();
                 task.hasNext(); ) {
                Map<String, Grant> taskGrants = task.next().getValue();
                for (java.util.Iterator<Map.Entry<String, Grant>> grant = taskGrants.entrySet().iterator();
                     grant.hasNext(); ) {
                    if (agentId.equals(grant.next().getValue().agentId)) grant.remove();
                }
                if (taskGrants.isEmpty()) task.remove();
            }
        }
    }

    public boolean revoke(String agentId, String taskId, String capability, String scope) {
        String effectiveAgent = (agentId != null && !agentId.isEmpty()) ? agentId : "local_agent";
        String key = grantKey(effectiveAgent, capability, scope);
        Grant grant;
        synchronized (GRANTS_LOCK) {
            Map<String, Grant> taskGrants = grants.get(taskId);
            grant = taskGrants == null ? null : taskGrants.get(key);
            if (grant == null || grant.expiresAtElapsed <= SystemClock.elapsedRealtime()) {
                removeGrant(taskId, key);
                grant = null;
            } else {
                String agentTag = !"local_agent".equals(effectiveAgent) ? ":" + effectiveAgent : "";
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

    long remainingMillis(String agentId, String taskId, String capability, String scope) {
        String effectiveAgent = (agentId != null && !agentId.isEmpty()) ? agentId : "local_agent";
        synchronized (GRANTS_LOCK) {
            Map<String, Grant> taskGrants = grants.get(taskId);
            if (taskGrants == null) return 0;
            String key = grantKey(effectiveAgent, capability, scope);
            Grant grant = taskGrants.get(key);
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

    long remainingMillis(String taskId, String capability, String scope) {
        return remainingMillis("local_agent", taskId, capability, scope);
    }

    private void removeGrant(String taskId, String key) {
        synchronized (GRANTS_LOCK) {
            Map<String, Grant> taskGrants = grants.get(taskId);
            if (taskGrants == null) return;
            taskGrants.remove(key);
            if (taskGrants.isEmpty()) grants.remove(taskId);
        }
    }

    private String grantKey(String agentId, String capability, String scope) {
        return agentId + "\n" + capability + "\n" + scope;
    }
}
