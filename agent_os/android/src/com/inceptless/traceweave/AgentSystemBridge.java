// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Compatibility facade for the retired standalone daemon transport.
 */
public final class AgentSystemBridge {
    private static final String TAG = "AgentSystemBridge";

    private static volatile AgentSystemBridge sInstance;

    public static AgentSystemBridge getInstance() {
        if (sInstance == null) {
            synchronized (AgentSystemBridge.class) {
                if (sInstance == null) {
                    sInstance = new AgentSystemBridge();
                }
            }
        }
        return sInstance;
    }

    private AgentSystemBridge() {}

    /**
     * Fails closed because the Android MVP has no standalone daemon transport.
     */
    public JSONObject sendDaemonRequest(String action, JSONObject payload) {
        JSONObject unavailable = new JSONObject();
        try {
            unavailable.put("id", "bridge-" + System.currentTimeMillis());
            unavailable.put("status", "error");
            unavailable.put("message", "Standalone agentosd transport is disabled in the Android Binder-only MVP");
            return unavailable;
        } catch (Exception e) {
            Log.e(TAG, "Unable to build disabled-daemon response", e);
            return unavailable;
        }
    }

    public JSONObject ping() {
        return sendDaemonRequest("ping", new JSONObject());
    }

    public JSONObject getStatus() {
        return sendDaemonRequest("status", new JSONObject());
    }

    public JSONObject evaluateTask(String taskId, String goal) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("task_id", taskId);
            payload.put("goal", goal);
            return sendDaemonRequest("eval_task", payload);
        } catch (Exception e) {
            Log.e(TAG, "evaluateTask error", e);
            return new JSONObject();
        }
    }

    public JSONObject syncMemories(JSONArray memories) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("memories", memories);
            return sendDaemonRequest("memory_sync", payload);
        } catch (Exception e) {
            Log.e(TAG, "syncMemories error", e);
            return new JSONObject();
        }
    }
}
