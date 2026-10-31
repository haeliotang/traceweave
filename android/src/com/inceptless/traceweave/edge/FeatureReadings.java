package com.inceptless.traceweave.edge;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

final class FeatureReadings {
    static final class Reading {
        final double value;
        final String kind, source;
        final long timestampNanos, maxAgeNanos;
        Reading(double value, String kind, String source, long timestampNanos, long maxAgeNanos) {
            this.value = value; this.kind = kind; this.source = source;
            this.timestampNanos = timestampNanos; this.maxAgeNanos = maxAgeNanos;
        }
    }
    private final Map<String, Reading> values = new HashMap<>();
    private double accelMagnitude = Double.NaN, gyroMagnitude = Double.NaN;
    private double[] accelRawVector, gyroRawVector;
    private long accelNanos, gyroNanos;
    private double referencePressure = Double.NaN;
    private long pressureNanos, previousPressureNanos, lastPressureNanos;
    private double previousPressure = Double.NaN;
    private double referenceVoltage = Double.NaN, referenceCurrent = Double.NaN;
    private long electricalNanos;
    private static final class Derivative {
        double[] reference;
        double[][] higher = new double[3][];
        long nanos;
    }
    private static final String[] HIGHER_ACCEL_NAMES = {"kinematic_snap_metric", "kinematic_crackle_metric", "kinematic_pop_metric"};
    private final Derivative accelDerivative = new Derivative(), gyroDerivative = new Derivative();
    private Reading rawLight;
    private long windowNanos, historyAccelNanos;
    private final ArrayDeque<Double> accelWindow = new ArrayDeque<>();
    private final ArrayDeque<Double> lightWindow = new ArrayDeque<>(), pressureWindow = new ArrayDeque<>();
    void reset() {
        values.clear();
        previousPressure = Double.NaN; previousPressureNanos = lastPressureNanos = 0;
        accelMagnitude = gyroMagnitude = referencePressure = referenceVoltage = referenceCurrent = Double.NaN;
        accelRawVector = gyroRawVector = null;
        accelNanos = gyroNanos = pressureNanos = electricalNanos = windowNanos = historyAccelNanos = 0;
        accelDerivative.reference = gyroDerivative.reference = null;
        accelDerivative.higher = new double[3][]; gyroDerivative.higher = new double[3][];
        accelDerivative.nanos = gyroDerivative.nanos = 0;
        rawLight = null;
        accelWindow.clear(); lightWindow.clear(); pressureWindow.clear();
    }
    void light(double lux, long nanos) {
        rawLight = Double.isFinite(lux) && lux >= 0 ? new Reading(lux, "observed", "light_sensor", nanos, 300000000000L) : null;
        put("env_log_lux", rawLight == null ? Double.NaN : Math.log1p(lux), "derived", "light_sensor", nanos, 300000000000L);
    }
    static boolean isDeviceTemperature(String name, String type) {
        return "LSM6DSR Temperature".equals(name) && "com.google.sensor.gyro_temperature".equals(type);
    }
    void deviceTemperature(double celsius, long nanos) {
        put("env_device_temp_c", celsius, "observed",
            "LSM6DSR_imu_chip_temperature_not_battery_ambient_or_whole_device", nanos, 300000000000L);
    }
    void wifiRssi(double dbm, long nanos) {
        double normalized = Double.isFinite(dbm) && dbm >= -126 && dbm <= 0
            ? Math.max(0, Math.min(1, (dbm + 100) / 70)) : Double.NaN;
        put("conn_wifi_rssi_norm", normalized, "derived", "strongest_available_wifi_transport_rssi_dbm_clipped_minus100_to_minus30", nanos, 5000000000L);
    }
    private void push(ArrayDeque<Double> window, double value, int limit) {
        window.addLast(value);
        if (window.size() > limit) window.removeFirst();
    }
    private double mean(ArrayDeque<Double> window) {
        double total = 0;
        for (double value : window) total += value;
        return total / window.size();
    }
    private double moment(ArrayDeque<Double> window, double mean, int power) {
        double total = 0;
        for (double value : window) total += Math.pow(value - mean, power);
        return total / window.size();
    }
    void windows(long nanos) {
        if (nanos == windowNanos) return;
        if (nanos < windowNanos || nanos - windowNanos > 5000000000L) {
            lightWindow.clear(); pressureWindow.clear(); accelWindow.clear(); historyAccelNanos = 0;
        }
        windowNanos = nanos;
        double contrast = Double.NaN, kurtosis = Double.NaN, turbulence = Double.NaN;
        if (rawLight != null && nanos >= rawLight.timestampNanos && nanos - rawLight.timestampNanos <= 5000000000L) {
            push(lightWindow, rawLight.value, 10);
            double mean = mean(lightWindow);
            if (lightWindow.size() >= 2) contrast = Math.rint(Math.min(6, Math.log1p(Math.abs(rawLight.value - mean) / (mean + 1))) * 1000) / 1000;
            if (lightWindow.size() >= 4) {
                double variance = moment(lightWindow, mean, 2);
                kurtosis = variance > 1e-4 ? Math.max(-2, Math.min(10, moment(lightWindow, mean, 4) / (variance * variance) - 3)) : 0;
                kurtosis = Math.rint(kurtosis * 1000) / 1000;
            }
        } else lightWindow.clear();
        Reading pressure = get("env_pressure_hpa", nanos);
        if (pressure != null && nanos - pressure.timestampNanos <= 5000000000L) {
            push(pressureWindow, pressure.value, 6);
            if (pressureWindow.size() >= 2) turbulence = Math.rint(Math.min(10, moment(pressureWindow, mean(pressureWindow), 2)) * 10000) / 10000;
        } else pressureWindow.clear();
        double decay = Double.NaN;
        Reading acceleration = get("motion_accel_magnitude", nanos);
        if (acceleration != null && acceleration.timestampNanos > historyAccelNanos) {
            push(accelWindow, acceleration.value, 25);
            historyAccelNanos = acceleration.timestampNanos;
            if (accelWindow.size() >= 8) {
                double average = mean(accelWindow), denominator = 0, numerator = 0;
                double previous = 0;
                boolean first = true;
                for (double value : accelWindow) {
                    double centered = value - average;
                    denominator += centered * centered;
                    if (!first) numerator += previous * centered;
                    previous = centered; first = false;
                }
                if (denominator > 1e-4) {
                    double rho = Math.max(0.01, Math.min(0.99, Math.abs(numerator / denominator)));
                    decay = Math.rint(Math.max(0.1, Math.min(10, -1 / Math.log(rho))) * 1000) / 1000;
                }
            }
        } else {
            accelWindow.clear();
            if (acceleration == null) historyAccelNanos = 0;
        }
        int[] ordinalCounts = new int[27];
        Double[] magnitudes = accelWindow.toArray(new Double[0]);
        double teager = Double.NaN;
        if (magnitudes.length >= 3) {
            int last = magnitudes.length - 1;
            double middle = magnitudes[last - 1];
            teager = Math.rint(Math.max(0, Math.min(10, middle * middle - magnitudes[last - 2] * magnitudes[last])) * 1000) / 1000;
        }
        put("non_linear_energy_operator_teager", teager, "derived", "last3_fresh_accelerometer_magnitudes_middle_frame_discrete_teager_positive_clipped_0_10_includes_gravity_not_joules", nanos, 5000000000L);
        int validPatterns = 0;
        for (int index = 0; index + 2 < magnitudes.length; index++) {
            double a = magnitudes[index], b = magnitudes[index + 1], c = magnitudes[index + 2];
            if (a == b || a == c || b == c) continue;
            int rankA = (a > b ? 1 : 0) + (a > c ? 1 : 0);
            int rankB = (b > a ? 1 : 0) + (b > c ? 1 : 0);
            int rankC = (c > a ? 1 : 0) + (c > b ? 1 : 0);
            ordinalCounts[rankA * 9 + rankB * 3 + rankC]++;
            validPatterns++;
        }
        double permutationEntropy = Double.NaN;
        if (validPatterns >= 8) {
            double entropy = 0;
            for (int count : ordinalCounts) {
                if (count == 0) continue;
                double probability = count / (double) validPatterns;
                entropy -= probability * Math.log(probability);
            }
            permutationEntropy = Math.rint(entropy / Math.log(6) * 1000) / 1000;
        }
        put("permutation_entropy_complexity", permutationEntropy, "derived", "latest_25_fresh_accelerometer_frames_order3_delay1_normalized_ordinal_entropy_ties_excluded_min8_patterns", nanos, 5000000000L);
        put("imu_temporal_autocorrelation_decay", decay, "derived", "latest_25_fresh_accelerometer_sample_frames_abs_lag1_acf_decay_not_seconds_or_physiological_memory", nanos, 5000000000L);
        put("photometric_contrast_ratio", contrast, "derived", "latest_10_sample_frames_light_contrast_not_optical_flicker", nanos, 5000000000L);
        put("photometric_flicker_kurtosis", kurtosis, "derived", "latest_10_sample_frames_lux_excess_kurtosis_not_frequency_resolved_flicker", nanos, 5000000000L);
        put("barometric_turbulence_variance", turbulence, "derived", "latest_6_sample_frames_pressure_population_variance_not_fluid_turbulence", nanos, 5000000000L);
        Reading velocity = get("barometric_vertical_velocity", nanos);
        double buoyancy = velocity == null || !Double.isFinite(turbulence) ? Double.NaN
                : Math.rint(Math.min(10, 0.981 * Math.abs(velocity.value) * Math.sqrt(turbulence)) * 10000) / 10000;
        put("barometric_buoyancy_energy_flux", buoyancy, "proxy", "pressure_velocity_times_window_deviation_not_measured_buoyancy_energy", nanos, 5000000000L);
    }
    void put(String name, double value, String kind, String source, long timestampNanos, long maxAgeNanos) {
        if (!Double.isFinite(value)) { values.remove(name); return; }
        values.put(name, new Reading(value, kind, source, timestampNanos, maxAgeNanos));
    }
    Reading get(String name, long nowNanos) {
        Reading reading = values.get(name);
        if (reading == null || nowNanos < reading.timestampNanos || nowNanos - reading.timestampNanos > reading.maxAgeNanos) return null;
        return reading;
    }
    private boolean finiteVector(float[] values) {
        return values.length >= 3 && Float.isFinite(values[0]) && Float.isFinite(values[1]) && Float.isFinite(values[2]);
    }
    /** Latest accelerometer vector (m/s^2, gravity included) if it is no older than five seconds, else null. */
    double[] accelRaw(long nowNanos) {
        return accelRawVector != null && nowNanos >= accelNanos && nowNanos - accelNanos <= 5000000000L
            ? accelRawVector.clone() : null;
    }
    /** Latest gyroscope vector (rad/s) if it is no older than five seconds, else null. */
    double[] gyroRaw(long nowNanos) {
        return gyroRawVector != null && nowNanos >= gyroNanos && nowNanos - gyroNanos <= 5000000000L
            ? gyroRawVector.clone() : null;
    }
    /** Latest raw light level in lux when fresh (the log1p feature is derived from it), else NaN. */
    double lightLux(long nowNanos) {
        return rawLight != null && nowNanos >= rawLight.timestampNanos && nowNanos - rawLight.timestampNanos <= 5000000000L
            ? rawLight.value : Double.NaN;
    }
    void gyroscope(float[] values, long timestampNanos) {
        derivative(gyroDerivative, "rotational_angular_jerk", values, timestampNanos, 25);
        double energy = Double.NaN;
        gyroMagnitude = Double.NaN; gyroNanos = timestampNanos;
        gyroRawVector = finiteVector(values) ? new double[]{values[0], values[1], values[2]} : null;
        if (finiteVector(values)) {
            double x = values[0], y = values[1], z = values[2];
            gyroMagnitude = Math.sqrt(x * x + y * y + z * z);
            energy = Math.rint(Math.min(50, 0.5 * (x * x + y * y + z * z)) * 100000) / 100000;
        }
        put("rotational_kinetic_energy", energy, "derived", "gyroscope", timestampNanos, 5000000000L);
    }
    void accelerometer(float[] values, long timestampNanos) {
        derivative(accelDerivative, "kinematic_jerk_metric", values, timestampNanos, 20);
        double tilt = Double.NaN, alignment = Double.NaN;
        accelMagnitude = Double.NaN; accelNanos = timestampNanos;
        accelRawVector = finiteVector(values) ? new double[]{values[0], values[1], values[2]} : null;
        if (finiteVector(values)) {
            double x = values[0], y = values[1], z = values[2];
            double norm = Math.sqrt(x * x + y * y + z * z);
            accelMagnitude = norm;
            if (norm > 0.1) {
                double cosine = Math.max(-1, Math.min(1, z / norm));
                tilt = Math.rint(Math.toDegrees(Math.acos(cosine)) * 100) / 100;
                alignment = Math.rint(cosine * 1000) / 1000;
            }
        }
        put("device_zenith_tilt_angle", tilt, "derived", "accelerometer_including_gravity", timestampNanos, 5000000000L);
        put("device_screen_gravity_alignment", alignment, "derived", "accelerometer_including_gravity", timestampNanos, 5000000000L);
        put("motion_accel_magnitude", Math.rint(accelMagnitude * 10000) / 10000, "derived", "accelerometer_including_gravity", timestampNanos, 5000000000L);
        put("motion_accel_rms", Math.rint(accelMagnitude / Math.sqrt(3) * 10000) / 10000, "derived", "accelerometer_spatial_rms_not_temporal_window", timestampNanos, 5000000000L);
    }
    void motion(long nowNanos) {
        double energy = Double.NaN, stationary = Double.NaN;
        long oldest = Math.min(accelNanos, gyroNanos);
        if (Double.isFinite(accelMagnitude) && Double.isFinite(gyroMagnitude)
                && nowNanos >= Math.max(accelNanos, gyroNanos) && nowNanos - oldest <= 5000000000L
                && Math.abs(accelNanos - gyroNanos) <= 1000000000L) {
            energy = PyRuntime.round(Math.abs(accelMagnitude - 9.80665) + gyroMagnitude, 4);
            // energy has four decimals, so energy / 2 sits exactly on a three-decimal tie for odd last
            // digits; Math.rint(x * 1000) and Python's round() break that tie differently.
            stationary = PyRuntime.round(Math.max(0, Math.min(1, 1 - energy / 2)), 3);
        }
        put("motion_energy", energy, "derived", "accelerometer_and_gyroscope_snapshot", oldest, 5000000000L);
        put("motion_stationary_likelihood", stationary, "proxy", "heuristic_stationarity_score", oldest, 5000000000L);
    }
    void clock(long epochMillis, long timestampNanos) {
        ZonedDateTime utc = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC);
        double fraction = (utc.toLocalTime().toSecondOfDay() + utc.getNano() / 1e9) / 86400;
        double weekly = (utc.getDayOfWeek().getValue() - 1 + fraction) / 7;
        put("circadian_sin_phase", Math.sin(2 * Math.PI * fraction), "derived", "system_clock_utc", timestampNanos, 5000000000L);
        put("circadian_cos_phase", Math.cos(2 * Math.PI * fraction), "derived", "system_clock_utc", timestampNanos, 5000000000L);
        put("infradian_weekly_phase", Math.sin(2 * Math.PI * weekly), "derived", "system_clock_utc", timestampNanos, 5000000000L);
    }
    void magnetic(float[] vector, long timestampNanos) {
        double anomaly = Double.NaN, totalFlux = Double.NaN, dip = Double.NaN, anisotropy = Double.NaN;
        if (finiteVector(vector)) {
            double x = vector[0], y = vector[1], z = vector[2];
            double norm = Math.sqrt(x * x + y * y + z * z);
            anisotropy = Math.rint(Math.min(10, Math.hypot(x, y) / Math.max(0.5, Math.abs(z))) * 1000) / 1000;
            anomaly = Math.rint(Math.abs(norm - 45) * 100) / 100;
            totalFlux = Math.rint(Math.min(100, Math.abs(norm - 50)) * 100) / 100;
            if (norm > 0) dip = Math.rint(Math.toDegrees(Math.atan2(z, Math.hypot(x, y))) * 100) / 100;
        }
        put("geomagnetic_quadrupole_anisotropy_ratio", anisotropy, "derived", "device_coordinate_horizontal_over_vertical_field_with_0.5uT_floor", timestampNanos, 5000000000L);
        put("geomagnetic_flux_divergence", anomaly, "proxy", "magnetic_norm_minus_45uT_not_spatial_divergence", timestampNanos, 5000000000L);
        put("geomagnetic_total_field_flux", totalFlux, "proxy", "magnetic_norm_minus_50uT_reference_anomaly", timestampNanos, 5000000000L);
        put("geomagnetic_field_dip_angle_deg", dip, "derived", "magnetic_vector_device_coordinate_inclination", timestampNanos, 5000000000L);
    }
    void pressure(double hpa, long timestampNanos) {
        if (!Double.isFinite(hpa) || hpa <= 0) {
            referencePressure = previousPressure = Double.NaN;
            pressureNanos = previousPressureNanos = lastPressureNanos = 0;
            put("env_pressure_hpa", Double.NaN, "observed", "pressure_sensor", timestampNanos, 300000000000L);
            put("barometric_vertical_velocity", Double.NaN, "derived", "pressure_derivative", timestampNanos, 5000000000L);
            return;
        }
        put("env_pressure_hpa", hpa, "observed", "pressure_sensor", timestampNanos, 300000000000L);
        if (!Double.isFinite(referencePressure) || timestampNanos <= lastPressureNanos
                || timestampNanos - lastPressureNanos > 600000000000L) {
            referencePressure = hpa; pressureNanos = timestampNanos;
            previousPressure = Double.NaN; previousPressureNanos = 0;
            put("barometric_vertical_velocity", Double.NaN, "derived", "pressure_derivative", timestampNanos, 5000000000L);
        } else {
            double anchorDt = (timestampNanos - pressureNanos) / 1e9;
            double baseline = anchorDt >= 5 ? referencePressure : previousPressure;
            double dt = anchorDt >= 5 ? anchorDt : (timestampNanos - previousPressureNanos) / 1e9;
            if (Double.isFinite(baseline) && dt >= 5 && dt <= 600) {
                double velocity = Math.rint(Math.max(-5, Math.min(5, -8.43 * (hpa - baseline) / dt)) * 1000) / 1000;
                put("barometric_vertical_velocity", velocity, "derived", "pressure_5s_or_longer_derivative", timestampNanos, 5000000000L);
            }
            if (anchorDt >= 5) {
                previousPressure = referencePressure; previousPressureNanos = pressureNanos;
                referencePressure = hpa; pressureNanos = timestampNanos;
            }
        }
        lastPressureNanos = timestampNanos;
    }
    void electrical(double voltageMv, double currentUa, long nanos) {
        double impedance = Double.NaN, heat = Double.NaN;
        double dt = (nanos - electricalNanos) / 1e9;
        if (Double.isFinite(voltageMv) && voltageMv > 0 && Double.isFinite(currentUa)) {
            if (Double.isFinite(referenceVoltage) && dt > 0 && dt <= 5) {
                double deltaCurrent = Math.abs(currentUa - referenceCurrent);
                if (deltaCurrent >= 40000) {
                    impedance = Math.rint(Math.max(50, Math.min(800, Math.abs(voltageMv - referenceVoltage) / deltaCurrent * 1000000)) * 10) / 10;
                    heat = Math.rint(Math.min(5, Math.pow(Math.abs(currentUa) * 1e-6, 2) * impedance * 1e-3) * 100000) / 100000;
                }
            }
            referenceVoltage = voltageMv; referenceCurrent = currentUa;
        } else {
            referenceVoltage = Double.NaN; referenceCurrent = Double.NaN;
        }
        electricalNanos = nanos;
        put("battery_internal_impedance_miliohm", impedance, "proxy", "absolute_voltage_over_current_change_not_identified_internal_resistance", nanos, 5000000000L);
        put("thermodynamic_joule_heat_loss_watts", heat, "proxy", "current_squared_times_delta_estimated_resistance_not_measured_heat", nanos, 5000000000L);
    }
    void batteryStress(double voltageMv, double currentUa, double fraction, long nanos) {
        Reading temperature = get("env_device_temp_c", nanos);
        double stress = Double.NaN;
        long maxAge = 5000000000L;
        if (temperature != null && Double.isFinite(voltageMv) && voltageMv > 0
                && Double.isFinite(currentUa) && Double.isFinite(fraction) && fraction >= 0 && fraction <= 1) {
            double power = voltageMv * 1e-3 * Math.abs(currentUa) * 1e-6;
            double raw = power * (1 + 0.1 * Math.max(0, temperature.value - 25)) * (1 + (1 - fraction) * 0.5);
            if (Double.isFinite(raw)) stress = Math.rint(Math.max(0, Math.min(5, raw)) * 10000) / 10000;
            maxAge = Math.min(maxAge, temperature.maxAgeNanos - (nanos - temperature.timestampNanos));
        }
        put("thermodynamic_battery_stress_index", stress, "proxy",
            "voltage_current_fraction_and_IMU_temperature_heuristic_not_measured_electrochemical_stress", nanos, maxAge);
    }
    private void derivative(Derivative state, String name, float[] vector, long nanos, double cap) {
        if (!finiteVector(vector)) {
            state.reference = null;
            clearHigher(state, nanos);
            put(name, Double.NaN, "derived", "sensor_vector_derivative", nanos, 5000000000L);
            return;
        }
        double[] current = new double[3];
        for (int index = 0; index < 3; index++) current[index] = Math.rint(vector[index] * 10000.0) / 10000;
        double dt = (nanos - state.nanos) / 1e9;
        if (state.reference == null || dt <= 0 || dt > 300) {
            clearHigher(state, nanos);
            put(name, Double.NaN, "derived", "sensor_vector_derivative", nanos, 5000000000L);
            state.reference = current; state.nanos = nanos;
        } else if (dt >= 2) {
            double square = 0;
            double[] jerk = new double[3];
            for (int index = 0; index < 3; index++) {
                jerk[index] = (current[index] - state.reference[index]) / dt;
                square += Math.pow(current[index] - state.reference[index], 2);
            }
            double value = Math.rint(Math.min(cap, Math.sqrt(square) / dt) * 10000) / 10000;
            put(name, value, "derived", "sensor_vector_2s_or_longer_derivative", nanos, 5000000000L);
            if (state == accelDerivative) {
                double[][] next = new double[3][];
                next[0] = jerk;
                double[] derivative = jerk;
                for (int level = 0; level < 3; level++) {
                    double metric = Double.NaN;
                    if (state.higher[level] != null && derivative != null) {
                        double[] higher = new double[3];
                        double norm = 0;
                        for (int axis = 0; axis < 3; axis++) {
                            higher[axis] = (derivative[axis] - state.higher[level][axis]) / dt;
                            norm += higher[axis] * higher[axis];
                        }
                        metric = Math.rint(Math.min(50, Math.sqrt(norm)) * 10000) / 10000;
                        derivative = higher;
                        if (level < 2) next[level + 1] = higher;
                    } else derivative = null;
                    put(HIGHER_ACCEL_NAMES[level], metric, "derived", "successive_accelerometer_2s_or_longer_finite_differences", nanos, 5000000000L);
                }
                state.higher = next;
            }
            state.reference = current; state.nanos = nanos;
        }
    }
    private void clearHigher(Derivative state, long nanos) {
        if (state != accelDerivative) return;
        state.higher = new double[3][];
        for (String name : HIGHER_ACCEL_NAMES) put(name, Double.NaN, "derived", "successive_accelerometer_finite_differences", nanos, 5000000000L);
    }
}
