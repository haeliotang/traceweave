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

    // =========================================================================
    // PHASE 8: DIRECTION A (PLUGINS), DIRECTION C (EDGE SLM), DIRECTION D (E2EE SYNC)
    // =========================================================================

    public static String installPlugin(String baseDir, String manifestJson, String jsCode) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeInstallPlugin(baseDir, manifestJson, jsCode);
    }

    public static String listPlugins(String baseDir) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeListPlugins(baseDir);
    }

    public static String invokePluginTool(String baseDir, String pluginId, String toolName, String argsJson) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeInvokePluginTool(baseDir, pluginId, toolName, argsJson);
    }

    public static boolean uninstallPlugin(String baseDir, String pluginId) {
        return IS_LOADED && nativeUninstallPlugin(baseDir, pluginId);
    }

    public static String edgeSlmExtract(String goal, String memoriesJson) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeEdgeSlmExtract(goal, memoriesJson);
    }

    public static String edgeSlmBuildPrompt(String goal) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeEdgeSlmBuildPrompt(goal);
    }

    public static String edgeSlmPlan(String taskId, String goal, String memoriesJson, String capabilitiesJson, long pacingMs) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeEdgeSlmPlan(taskId, goal, memoriesJson, capabilitiesJson, pacingMs);
    }

    public static String edgeSlmSynthesize(String goal, String observationsJson) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeEdgeSlmSynthesize(goal, observationsJson);
    }

    public static String edgeSlmModelInfo(String modelPath) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeEdgeSlmModelInfo(modelPath);
    }

    public static String exportEncryptedSync(String passphrase, String deviceId) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeExportEncryptedSync(passphrase, deviceId);
    }

    public static String importEncryptedSync(String passphrase, String envelopeJson) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeImportEncryptedSync(passphrase, envelopeJson);
    }

    // =========================================================================
    // PHASE 9: ADVANCED ALL-IN-ONE (GGUF STREAMING, SCREEN AGENT, P2P SYNC, VOICE DSP)
    // =========================================================================

    public static String parseGgufHeader(byte[] headerBytes) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeParseGgufHeader(headerBytes);
    }

    public static String generateTokensStreaming(String prompt) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeGenerateTokensStreaming(prompt);
    }

    public static String analyzeScreenHierarchy(String screenNodesJson, String goal) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeAnalyzeScreenHierarchy(screenNodesJson, goal);
    }

    public static String p2pExchange(String host, int port, String localEnvelopeJson) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeP2pExchange(host, port, localEnvelopeJson);
    }

    public static String analyzeVoicePcm(short[] pcmSamples) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeAnalyzeVoicePcm(pcmSamples);
    }

    public static boolean analyzeVoiceDirect(java.nio.ByteBuffer directBuffer, int sampleOffset, int sampleCount, float[] outMetrics) {
        if (!IS_LOADED) return false;
        return nativeAnalyzeVoiceDirect(directBuffer, sampleOffset, sampleCount, outMetrics);
    }

    public static String classifyVoiceIntent(String transcript) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeClassifyVoiceIntent(transcript);
    }

    // =========================================================================
    // PHASE 10: AOSP SYSTEM DAEMON, SENSOR FUSION, VISION OCR, HARDWARE BROKER
    // =========================================================================

    public static String processSensorTelemetry(String telemetryJson) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeProcessSensorTelemetry(telemetryJson);
    }

    public static String analyzeVisualScene(String sceneJson, String query) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeAnalyzeVisualScene(sceneJson, query);
    }

    public static String parseVisualDocument(String ocrJson) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeParseVisualDocument(ocrJson);
    }

    public static String registerPeripheral(String peripheralJson) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeRegisterPeripheral(peripheralJson);
    }

    public static String dispatchHardwareEvent(String eventJson) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeDispatchHardwareEvent(eventJson);
    }

    // =========================================================================
    // PHASE 2: DECOUPLED PERSISTENT VAULT (TWO-TIER KEY LIFECYCLE)
    // =========================================================================

    public static boolean vaultInit(String vaultPath, String passphrase) {
        return IS_LOADED && nativeVaultInit(vaultPath, passphrase);
    }

    public static boolean vaultUnlock(String vaultPath, String passphrase) {
        return IS_LOADED && nativeVaultUnlock(vaultPath, passphrase);
    }

    public static boolean vaultIsUnlocked() {
        return IS_LOADED && nativeVaultIsUnlocked();
    }

    public static byte[] vaultSeal(byte[] aad, byte[] plaintext) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeVaultSeal(aad, plaintext);
    }

    public static byte[] vaultUnseal(byte[] aad, byte[] sealed) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeVaultUnseal(aad, sealed);
    }

    // =========================================================================
    // PHASE 11: TRACEFORM COMPUTE ENGINE (TCE) HETEROGENEOUS GRAPH SCHEDULER
    // =========================================================================

    public static String tceTopology() {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeTceTopology();
    }

    public static String tceExecuteBenchmark(int scenario, float temperatureC, float batteryLevel) {
        if (!IS_LOADED) throw new IllegalStateException("Native core not available");
        return nativeTceExecuteBenchmark(scenario, temperatureC, batteryLevel);
    }

    // =========================================================================
    // OPTION 1 & 2: ZERO-COPY SLM INFERENCE & EMBEDDED IN-PROCESS JS MCP TOOLS
    // =========================================================================

    public static void inferZeroCopy(String goal, String[] memories, float[] outLogits) {
        if (!IS_LOADED) {
            throw new IllegalStateException("Native core not available");
        }
        nativeInferZeroCopy(goal, memories, outLogits);
    }

    public static String executeJsTool(String script, String toolName, String paramsJson) {
        if (!IS_LOADED) {
            throw new IllegalStateException("Native core not available");
        }
        return nativeExecuteJsTool(script, toolName, paramsJson);
    }

    // Native JNI declarations
    public static native boolean nativeIsLoaded();
    public static native String nativeRedact(String text);
    public static native String nativeInfer(String goal, String capabilitiesJson);
    public static native void nativeInferZeroCopy(String goal, String[] memories, float[] outLogits);
    public static native String nativeExecuteJsTool(String script, String toolName, String paramsJson);
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

    public static native String nativeInstallPlugin(String baseDir, String manifestJson, String jsCode);
    public static native String nativeListPlugins(String baseDir);
    public static native String nativeInvokePluginTool(String baseDir, String pluginId, String toolName, String argsJson);
    public static native boolean nativeUninstallPlugin(String baseDir, String pluginId);

    public static native String nativeEdgeSlmExtract(String goal, String memoriesJson);
    public static native String nativeEdgeSlmBuildPrompt(String goal);
    public static native String nativeEdgeSlmPlan(String taskId, String goal, String memoriesJson, String capabilitiesJson, long pacingMs);
    public static native String nativeEdgeSlmSynthesize(String goal, String observationsJson);
    public static native String nativeEdgeSlmModelInfo(String modelPath);

    public static native String nativeExportEncryptedSync(String passphrase, String deviceId);
    public static native String nativeImportEncryptedSync(String passphrase, String envelopeJson);

    public static native String nativeParseGgufHeader(byte[] headerBytes);
    public static native String nativeGenerateTokensStreaming(String prompt);
    public static native String nativeAnalyzeScreenHierarchy(String screenNodesJson, String goal);
    public static native String nativeP2pExchange(String host, int port, String localEnvelopeJson);
    public static native String nativeAnalyzeVoicePcm(short[] pcmSamples);
    public static native boolean nativeAnalyzeVoiceDirect(java.nio.ByteBuffer directBuffer, int sampleOffset, int sampleCount, float[] outMetrics);
    public static native String nativeClassifyVoiceIntent(String transcript);

    public static native String nativeProcessSensorTelemetry(String telemetryJson);
    public static native String nativeAnalyzeVisualScene(String sceneJson, String query);
    public static native String nativeParseVisualDocument(String ocrJson);
    public static native String nativeRegisterPeripheral(String peripheralJson);
    public static native String nativeDispatchHardwareEvent(String eventJson);

    public static native boolean nativeVaultInit(String vaultPath, String passphrase);
    public static native boolean nativeVaultUnlock(String vaultPath, String passphrase);
    public static native boolean nativeVaultIsUnlocked();
    public static native byte[] nativeVaultSeal(byte[] aad, byte[] plaintext);
    public static native byte[] nativeVaultUnseal(byte[] aad, byte[] sealed);

    public static native String nativeTceTopology();
    public static native String nativeTceExecuteBenchmark(int scenario, float temperatureC, float batteryLevel);
}
