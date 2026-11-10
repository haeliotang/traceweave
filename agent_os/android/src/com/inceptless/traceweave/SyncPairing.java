// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Pairing with a peer identity that the user verified out of band.
 *
 * <p>Two paths exist: the code-based LAN exchange (the Rust SAS binds both P-256 fingerprints
 * and both transport keys, so typing the peer's six digits authenticates them), and the manual
 * out-of-band path where the user carries the peer's fingerprint in a pairing card or reads it
 * from the other screen.
 */
final class SyncPairing {
    static final String STATUS_ACTIVE = "active";
    static final String STATUS_REVOKED = "revoked";

    private SyncPairing() {}

    static boolean validFingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /**
     * Pairs two devices whose identities were exchanged out of band (typed full fingerprint or
     * an imported pairing card). The P-256 fingerprint is the same value the user verified, so
     * channel-bound sync can verify the peer later.
     */
    static void pairOutOfBand(AgentStore store, String localFingerprint, String remoteFingerprint,
                              String remoteDeviceId, String remotePeerId, String remoteP256Fingerprint)
            throws Exception {
        if (!validFingerprint(localFingerprint) || !validFingerprint(remoteFingerprint)
                || !validFingerprint(remoteP256Fingerprint))
            throw new IllegalArgumentException("Invalid device fingerprint");
        if (localFingerprint.equals(remoteFingerprint))
            throw new IllegalStateException("A device cannot pair with itself");
        if (!remoteFingerprint.equals(remoteP256Fingerprint))
            throw new IllegalArgumentException("The verified fingerprint must be the P-256 identity");
        if (remoteDeviceId == null || remoteDeviceId.isEmpty())
            throw new IllegalArgumentException("Missing remote device id");
        store.savePairing(remoteDeviceId, remoteFingerprint,
            remotePeerId == null ? "" : remotePeerId, STATUS_ACTIVE, remoteP256Fingerprint);
    }

    static void confirm(AgentStore store, String localFingerprint, String remoteFingerprint, String remoteDeviceId,
                        String remotePeerId, String localCode, String remoteCode) throws Exception {
        confirm(store, localFingerprint, remoteFingerprint, remoteDeviceId, remotePeerId, localCode, remoteCode, "");
    }

    /**
     * @param remoteP256Fingerprint the peer's Android Keystore key fingerprint, when known.
     *        Pairings made without it can be listed but cannot sync under channel binding.
     */
    static void confirm(AgentStore store, String localFingerprint, String remoteFingerprint, String remoteDeviceId,
                        String remotePeerId, String localCode, String remoteCode,
                        String remoteP256Fingerprint) throws Exception {
        if (!validFingerprint(localFingerprint) || !validFingerprint(remoteFingerprint))
            throw new IllegalArgumentException("Invalid device fingerprint");
        if (localFingerprint.equals(remoteFingerprint)) throw new IllegalStateException("A device cannot pair with itself");
        if (localCode == null || remoteCode == null || localCode.isEmpty() || remoteCode.isEmpty())
            throw new IllegalStateException("Enter the code shown on the other device");
        // Constant-time compare: this value decides whether a peer is trusted.
        if (!java.security.MessageDigest.isEqual(
                localCode.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                remoteCode.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new IllegalStateException("Pairing codes do not match; do not trust this device");
        if (remoteDeviceId == null || remoteDeviceId.isEmpty()) throw new IllegalArgumentException("Missing remote device id");
        store.savePairing(remoteDeviceId, remoteFingerprint, remotePeerId == null ? "" : remotePeerId,
            STATUS_ACTIVE, remoteP256Fingerprint);
    }

    static void revoke(AgentStore store, String deviceId) throws Exception {
        store.deletePairing(deviceId);
    }

    static boolean isActive(AgentStore store, String deviceId, String fingerprint) {
        AgentStore.Pairing pairing = store.pairing(deviceId);
        return pairing != null && STATUS_ACTIVE.equals(pairing.status)
            && pairing.fingerprint.equals(fingerprint);
    }
}
