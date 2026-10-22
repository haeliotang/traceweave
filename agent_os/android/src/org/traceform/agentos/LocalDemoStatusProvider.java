package org.traceform.agentos;

final class LocalDemoStatusProvider implements CapabilityProvider {
    @Override
    public String capability() {
        return CapabilityBroker.DEMO_STATUS_CAPABILITY;
    }

    @Override
    public boolean supportsScope(String scope) {
        return CapabilityBroker.DEMO_STATUS_SCOPE.equals(scope);
    }

    @Override
    public boolean isAvailable(String taskId, String scope) {
        return supportsScope(scope);
    }

    @Override
    public String read(String taskId, String scope) {
        if (!supportsScope(scope)) {
            throw new SecurityException("Unsupported synthetic status scope");
        }
        return "合成状态：本地离线能力可用。";
    }

    @Override
    public void releaseTask(String taskId) {}
}
