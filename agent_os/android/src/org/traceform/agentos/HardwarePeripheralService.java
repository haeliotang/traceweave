package org.traceform.agentos;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Direction 4: Peripheral Hardware Linkage & BLE/USB-C HID Service.
 * Manages external smart rings, sensor tags, and Push-To-Talk (PTT) physical button actions.
 */
public final class HardwarePeripheralService {
    private static final String TAG = "HardwarePeripheralService";
    private static volatile HardwarePeripheralService sInstance;

    public interface HardwareEventListener {
        void onPttTriggered(boolean pressed);
        void onTelemetryIngested(String deviceId, String metric, double value);
    }

    private volatile HardwareEventListener mListener;

    public static HardwarePeripheralService getInstance() {
        if (sInstance == null) {
            synchronized (HardwarePeripheralService.class) {
                if (sInstance == null) {
                    sInstance = new HardwarePeripheralService();
                }
            }
        }
        return sInstance;
    }

    private HardwarePeripheralService() {}

    public void setHardwareEventListener(HardwareEventListener listener) {
        mListener = listener;
    }

    /**
     * Registers a peripheral device in the native hardware broker.
     */
    public JSONObject registerPeripheral(String id, String name, String transport, String[] capabilities) {
        JSONObject dev = new JSONObject();
        try {
            dev.put("id", id);
            dev.put("name", name);
            dev.put("transport", transport);
            dev.put("connected", true);
            dev.put("battery_percent", 95);
            JSONArray caps = new JSONArray();
            if (capabilities != null) {
                for (String c : capabilities) caps.put(c);
            }
            dev.put("capabilities", caps);

            if (NativeAgentCore.isAvailable()) {
                String resp = NativeAgentCore.registerPeripheral(dev.toString());
                return new JSONObject(resp);
            }
        } catch (Exception e) {
            Log.e(TAG, "registerPeripheral failed", e);
        }

        JSONObject fb = new JSONObject();
        try {
            fb.put("status", "registered");
            fb.put("device_id", id);
        } catch (Exception ignored) {}
        return fb;
    }

    /**
     * Dispatches a hardware event (PTT button or telemetry frame).
     */
    public JSONObject dispatchEvent(String deviceId, String eventType, JSONObject payload) {
        JSONObject event = new JSONObject();
        try {
            event.put("device_id", deviceId);
            event.put("event_type", eventType);
            event.put("payload", payload != null ? payload : new JSONObject());
            event.put("timestamp_ms", System.currentTimeMillis());

            JSONObject result;
            if (NativeAgentCore.isAvailable()) {
                String resp = NativeAgentCore.dispatchHardwareEvent(event.toString());
                result = new JSONObject(resp);
            } else {
                result = new JSONObject();
                result.put("status", "success");
                result.put("action", "ptt_pressed".equals(eventType) ? "VOICE_LISTEN_START" : "NOOP");
                result.put("intercepted", true);
            }

            // Callback notification
            if (mListener != null) {
                if ("ptt_pressed".equals(eventType) || "ptt_down".equals(eventType)) {
                    mListener.onPttTriggered(true);
                } else if ("ptt_released".equals(eventType) || "ptt_up".equals(eventType)) {
                    mListener.onPttTriggered(false);
                } else if ("telemetry_frame".equals(eventType) && payload != null) {
                    mListener.onTelemetryIngested(deviceId, payload.optString("metric"), payload.optDouble("value"));
                }
            }

            return result;
        } catch (Exception e) {
            Log.e(TAG, "dispatchEvent failed", e);
            JSONObject err = new JSONObject();
            try {
                err.put("status", "error");
                err.put("message", e.getMessage());
            } catch (Exception ignored) {}
            return err;
        }
    }
}
