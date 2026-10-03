package org.traceform.edge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the observation map the reference extractor reads, from what the native service can
 * actually obtain. Fields it cannot obtain are left out, so the ported code takes the same defaults
 * Python takes when a field is missing. The set of supplied fields is FIELDS; the feature-kind table
 * is generated from it (scripts/generate_reference_feature_kinds.py).
 */
final class ReferenceObservation {
    private ReferenceObservation() {}

    /** Observation paths this builder can supply. Keep in step with scripts/generate_reference_feature_kinds.py. */
    static final String[] FIELDS = {
        "timestamp_utc",
        "motion.accel_magnitude", "motion.accel_rms", "motion.motion_energy", "motion.stationary_likelihood",
        "motion.accel_raw", "motion.gyro_raw", "motion.gyro_energy",
        "environment.ambient_light_lux", "environment.pressure_hpa", "environment.device_temp_c",
        "device_relation.battery_percentage", "device_relation.plugged", "device_relation.status",
        "device_relation.current_ua", "device_relation.voltage_mv", "device_relation.screen_state",
        "device_relation.wired_headset",
        "connectivity.wifi_rssi", "connectivity.wifi_connected",
        "digital_context.screen_on_ratio", "digital_context.app_switch_rate", "digital_context.unlock_burst_count",
        "digital_context.touch_interaction_velocity", "digital_context.touch_interval_jitter",
        "digital_context.attention_fragmentation_entropy", "digital_context.ambient_social_ble_density",
        "digital_context.ambient_rf_shannon_entropy", "digital_context.ble_rssi_spatial_variance"
    };

    /** Android-side inputs that are not feature readings. NaN / null mean "not available". */
    static final class Inputs {
        long wallMillis;
        long nanos;
        String screenState = "UNKNOWN";
        NativeUsageSource.Counts usage;
        double voltageMv = Double.NaN;
        double wifiRssiDbm = Double.NaN;
        Boolean headsetConnected;
    }

    static Map<String, Object> build(FeatureReadings readings, Inputs inputs) {
        long nanos = inputs.nanos;
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("timestamp_utc", Instant.ofEpochMilli(inputs.wallMillis).toString());

        Map<String, Object> motion = new LinkedHashMap<>();
        copy(readings, "motion_accel_magnitude", nanos, motion, "accel_magnitude");
        copy(readings, "motion_accel_rms", nanos, motion, "accel_rms");
        copy(readings, "motion_energy", nanos, motion, "motion_energy");
        copy(readings, "motion_stationary_likelihood", nanos, motion, "stationary_likelihood");
        double[] accel = readings.accelRaw(nanos), gyro = readings.gyroRaw(nanos);
        if (accel != null) motion.put("accel_raw", vector(accel));
        if (gyro != null) {
            motion.put("gyro_raw", vector(gyro));
            motion.put("gyro_energy", PyRuntime.round(gyro[0] * gyro[0] + gyro[1] * gyro[1] + gyro[2] * gyro[2], 6));
        }
        if (!motion.isEmpty()) observation.put("motion", motion);

        Map<String, Object> environment = new LinkedHashMap<>();
        double lux = readings.lightLux(nanos);
        if (Double.isFinite(lux)) environment.put("ambient_light_lux", lux);
        copy(readings, "env_pressure_hpa", nanos, environment, "pressure_hpa");
        copy(readings, "env_device_temp_c", nanos, environment, "device_temp_c");
        if (!environment.isEmpty()) observation.put("environment", environment);

        Map<String, Object> device = new LinkedHashMap<>();
        FeatureReadings.Reading battery = readings.get("device_battery_fraction", nanos);
        if (battery != null) device.put("battery_percentage", Math.rint(battery.value * 100));
        FeatureReadings.Reading plugged = readings.get("device_is_plugged", nanos);
        if (plugged != null) device.put("plugged", plugged.value != 0 ? "USB" : "UNPLUGGED");
        FeatureReadings.Reading charging = readings.get("device_is_charging", nanos);
        if (charging != null) device.put("status", charging.value != 0 ? "CHARGING" : "DISCHARGING");
        copy(readings, "battery_current_ua", nanos, device, "current_ua");
        if (Double.isFinite(inputs.voltageMv)) device.put("voltage_mv", inputs.voltageMv);
        device.put("screen_state", inputs.screenState);
        if (inputs.headsetConnected != null) device.put("wired_headset", inputs.headsetConnected);
        observation.put("device_relation", device);

        Map<String, Object> connectivity = new LinkedHashMap<>();
        FeatureReadings.Reading connected = readings.get("conn_wifi_connected", nanos);
        if (connected != null) connectivity.put("wifi_connected", connected.value != 0);
        if (Double.isFinite(inputs.wifiRssiDbm)) connectivity.put("wifi_rssi", inputs.wifiRssiDbm);
        if (!connectivity.isEmpty()) observation.put("connectivity", connectivity);

        Map<String, Object> digital = inputs.usage == null ? null : inputs.usage.digital(inputs.screenState);
        if (digital != null) observation.put("digital_context", digital);
        return observation;
    }

    private static void copy(FeatureReadings readings, String feature, long nanos, Map<String, Object> into, String key) {
        FeatureReadings.Reading reading = readings.get(feature, nanos);
        if (reading != null) into.put(key, reading.value);
    }

    private static List<Object> vector(double[] values) {
        List<Object> list = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) list.add(values[i]);
        return list;
    }
}
