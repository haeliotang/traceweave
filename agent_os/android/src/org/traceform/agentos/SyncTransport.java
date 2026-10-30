package org.traceform.agentos;

/** JNI bridge for the sync transport; the app disables sync when the library is absent. */
final class SyncTransport {
    interface Handler {
        byte[] handle(byte[] request);
    }

    private static boolean loaded;
    private static boolean failed;

    private SyncTransport() {}

    static synchronized boolean available() {
        if (!loaded && !failed) {
            try {
                System.loadLibrary("traceform_sync");
                loaded = true;
            } catch (Throwable error) {
                failed = true;
            }
        }
        return loaded;
    }

    static native String nativePeerId(String seedHex);

    static native String nativeServeOnce(String seedHex, int port, String allowedCsv, Handler handler);

    static native byte[] nativeDialOnce(String seedHex, int port, String expectedPeerHex, byte[] payload);
}
