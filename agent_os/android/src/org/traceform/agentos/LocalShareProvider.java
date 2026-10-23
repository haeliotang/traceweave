package org.traceform.agentos;

import org.json.JSONObject;

final class LocalShareProvider implements CapabilityProvider {
    static final String CAPABILITY = "system.share.export";
    static final String SCOPE = "system.share_text";
    static final String MIME_TYPE = "text/plain";
    static final String INTENT_ACTION = "android.intent.action.SEND";

    @Override
    public String capability() {
        return CAPABILITY;
    }

    @Override
    public boolean supportsScope(String scope) {
        return SCOPE.equals(scope);
    }

    @Override
    public boolean isAvailable(String taskId, String scope) {
        return supportsScope(scope);
    }

    @Override
    public String read(String taskId, String scope) {
        if (!isAvailable(taskId, scope)) {
            throw new IllegalStateException("Share provider does not support scope: " + scope);
        }

        try {
            JSONObject shareData = new JSONObject();
            shareData.put("provider", "system.share");
            shareData.put("format", MIME_TYPE);
            shareData.put("intent_action", INTENT_ACTION);
            shareData.put("subject", "Agent OS 任务报告");
            shareData.put("content", "Agent OS 离线推演报告：已完成端侧多步核对与数据治理。(Task=" + taskId + ")");
            shareData.put("status", "ready_for_sharing");
            return shareData.toString();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to prepare share export: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void releaseTask(String taskId) {}
}
