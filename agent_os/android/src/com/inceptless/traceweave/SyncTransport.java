package com.inceptless.traceweave;

/** JNI bridge for the sync transport; the app disables sync when the library is absent. */
final class SyncTransport {
    interface Handler {
        byte[] handle(byte[] request);
    }

    private static boolean loaded;
    private static boolean failed;

    private SyncTransport() {}

    /** Device sync seed, wrapped by a non-exportable Keystore key (see SeedVault). */
    static String seed(android.content.Context context) {
        try {
            String existing = SeedVault.read(context);
            if (existing != null && !existing.isEmpty()) return existing;
        } catch (Exception error) {
            // A missing Keystore key means this install cannot prove its old identity; do not
            // silently mint a new one, because peers still hold the old pairing.
            android.util.Log.e("SyncTransport", "Unable to unwrap the sync seed: " + error.getMessage());
            return "";
        }
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        StringBuilder builder = new StringBuilder();
        for (byte item : raw) builder.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        String seed = builder.toString();
        try {
            SeedVault.write(context, seed);
        } catch (Exception error) {
            android.util.Log.e("SyncTransport", "Unable to wrap the sync seed: " + error.getMessage());
            return "";
        }
        return seed;
    }

    static synchronized boolean available() {
        if (!loaded && !failed) {
            try {
                System.loadLibrary("traceweave_sync");
                loaded = true;
            } catch (Throwable error) {
                failed = true;
            }
        }
        return loaded;
    }

    private static final TransportBinding.NonceCache NONCES = new TransportBinding.NonceCache();

    /** An outbound request: the signed envelope plus the nonce the response must acknowledge. */
    static final class Prepared {
        final byte[] envelope;
        final byte[] nonce;

        Prepared(byte[] envelope, byte[] nonce) {
            this.envelope = envelope;
            this.nonce = nonce;
        }
    }

    static byte[] decodeChallenge(String hex) {
        if (hex == null || hex.isEmpty()) return TransportBinding.ZERO;
        byte[] value = new byte[hex.length() / 2];
        for (int index = 0; index < value.length; index++)
            value[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        if (value.length != TransportBinding.CHALLENGE_BYTES) return TransportBinding.ZERO;
        return value;
    }

    static String encodeChallenge(byte[] challenge) {
        StringBuilder result = new StringBuilder(challenge.length * 2);
        for (byte item : challenge) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }

    static Prepared prepareRequest(String localFingerprint, String peerFingerprint, byte[] challenge, byte[] body) {
        if (peerFingerprint == null || !peerFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("pairing_not_p256_bound");
        try {
            byte[] envelope = TransportBinding.request(localFingerprint, peerFingerprint,
                DeviceIdentity.publicKey(), body, challenge, message -> DeviceIdentity.sign(message));
            // The request nonce is inside the envelope; read it back for the response check.
            TransportBinding.Parsed parsed = TransportBinding.parse(envelope);
            return new Prepared(envelope, parsed.nonce);
        } catch (Exception error) {
            throw new IllegalStateException("binding_unavailable", error);
        }
    }

    static TransportBinding.Opened verifyResponse(byte[] envelope, String localFingerprint,
                                                  String peerFingerprint, byte[] requestNonce) {
        if (peerFingerprint == null || !peerFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("pairing_not_p256_bound");
        return TransportBinding.verifyResponse(envelope, peerFingerprint, localFingerprint, requestNonce,
            NONCES, System.currentTimeMillis());
    }

    static Prepared prepareResponse(String localFingerprint, String peerFingerprint, byte[] body,
                                    byte[] challenge, byte[] requestNonce) {
        if (peerFingerprint == null || !peerFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("pairing_not_p256_bound");
        try {
            byte[] envelope = TransportBinding.response(localFingerprint, peerFingerprint,
                DeviceIdentity.publicKey(), body, challenge, requestNonce, message -> DeviceIdentity.sign(message));
            TransportBinding.Parsed parsed = TransportBinding.parse(envelope);
            return new Prepared(envelope, parsed.nonce);
        } catch (Exception error) {
            throw new IllegalStateException("binding_unavailable", error);
        }
    }

    static TransportBinding.Opened verifyRequest(byte[] envelope, String localFingerprint,
                                                 String peerFingerprint) {
        if (peerFingerprint == null || !peerFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("pairing_not_p256_bound");
        return TransportBinding.verifyEnvelope(envelope, peerFingerprint, localFingerprint, NONCES,
            System.currentTimeMillis());
    }

    /** Local Android Keystore fingerprint; the identity the peer paired with. */
    static String localFingerprint() {
        try {
            return DeviceIdentity.fingerprint();
        } catch (Exception error) {
            throw new IllegalStateException("device_identity_unavailable", error);
        }
    }

    static native String nativePeerId(String seedHex);

    static native String nativeServeOnce(String seedHex, int port, String allowedCsv, Handler handler);

    /** Serves one session on an explicit multiaddr so a paired LAN peer can connect. */
    static native String nativeServeAddressOnce(String seedHex, String address, String allowedCsv, Handler handler);

    static native byte[] nativeDialOnce(String seedHex, int port, String expectedPeerHex, byte[] payload);

    /** Dials an explicit multiaddr (LAN peer) instead of loopback. */
    static native byte[] nativeDialAddressOnce(String seedHex, String address, String expectedPeerHex, byte[] payload);

    static native String nativeDiscover(String seedHex, long timeoutMs);

    static native String nativePairServe(String seedHex, int port, String fingerprint, long timeoutMs);

    static native String nativePairDial(String seedHex, String address, String fingerprint, long timeoutMs);
}
