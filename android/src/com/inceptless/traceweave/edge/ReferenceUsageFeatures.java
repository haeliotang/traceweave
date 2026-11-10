// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.util.List;
import java.util.Map;

/**
 * Statement-by-statement port of the usage- and touch-derived outputs of
 * MultimodalNormalizer.extract_raw_features (traceform/representation/normalizer.py).
 * Missing fields take the same defaults as Python; see tests/test_reference_features_usage.py.
 *
 * Stateful output: touch_engagement_decay_factor keeps _last_touch_ts, and the "timestamp moved"
 * test it shares with Python's persistent-state update keeps its own copy of _last_ts_seconds
 * (normalizer.py lines 2036-2039). reset() clears exactly what MultimodalNormalizer.reset_state()
 * clears for them (lines 313 and 322).
 *
 * Java's Math.log/cos/exp and the log2 helper below can differ from CPython's libm in the last bit;
 * every such value passes through round(x, n) or a threshold comparison, so a difference only
 * shows up on an exact decimal tie (tests/parity_support.compare tolerates one rounding unit on a
 * tiny fraction of values).
 */
final class ReferenceUsageFeatures {
    static final String[] NAMES = {
        "touch_interaction_velocity", "touch_interval_jitter", "attention_fragmentation_entropy",
        "compute_power_dissipation_watts", "touch_engagement_decay_factor",
        "neuromotor_microslip_angular_velocity", "circadian_phase_disruption_index",
        "touch_contact_ellipse_eccentricity", "neuromotor_fitts_law_throughput",
        "touch_biomotor_cadence_decay_slope", "touch_path_tortuosity_ratio",
        "cpu_frequency_scaling_entropy", "kernel_context_switch_velocity",
        "display_frame_pacing_jank_variance", "io_wait_latency_proxy"
    };

    // self._last_touch_ts: Optional[float]
    private boolean hasLastTouchTs;
    private double lastTouchTs;
    // self._last_ts_seconds: Optional[float]
    private boolean hasLastTsSeconds;
    private double lastTsSeconds;

    void reset() {
        hasLastTsSeconds = false;
        lastTsSeconds = 0.0;
        hasLastTouchTs = false;
        lastTouchTs = 0.0;
    }

    /** Python's `a or b`: the first operand when it is truthy, otherwise the second. */
    private static Object or(Object first, Object second) {
        return PyRuntime.truthy(first) ? first : second;
    }

    /** math.log2 for a positive finite double: exact for powers of two, ln(x)/ln(2) otherwise. */
    private static double log2(double value) {
        if (value > 0.0 && !Double.isInfinite(value)) {
            int exponent = Math.getExponent(value);
            if (exponent >= Double.MIN_EXPONENT && value == Math.scalb(1.0, exponent)) return exponent;
        }
        return Math.log(value) / Math.log(2.0);
    }

