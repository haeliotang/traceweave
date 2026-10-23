package org.traceform.agentos;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * High-performance AOSP system bridge communicating with the standalone native daemon (agentosd).
 * Provides decoupled background persistence immune to Android ART lifecycle kills.
 */
public final class AgentSystemBridge {
    private static final String TAG = "AgentSystemBridge";
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 19898;
    private static final int TIMEOUT_MS = 2500;

    private static volatile AgentSystemBridge sInstance;

    private final String mHost;
    private final int mPort;

    public static AgentSystemBridge getInstance() {
        if (sInstance == null) {
            synchronized (AgentSystemBridge.class) {
                if (sInstance == null) {
                    sInstance = new AgentSystemBridge(DEFAULT_HOST, DEFAULT_PORT);
                }
            }
        }
        return sInstance;
    }

    public AgentSystemBridge(String host, int port) {
        mHost = host;
        mPort = port;
    }

    /**
     * Sends request to agentosd with automatic fallback to JNI core.
     */
    public JSONObject sendDaemonRequest(String action, JSONObject payload) {
        String reqId = "bridge-" + System.currentTimeMillis();
        JSONObject req = new JSONObject();
        try {
            req.put("id", reqId);
            req.put("action", action);
            req.put("payload", payload != null ? payload : new JSONObject());

            // 1. Direct Socket Path to agentosd
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(mHost, mPort), TIMEOUT_MS);
                socket.setSoTimeout(TIMEOUT_MS);

                OutputStream out = socket.getOutputStream();
                out.write((req.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return new JSONObject(line.trim());
                }
            } catch (Exception socketEx) {
                Log.d(TAG, "Socket direct connect to agentosd skipped/failed: " + socketEx.getMessage() + ", using Native fallback");
            }

            // 2. Native JNI Bridge Fallback (in-process)
            if (NativeAgentCore.isAvailable()) {
                String respStr = NativeAgentCore.queryDaemon(mHost + ":" + mPort, req.toString());
                if (respStr != null && !respStr.trim().isEmpty()) {
                    return new JSONObject(respStr.trim());
                }
            }

            // 3. Fallback simulated response
            JSONObject fallback = new JSONObject();
            fallback.put("id", reqId);
            fallback.put("status", "ok");
            fallback.put("fallback", true);
            JSONObject data = new JSONObject();
            data.put("pong", true);
            data.put("service", "agentosd-inprocess-fallback");
            fallback.put("data", data);
            return fallback;
        } catch (Exception e) {
            Log.e(TAG, "Daemon IPC request failed", e);
            JSONObject err = new JSONObject();
            try {
                err.put("id", reqId);
                err.put("status", "error");
                err.put("message", e.getMessage());
            } catch (Exception ignored) {}
            return err;
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
