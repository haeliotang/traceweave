package org.traceform.agentos;

/** Thin JNI bridge used only by isolated validation builds. */
final class SyncProbe {
    static {
        System.loadLibrary("traceform_sync");
    }

    private SyncProbe() {}

    static native String nativeLoopback(int port);

    static native byte[] nativeTranscript(byte[] deviceId, byte[] peerId, byte[] nonce);

    static native boolean nativeVerifyP256(byte[] message, byte[] signature, byte[] publicKey);
}
