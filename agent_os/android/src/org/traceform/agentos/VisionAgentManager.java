package org.traceform.agentos;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Direction 3: Multimodal Vision & Edge OCR Screen Layout Analyzer.
 * Extracts interactive elements and physical document structure beyond Accessibility tree limits.
 */
public final class VisionAgentManager {
    private static final String TAG = "VisionAgentManager";
    private static volatile VisionAgentManager sInstance;

    public static VisionAgentManager getInstance() {
        if (sInstance == null) {
            synchronized (VisionAgentManager.class) {
                if (sInstance == null) {
                    sInstance = new VisionAgentManager();
                }
            }
        }
        return sInstance;
    }

    private VisionAgentManager() {}

    /**
     * Analyzes visual scene elements to locate coordinates for user intent.
     */
    public JSONObject locateTarget(JSONObject sceneJson, String query) {
        if (NativeAgentCore.isAvailable()) {
            try {
                String resp = NativeAgentCore.analyzeVisualScene(sceneJson.toString(), query);
                return new JSONObject(resp);
            } catch (Exception e) {
                Log.e(TAG, "Native analyzeVisualScene failed", e);
            }
        }

        // Fallback
        JSONObject fb = new JSONObject();
        try {
            fb.put("matched", false);
            fb.put("action_type", "none");
            fb.put("tap_x", 0.0);
            fb.put("tap_y", 0.0);
            fb.put("reasoning", "Native core unavailable for visual scene analysis");
        } catch (Exception ignored) {}
        return fb;
    }

    /**
     * Parses physical camera / document OCR tokens into structured invoice/receipt memory.
     */
    public JSONObject parseDocument(JSONArray ocrTokens) {
        if (NativeAgentCore.isAvailable()) {
            try {
                String resp = NativeAgentCore.parseVisualDocument(ocrTokens.toString());
                return new JSONObject(resp);
            } catch (Exception e) {
                Log.e(TAG, "Native parseVisualDocument failed", e);
            }
        }

        JSONObject fb = new JSONObject();
        try {
            fb.put("doc_type", "unparsed");
            fb.put("merchant_or_title", "Unknown");
            fb.put("suggested_task", "Archive captured document");
            fb.put("memory_representation", "Document tokens ingested without native analyzer");
        } catch (Exception ignored) {}
        return fb;
    }
}
