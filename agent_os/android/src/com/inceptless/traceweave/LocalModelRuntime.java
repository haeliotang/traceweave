package com.inceptless.traceweave;

/** Optional native runtime; model output never authorizes an external action. */
final class LocalModelRuntime {
    private static final boolean AVAILABLE;
    static {
        boolean loaded;
        try { System.loadLibrary("traceweave_local_model"); loaded = true; }
        catch (UnsatisfiedLinkError error) { loaded = false; }
        AVAILABLE = loaded;
    }

    private LocalModelRuntime() {}
    static boolean isAvailable() { return AVAILABLE; }
    static native void prepare();
    static native void cancel();
    static native byte[] generate(String path, byte[] prompt, int maxTokens, String grammar);
}
