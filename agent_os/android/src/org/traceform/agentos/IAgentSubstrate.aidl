package org.traceform.agentos;

interface IAgentSubstrate {
    // Agent identity registration: the host binds package identity to the Binder caller UID.
    String registerAgent(String agentPackage, String agentPublicKeyHash, String manifestJson);

    // List capabilities available to this agent / task
    String listCapabilities(String sessionToken, String taskId);

    // Request JIT capability grant from user
    boolean requestCapability(String sessionToken, String taskId, String capability, String scope);

    // Invoke authorized capability
    String invokeCapability(String sessionToken, String taskId, String capability, String scope);

    // Revoke capability grant
    boolean revokeCapability(String sessionToken, String taskId, String capability, String scope);

    // Query immutable audit trail
    String queryAuditLog(String sessionToken, String taskId, int limit);

    // Health check and session keepalive
    String ping(String sessionToken);
}
