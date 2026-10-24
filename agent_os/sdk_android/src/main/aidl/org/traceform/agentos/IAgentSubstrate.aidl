// IAgentSubstrate.aidl
// Substrate Protocol v1.0 standard AIDL definition for Android IPC
package org.traceform.agentos;

interface IAgentSubstrate {
    // The host derives identity from the Binder caller UID, package, and installed APK signer.
    // The legacy claim remains in the wire shape for compatibility and is ignored.
    String registerAgent(String agentPackage, String legacyAgentPublicKeyHash, String manifestJson);

    // List available capabilities and active grants for task
    String listCapabilities(String sessionToken, String taskId);

    // Request JIT capability grant (triggers user authorization UI if needed)
    boolean requestCapability(String sessionToken, String taskId, String capability, String scope);

    // Invoke authorized capability
    String invokeCapability(String sessionToken, String taskId, String capability, String scope);

    // Revoke capability grant
    boolean revokeCapability(String sessionToken, String taskId, String capability, String scope);

    // Query immutable audit trail
    String queryAuditLog(String sessionToken, String taskId, int limit);

    // Ping / telemetry
    String ping(String sessionToken);
}
