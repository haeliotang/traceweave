package org.traceform.agentos;

import android.util.Log;

public final class NativeAgentCore {
    private static final String TAG = "NativeAgentCore";
    private static final boolean IS_LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("agent_os_core");
            loaded = nativeIsLoaded();
            Log.i(TAG, "Native agent_os_core loaded successfully: nativeIsLoaded=" + loaded);
        } catch (Throwable t) {
            Log.w(TAG, "Native agent_os_core not loaded, using Java fallback: " + t.getMessage());
        }
        IS_LOADED = loaded;
    }

    private NativeAgentCore() {}

    public static boolean isAvailable() {
        return IS_LOADED;
    }

    public static String redact(String text) {
        if (!IS_LOADED) {
            throw new IllegalStateException("Native core not available");
        }
        return nativeRedact(text);
    }

    public static String infer(String goal, String capabilitiesJson) {
        if (!IS_LOADED) {
            throw new IllegalStateException("Native core not available");
        }
        return nativeInfer(goal, capabilitiesJson);
    }

    public static String rankMemories(String goal, String memoriesJson, int topK) {
        if (!IS_LOADED) {
            throw new IllegalStateException("Native core not available");
        }
        return nativeRankMemories(goal, memoriesJson, topK);
    }

    // Native JNI declarations
    public static native boolean nativeIsLoaded();
    public static native String nativeRedact(String text);
    public static native String nativeInfer(String goal, String capabilitiesJson);
    public static native String nativeRankMemories(String goal, String memoriesJson, int topK);
}
