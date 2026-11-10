// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import org.json.JSONObject;
import java.util.Locale;

/** Display measured values separately from conservative, local rule estimates. */
final class SensorContext {
    private SensorContext() {}

    static JSONObject fallback(JSONObject readings, boolean monitoring) throws Exception {
        JSONObject result = new JSONObject(readings.toString());
        result.put("monitoring", monitoring).put("engine", "java_sensor_rules");
        boolean imu = has(readings, "accel_x") && has(readings, "gyro_x");
        String motion = "Unknown";
        if (monitoring && imu) {
            double a = magnitude(readings, "accel"), g = magnitude(readings, "gyro");
            motion = Math.abs(a - 9.81) < .65 && g < .15 ? "LowMotion" : "MotionDetected";
        }
        boolean coveredKnown = monitoring && has(readings, "light_lux") && has(readings, "proximity_cm");
        boolean covered = coveredKnown && readings.getDouble("light_lux") < 5 && readings.getDouble("proximity_cm") < 1;
        result.put("motion_state", motion).put("ambient_environment", has(readings, "light_lux") ? "MeasuredLight" : "Unknown")
            .put("in_pocket", coveredKnown ? covered : JSONObject.NULL).put("voice_wake_allowed", !covered);
        return result;
    }

    private static double magnitude(JSONObject value, String prefix) throws Exception {
        double x = value.getDouble(prefix + "_x"), y = value.getDouble(prefix + "_y"), z = value.getDouble(prefix + "_z");
        return Math.sqrt(x*x + y*y + z*z);
    }
    private static boolean has(JSONObject value, String key) { return value.has(key) && !value.isNull(key); }
    private static String reading(JSONObject value, String key, String unit, boolean en) {
        return has(value, key) ? String.format(Locale.US, "%.1f %s", value.optDouble(key), unit)
            : (T.s("sensorcontext.waiting_unavailable", en));
    }
    static String describe(JSONObject value, boolean en) {
        if (!value.optBoolean("monitoring")) return T.s("sensorcontext.sensor_monitoring_off_ntap_enable_sens", en);
        String motion = value.optString("motion_state", "Unknown");
        String pose = T.s("sensorcontext.waiting_unavailable", en);
        if ("LowMotion".equals(motion)) pose = T.s("sensorcontext.low_motion_window_estimate", en);
        else if ("MotionDetected".equals(motion)) pose = T.s("sensorcontext.motion_detected_window_estimate", en);
        else if (!"Unknown".equals(motion)) pose = (T.s("sensorcontext.native_estimate", en)) + motion;
        String light = (T.s("sensorcontext.light", en)) + reading(value, "light_lux", "Lux", en);
        if (has(value, "light_age_ms")) light += (T.s("sensorcontext.age", en)) + value.optLong("light_age_ms") / 1000 + (T.s("sensorcontext.s", en));
        JSONObject change = value.optJSONObject("motion_change");
        String event = change == null ? "" : (T.s("sensorcontext.nmotion_change_detected", en))
            + Math.max(0, (System.currentTimeMillis() - change.optLong("observedAt")) / 1000) + (T.s("sensorcontext.s_ago", en));
        return (T.s("sensorcontext.motion", en)) + pose + "\n" + light + "\n"
            + (T.s("sensorcontext.battery_temp", en)) + reading(value, "temperature_c", "°C", en)
            + (T.s("sensorcontext.nsource_accelerometer_gyroscope", en)) + event;
    }
}
