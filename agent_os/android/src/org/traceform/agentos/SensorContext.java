package org.traceform.agentos;

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
            : (en ? "Waiting / unavailable" : "等待数据 / 不可用");
    }
    static String describe(JSONObject value, boolean en) {
        if (!value.optBoolean("monitoring")) return en ? "Sensor monitoring off\nTap Enable sensors to start" : "传感器采集已关闭\n点击“启用感知”后开始";
        String motion = value.optString("motion_state", "Unknown");
        String pose = en ? "Waiting / unavailable" : "等待数据 / 不可用";
        if ("LowMotion".equals(motion)) pose = en ? "Low motion (rule estimate)" : "低运动（规则估计）";
        else if ("MotionDetected".equals(motion)) pose = en ? "Motion detected (rule estimate)" : "检测到运动（规则估计）";
        else if (!"Unknown".equals(motion)) pose = (en ? "Native estimate: " : "原生估计：") + motion;
        String light = (en ? "Light: " : "光照：") + reading(value, "light_lux", "Lux", en);
        if (has(value, "light_age_ms")) light += (en ? " · age " : " · ") + value.optLong("light_age_ms") / 1000 + (en ? "s" : " 秒前");
        return (en ? "Motion: " : "运动：") + pose + "\n" + light + "\n"
            + (en ? "Battery temp: " : "电池温度：") + reading(value, "temperature_c", "°C", en);
    }
}
