// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: Apache-2.0

package com.inceptless.traceweave;

interface IAgentSubstrate {
    // The host derives identity from the Binder caller UID, package, and installed APK signer.
    // The legacy claim remains in the wire shape for compatibility and is ignored.
    String registerAgent(String agentPackage, String legacyAgentPublicKeyHash, String manifestJson);

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