    double[] extract(Map<String, Object> obs) {
        // 1. Motion
        Map<String, Object> mot = PyRuntime.dict(obs, "motion");
        double motEnergy = PyRuntime.safeFloat(mot.get("motion_energy"), 0.0);
        double statLike = PyRuntime.safeFloat(mot.get("stationary_likelihood"), 1.0);

        // 2. Environment
        Map<String, Object> env = PyRuntime.dict(obs, "environment");
        double luxVal = PyRuntime.safeFloat(env.get("ambient_light_lux"), 0.0);
        double logLux = Math.log(PyRuntime.max(0.0, luxVal) + 1.0);

        // 3. Device relation
        Map<String, Object> dev = PyRuntime.dict(obs, "device_relation");

        // 6. Digital & cognitive features
        Map<String, Object> dig = PyRuntime.dict(obs, "digital_context");
        double screenRatio = PyRuntime.safeFloat(dig.get("screen_on_ratio"), 0.0);
        if (!dig.containsKey("screen_on_ratio")) {
            Object screenState = PyRuntime.getOr(dev, "screen_state", "UNKNOWN");
            if ("ON".equals(screenState)) screenRatio = 1.0;
            else if ("DOZING".equals(screenState)) screenRatio = 0.2;
            else screenRatio = 0.0;
        }
        double appSwitchRate = PyRuntime.safeFloat(dig.get("app_switch_rate"), 0.0);
        double unlockBurstCount = PyRuntime.safeFloat(dig.get("unlock_burst_count"), 0.0);

        // Touch interaction velocity
        double touchVelocity = PyRuntime.safeFloat(dig.get("touch_interaction_velocity"), 0.0);
        if (!dig.containsKey("touch_interaction_velocity")) {
            if (screenRatio > 0.0) {
                touchVelocity = PyRuntime.round(
                    PyRuntime.min(10.0, 1.0 + appSwitchRate * 0.4 + unlockBurstCount * 0.3), 3);
            } else {
                touchVelocity = 0.0;
            }
        }

        // 7. Audio immersion state
        double isImmersed =
            (PyRuntime.truthy(dev.get("wired_headset")) || PyRuntime.truthy(dev.get("bluetooth_a2dp"))) ? 1.0 : 0.0;

        // 8. Circadian temporal phase (only cos_phase and the timestamp are needed here)
        double cosPhase = 1.0;
        boolean hasCurrentTs = false;
        double currentTs = 0.0;
        PyRuntime.Stamp stamp = PyRuntime.parseTimestamp(obs.get("timestamp_utc"));
        if (stamp.valid) {
            double dailyFraction = stamp.secondsInDay / 86400.0;
            cosPhase = Math.cos(2.0 * Math.PI * dailyFraction);
            hasCurrentTs = true;
            currentTs = stamp.epochSeconds;
        }

        // 17. Touch motor interval jitter
        double touchIntervalJitter;
        Object jitterDirect = dig.get("touch_interval_jitter");
        if (jitterDirect != null) {
            touchIntervalJitter = PyRuntime.safeFloat(jitterDirect, 0.0);
        } else {
            if (screenRatio > 0.0) {
                touchIntervalJitter = PyRuntime.round(PyRuntime.min(2.0,
                    (appSwitchRate * 0.25 + unlockBurstCount * 0.2) / (1.0 + touchVelocity * 0.2)), 3);
            } else {
                touchIntervalJitter = 0.0;
            }
        }

        // 18. Multitask attention fragmentation entropy
        double attentionFragEntropy;
        Object attentionDirect = dig.get("attention_fragmentation_entropy");
        if (attentionDirect != null) {
            attentionFragEntropy = PyRuntime.safeFloat(attentionDirect, 0.0);
        } else {
            if (appSwitchRate > 1.0) {
                attentionFragEntropy = PyRuntime.round(PyRuntime.min(4.0, log2(PyRuntime.min(16.0, appSwitchRate))), 3);
            } else {
                attentionFragEntropy = 0.0;
            }
        }

        // 19. Estimated SoC computational power dissipation
        double computePowerW;
        Object computePowerDirect = dev.get("compute_power_w");
        if (computePowerDirect != null) {
            computePowerW = PyRuntime.safeFloat(computePowerDirect, 0.5);
        } else {
            double baseIdle = 0.35;
            double screenPwr = 0.85 * screenRatio;
            double audioPwr = 0.25 * isImmersed;
            double switchPwr = 0.20 * PyRuntime.min(1.0, appSwitchRate / 8.0);
            computePowerW = PyRuntime.round(
                PyRuntime.min(15.0, PyRuntime.max(0.1, baseIdle + screenPwr + audioPwr + switchPwr)), 3);
        }

        // gyro_raw is shared by the microslip fallback below
        Object gyroRaw = mot.get("gyro_raw");

        // 35. Continuous touch engagement decay factor
        double touchEngagement;
        boolean hasTouchInteraction = touchVelocity > 0.0 || appSwitchRate > 0.0 || unlockBurstCount > 0.0;
        if (hasTouchInteraction) {
            if (hasCurrentTs && (!hasLastTsSeconds || currentTs != lastTsSeconds)) {
                hasLastTouchTs = true;
                lastTouchTs = currentTs;
            }
            touchEngagement = 1.0;
        } else {
            if (hasLastTouchTs && hasCurrentTs && currentTs >= lastTouchTs) {
                double idleSec = currentTs - lastTouchTs;
                touchEngagement = PyRuntime.round(Math.exp(-idleSec / 600.0), 3);
            } else {
                touchEngagement = screenRatio > 0.5 ? 0.5 : 0.0;
            }
        }

        // 53. Neuromotor touch micro-slip rotational angular velocity
        double microslipVel;
        Object slipDirect = or(dig.get("neuromotor_microslip_angular_velocity"), mot.get("microslip_ang_vel"));
        if (slipDirect != null) {
            microslipVel = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(10.0, PyRuntime.safeFloat(slipDirect, 0.05))), 3);
        } else {
            if (PyRuntime.truthy(gyroRaw) && gyroRaw instanceof List && ((List<?>) gyroRaw).size() >= 3) {
                List<?> gyro = (List<?>) gyroRaw;
                double gx = PyRuntime.safeFloat(gyro.get(0), 0.0);
                double gy = PyRuntime.safeFloat(gyro.get(1), 0.0);
                double gz = PyRuntime.safeFloat(gyro.get(2), 0.0);
                double normGyro = Math.sqrt(gx * gx + gy * gy + gz * gz);
                double rawSlip;
                if (hasTouchInteraction || touchVelocity > 0.0) {
                    rawSlip = normGyro * PyRuntime.min(1.0, 0.4 + touchVelocity * 0.3);
                } else {
                    rawSlip = 0.05 * normGyro;
                }
                microslipVel = PyRuntime.round(PyRuntime.min(10.0, PyRuntime.max(0.0, rawSlip)), 3);
            } else {
                microslipVel = 0.0;
            }
        }

        // 54. Circadian phase disruption / chrono-entropy index
        double circadianDisruption;
        Object chronoDirect = or(dig.get("circadian_phase_disruption_index"), dig.get("chrono_entropy"));
        if (chronoDirect != null) {
            circadianDisruption = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(chronoDirect, 0.05))), 3);
        } else {
            double disruptVal;
            boolean isNight = cosPhase > 0.6;
            if (isNight) {
                disruptVal = 0.45 * screenRatio
                    + 0.35 * PyRuntime.min(1.0, logLux / 2.5)
                    + 0.20 * PyRuntime.min(1.0, motEnergy / 0.4);
            } else {
                boolean isMidday = cosPhase < -0.5;
                if (isMidday && logLux <= 0.1 && statLike >= 0.9 && screenRatio <= 0.05) {
                    disruptVal = 0.5 * statLike;
                } else {
                    disruptVal = 0.02;
                }
            }
            circadianDisruption = PyRuntime.round(PyRuntime.min(1.0, PyRuntime.max(0.0, disruptVal)), 3);
        }

        // 85. Touch contact ellipse eccentricity
        double touchEccentricity;
        Object eccDirect = or(dig.get("touch_contact_ellipse_eccentricity"), dig.get("touch_eccentricity"));
        if (eccDirect != null) {
            touchEccentricity = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(eccDirect, 0.5))), 3);
        } else {
            touchEccentricity = PyRuntime.round(
                PyRuntime.min(0.95, PyRuntime.max(0.1, 0.35 + touchVelocity * 0.4)), 3);
        }

        // 87. Neuromotor Fitts' law throughput
        double fittsThroughput;
        Object fittsDirect = or(dig.get("neuromotor_fitts_law_throughput"), dig.get("fitts_throughput"));
        if (fittsDirect != null) {
            fittsThroughput = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(15.0, PyRuntime.safeFloat(fittsDirect, 4.5))), 2);
        } else {
            fittsThroughput = PyRuntime.round(PyRuntime.min(12.0,
                PyRuntime.max(0.5, 4.5 + touchVelocity * 3.0 - touchIntervalJitter * 2.0)), 2);
        }

        // 88. Biomotor cadence decay slope
        double cadenceDecaySlope;
        Object cadenceDirect = dig.get("touch_biomotor_cadence_decay_slope");
        if (cadenceDirect != null) {
            cadenceDecaySlope = PyRuntime.round(
                PyRuntime.max(-2.0, PyRuntime.min(2.0, PyRuntime.safeFloat(cadenceDirect, 0.0))), 3);
        } else {
            cadenceDecaySlope = PyRuntime.round(
                PyRuntime.max(-1.5, PyRuntime.min(1.5, -0.05 * appSwitchRate)), 3);
        }

        // 90. Touch gesture path tortuosity ratio
        double touchTortuosity;
        Object tortuosityDirect = dig.get("touch_path_tortuosity_ratio");
        if (tortuosityDirect != null) {
            touchTortuosity = PyRuntime.round(
                PyRuntime.max(1.0, PyRuntime.min(5.0, PyRuntime.safeFloat(tortuosityDirect, 1.1))), 3);
        } else {
            touchTortuosity = PyRuntime.round(
                PyRuntime.min(4.0, PyRuntime.max(1.0, 1.05 + 0.3 * touchVelocity)), 3);
        }

        // 91. CPU cluster frequency scaling entropy
        double cpuFreqEntropy;
        Object cpuEntDirect = dig.get("cpu_frequency_scaling_entropy");
        if (cpuEntDirect != null) {
            cpuFreqEntropy = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(3.0, PyRuntime.safeFloat(cpuEntDirect, 1.2))), 3);
        } else {
            cpuFreqEntropy = PyRuntime.round(PyRuntime.min(2.5,
                PyRuntime.max(0.2, 0.8 + computePowerW * 0.4 + appSwitchRate * 0.15)), 3);
        }

        // 92. Kernel context switch velocity
        double contextSwitchVel;
        Object cswDirect = dig.get("kernel_context_switch_velocity");
        if (cswDirect != null) {
            contextSwitchVel = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(50.0, PyRuntime.safeFloat(cswDirect, 12.0))), 2);
        } else {
            contextSwitchVel = PyRuntime.round(PyRuntime.min(45.0,
                PyRuntime.max(2.0, 8.0 + appSwitchRate * 6.0 + computePowerW * 4.0)), 2);
        }

        // 94. SurfaceFlinger frame rendering jank variance
        double jankVariance;
        Object jankDirect = or(dig.get("display_frame_pacing_jank_variance"), dig.get("frame_jank_variance"));
        if (jankDirect != null) {
            jankVariance = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(10.0, PyRuntime.safeFloat(jankDirect, 0.5))), 3);
        } else {
            jankVariance = PyRuntime.round(
                PyRuntime.min(8.0, PyRuntime.max(0.05, 0.2 + appSwitchRate * 0.6)), 3);
        }

        // 95. Storage I/O wait latency pressure
        double iowaitPressure;
        Object iowaitDirect = dig.get("io_wait_latency_proxy");
        if (iowaitDirect != null) {
            iowaitPressure = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(iowaitDirect, 0.03))), 3);
        } else {
            iowaitPressure = PyRuntime.round(
                PyRuntime.min(0.7, PyRuntime.max(0.01, 0.02 + computePowerW * 0.05)), 3);
        }

        // Persistent state update (the part of normalizer.py lines 2036-2039 that concerns _last_ts_seconds)
        if (hasCurrentTs && (!hasLastTsSeconds || currentTs != lastTsSeconds)) {
            hasLastTsSeconds = true;
            lastTsSeconds = currentTs;
        }

        return new double[]{
            touchVelocity, touchIntervalJitter, attentionFragEntropy, computePowerW, touchEngagement,
            microslipVel, circadianDisruption, touchEccentricity, fittsThroughput, cadenceDecaySlope,
            touchTortuosity, cpuFreqEntropy, contextSwitchVel, jankVariance, iowaitPressure
        };
    }
}
