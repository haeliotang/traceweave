package com.inceptless.traceweave;

/** JNI bridge for the sync transport; the app disables sync when the library is absent. */
final class SyncTransport {
    interface Handler {
        byte[] handle(byte[] request);
    }

    private static boolean loaded;
    private static boolean failed;

    private SyncTransport() {}

    /** Device sync seed; the same hex string the UI derives. */
    static String seed(android.content.Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("agent_os_prefs", 0);
        String seed = prefs.getString("sync_seed", "");
        if (!seed.isEmpty()) return seed;
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        StringBuilder builder = new StringBuilder();
        for (byte item : raw) builder.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        seed = builder.toString();
        prefs.edit().putString("sync_seed", seed).apply();
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

    static native String nativePeerId(String seedHex);

    static native String nativeServeOnce(String seedHex, int port, String allowedCsv, Handler handler);

    static native byte[] nativeDialOnce(String seedHex, int port, String expectedPeerHex, byte[] payload);

    /** Dials an explicit multiaddr (LAN peer) instead of loopback. */
    static native byte[] nativeDialAddressOnce(String seedHex, String address, String expectedPeerHex, byte[] payload);

    static native String nativeDiscover(String seedHex, long timeoutMs);

    static native String nativePairServe(String seedHex, int port, String fingerprint, long timeoutMs);

    static native String nativePairDial(String seedHex, String address, String fingerprint, long timeoutMs);
}
