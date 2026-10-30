package org.traceform.agentos;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Short authentication string for pairing two devices.
 *
 * Both sides feed the same two fingerprints and nonce and compare the resulting
 * six digits out of band. The code is a display aid, not a secret or a key.
 */
final class SyncPairing {
    static final String STATUS_ACTIVE = "active";
    static final String STATUS_REVOKED = "revoked";

    private SyncPairing() {}

    static boolean validFingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /** Six decimal digits derived from both fingerprints and a shared nonce. */
    static String code(String localFingerprint, String remoteFingerprint, byte[] nonce) throws Exception {
        if (!validFingerprint(localFingerprint) || !validFingerprint(remoteFingerprint))
            throw new IllegalArgumentException("Both fingerprints must be 32-byte hex values");
        if (localFingerprint.equals(remoteFingerprint))
            throw new IllegalArgumentException("A device cannot pair with itself");
        if (nonce == null || nonce.length != 32) throw new IllegalArgumentException("A 32-byte nonce is required");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("traceform-pairing-v1".getBytes(StandardCharsets.UTF_8));
        digest.update(localFingerprint.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(remoteFingerprint.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(nonce);
        byte[] hashed = digest.digest();
        long value = (((long) (hashed[0] & 0xff) << 24) | ((long) (hashed[1] & 0xff) << 16)
            | ((long) (hashed[2] & 0xff) << 8) | (hashed[3] & 0xff)) % 1_000_000L;
        return String.format(java.util.Locale.ROOT, "%06d", value);
    }

    /** Pair only when both sides derived the same code and the fingerprints differ. */
    static void confirm(AgentStore store, String localFingerprint, String remoteFingerprint, String remoteDeviceId,
                        String remotePeerId, String localCode, String remoteCode) throws Exception {
        if (!validFingerprint(localFingerprint) || !validFingerprint(remoteFingerprint))
            throw new IllegalArgumentException("Invalid device fingerprint");
        if (localFingerprint.equals(remoteFingerprint)) throw new IllegalStateException("A device cannot pair with itself");
        if (localCode == null || remoteCode == null || !localCode.equals(remoteCode))
            throw new IllegalStateException("Pairing codes do not match; do not trust this device");
        if (remoteDeviceId == null || remoteDeviceId.isEmpty()) throw new IllegalArgumentException("Missing remote device id");
        store.savePairing(remoteDeviceId, remoteFingerprint, remotePeerId == null ? "" : remotePeerId, STATUS_ACTIVE);
    }
}
