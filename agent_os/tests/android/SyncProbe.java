package com.inceptless.traceweave;

/** Thin JNI bridge used only by isolated validation builds. */
final class SyncProbe {
    interface Handler {
        byte[] handle(byte[] request);
    }

    static {
        System.loadLibrary("traceform_sync");
    }

    private SyncProbe() {}

    static native String nativeLoopback(int port);

    static native byte[] nativeTranscript(byte[] deviceId, byte[] peerId, byte[] nonce);

    static native boolean nativeVerifyP256(byte[] message, byte[] signature, byte[] publicKey);

    static native String nativePeerId(String seedHex);

    static native String nativePublicKey(String seedHex);

    static native String nativeServeOnce(String seedHex, int port, String allowedCsv, Handler handler);

    static native byte[] nativeDialOnce(String seedHex, int port, String expectedPeerHex, byte[] payload);
}
