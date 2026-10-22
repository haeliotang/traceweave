package org.traceform.agentos;

interface CapabilityProvider {
    String capability();

    boolean supportsScope(String scope);

    boolean isAvailable(String taskId, String scope);

    String read(String taskId, String scope);

    void releaseTask(String taskId);
}
