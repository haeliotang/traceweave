// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.graphics.Bitmap;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Direction 3: Multimodal Vision & Edge OCR Screen Layout Analyzer.
 * Extracts interactive elements, grounds user intent into physical touch coordinates,
 * and executes autonomous UI actions across arbitrary Android applications.
 */
public final class VisionAgentManager {
    private static final String TAG = "VisionAgentManager";
    private static volatile VisionAgentManager sInstance;

    public interface VisionTargetCallback {
        void onTargetLocated(JSONObject targetResult);
        void onError(String message);
    }

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
     * Analyzes visual scene elements to locate coordinates for user intent via Rust Core vision engine.
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

        // Fallback heuristic matcher
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
     * Dumps the live on-screen view hierarchy and uses the Rust vision engine
     * to locate the exact physical touch coordinates matching the user's intent.
     */
    public JSONObject locateTargetOnScreen(String query) {
        AgentAccessibilityService a11y = AgentAccessibilityService.getInstance();
        if (a11y == null) {
            JSONObject err = new JSONObject();
            try {
                err.put("matched", false);
                err.put("reasoning", "AccessibilityService 未运行或未启用无障碍权限");
            } catch (Exception ignored) {}
            return err;
        }

        String hierarchyJson = a11y.dumpScreenHierarchyJson();
        try {
            JSONArray nodes = new JSONArray(hierarchyJson);
            Log.i(TAG, "Screen hierarchy returned " + nodes.length() + " nodes");
            JSONObject scene = new JSONObject();
            scene.put("screen_width", 1080);
            scene.put("screen_height", 2400);

            JSONArray elements = new JSONArray();
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                String text = node.optString("text", null);
                if (text == null || text.trim().isEmpty() || "null".equals(text)) {
                    text = node.optString("content_description", null);
                }
                if (text == null || text.trim().isEmpty() || "null".equals(text)) {
                    continue;
                }

                JSONArray b = node.optJSONArray("bounds");
                if (b == null || b.length() < 4) continue;
                int left = b.optInt(0);
                int top = b.optInt(1);
                int right = b.optInt(2);
                int bottom = b.optInt(3);
                int w = right - left;
                int h = bottom - top;
                if (w <= 0 || h <= 0) continue;

                JSONObject elem = new JSONObject();
                elem.put("id", node.optString("id", "elem_" + i));
                elem.put("text", text);
                JSONArray vb = new JSONArray();
                vb.put(left);
                vb.put(top);
                vb.put(w);
                vb.put(h);
                elem.put("bounds", vb);

                boolean isEdit = node.optBoolean("is_editable", false);
                boolean isClick = node.optBoolean("is_clickable", false);
                String cls = node.optString("class_name", "");
                if (isEdit) {
                    elem.put("element_type", "input");
                } else if (isClick || cls.contains("Button")) {
                    elem.put("element_type", "button");
                } else {
                    elem.put("element_type", "text");
                }

                elements.put(elem);
            }
            scene.put("elements", elements);

            return locateTarget(scene, query);
        } catch (Exception e) {
            Log.e(TAG, "Failed to analyze screen for query: " + query, e);
            JSONObject err = new JSONObject();
            try {
                err.put("matched", false);
                err.put("reasoning", "屏幕视觉层级解析异常: " + e.getMessage());
            } catch (Exception ignored) {}
            return err;
        }
    }

    /**
     * Locates a UI target on the live screen and programmatically executes a physical touch tap.
     */
    public boolean clickTargetOnScreen(String query) {
        JSONObject target = locateTargetOnScreen(query);
        if (target.optBoolean("matched", false)) {
            int tapX = (int) target.optDouble("tap_x", 0.0);
            int tapY = (int) target.optDouble("tap_y", 0.0);
            AgentAccessibilityService a11y = AgentAccessibilityService.getInstance();
            if (a11y != null && tapX > 0 && tapY > 0) {
                Log.i(TAG, "Dispatching programmatic click to target (" + tapX + ", " + tapY + ") for query: " + query);
                return a11y.performClickGesture(tapX, tapY);
            }
        }
        return false;
    }

    /**
     * Captures screen bitmap silently via AccessibilityService without screen share prompt dialogs.
     */
    public void captureScreenBitmap(AgentAccessibilityService.ScreenCaptureCallback callback) {
        AgentAccessibilityService a11y = AgentAccessibilityService.getInstance();
        if (a11y == null) {
            if (callback != null) callback.onError(-1);
            return;
        }
        a11y.takeScreenBitmap(callback);
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
