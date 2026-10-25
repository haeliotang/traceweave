package org.traceform.agentos;

final class LocalDemoNoteProvider implements CapabilityProvider {
    @Override
    public String capability() {
        return CapabilityBroker.DEMO_NOTE_CAPABILITY;
    }

    @Override
    public boolean supportsScope(String scope) {
        return CapabilityBroker.DEMO_NOTE_SCOPE.equals(scope);
    }

    @Override
    public boolean isAvailable(String taskId, String scope) {
        return supportsScope(scope);
    }

    @Override
    public String read(String taskId, String scope) {
        if (!supportsScope(scope)) {
            throw new SecurityException("Unsupported demo note scope");
        }
        return "合成示例便笺：欢迎使用本地 Agent OS 原型；此内容为固定测试夹具。";
    }

    @Override
    public void releaseTask(String taskId) {}
}
