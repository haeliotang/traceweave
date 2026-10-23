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

    // =========================================================================
    // ROUTE 1: FULL RUST STORE & STATE MACHINE PERSISTENCE WRAPPERS
    // =========================================================================

    public static boolean storeOpen(String dbPath) {
        return IS_LOADED && nativeStoreOpen(dbPath);
    }

    public static String storeCreateTask(String title) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeStoreCreateTask(title);
    }

    public static String storeGetTask(String taskId) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeStoreGetTask(taskId);
    }

    public static String storeListTasks() {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeStoreListTasks();
    }

    public static boolean storeTransitionTask(String taskId, String status) {
        return IS_LOADED && nativeStoreTransitionTask(taskId, status);
    }

    public static boolean storeSaveStepCheckpoint(String taskId, int stepIndex, String observation) {
        return IS_LOADED && nativeStoreSaveStepCheckpoint(taskId, stepIndex, observation);
    }

    public static boolean storeCompleteTask(String taskId, String result) {
        return IS_LOADED && nativeStoreCompleteTask(taskId, result);
    }

    public static String storeCreateOrUpdateMemory(String kind, String content, String source, String refId, String topicKey) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeStoreCreateOrUpdateMemory(kind, content, source, refId, topicKey);
    }

    public static String storeListMemories() {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeStoreListMemories();
    }

    public static String storeSelectMemories(String goal, int topK) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeStoreSelectMemories(goal, topK);
    }

    // Native JNI declarations
    public static native boolean nativeIsLoaded();
    public static native String nativeRedact(String text);
    public static native String nativeInfer(String goal, String capabilitiesJson);
    public static native String nativeRankMemories(String goal, String memoriesJson, int topK);

    public static native boolean nativeStoreOpen(String dbPath);
    public static native String nativeStoreCreateTask(String title);
    public static native String nativeStoreGetTask(String taskId);
    public static native String nativeStoreListTasks();
    public static native boolean nativeStoreTransitionTask(String taskId, String status);
    public static native boolean nativeStoreSaveStepCheckpoint(String taskId, int stepIndex, String observation);
    public static native boolean nativeStoreCompleteTask(String taskId, String result);
    public static native String nativeStoreCreateOrUpdateMemory(String kind, String content, String source, String refId, String topicKey);
    public static native String nativeStoreListMemories();
    public static native String nativeStoreSelectMemories(String goal, int topK);
}
