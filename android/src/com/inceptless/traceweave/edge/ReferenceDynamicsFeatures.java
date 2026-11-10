// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Statement-by-statement port of the "group P" outputs of
 * MultimodalNormalizer.extract_raw_features (traceform/representation/normalizer.py): thermal
 * conduction, state residence, recurrence/RQA, kinematic curvature and torsion, phase-space
 * velocity, tap recoil, Lyapunov/Kaplan-Yorke dynamics and the composite coherence index.
 * They share one state core (the previous-frame memory below), so they are ported together.
 *
 * The class is independent of the other ported groups: it keeps its own copy of every preamble
 * value and state field it needs. Python state that no output of this group reads (the pressure,
 * voltage and current memories, the touch timestamp, snap and crackle history) is deliberately not
 * kept; output parity cannot observe it.
 *
 * Python-specific behaviour that matters here:
 *  - sum() over floats is compensated (Neumaier) since Python 3.12, which differs from naive
 *    summation in the last bit about a third of the time; see {@link #pySum}.
 *  - "a or b" yields b whenever a is falsy, including 0, "" and None; see {@link #firstTruthy}.
 *  - "current_ts_seconds != self._last_ts_seconds" is true when either side is None; see
 *    {@link #newInstant}.
 */
final class ReferenceDynamicsFeatures {
    static final String[] NAMES = {
        "thermal_conduction_rate_dt", "state_residence_persistence_hours", "thermal_flux_acceleration_dt2",
        "phase_space_recurrence_density", "kinematic_trajectory_curvature", "phase_space_kinetic_velocity",
        "neuromotor_tap_recoil_intensity", "kinematic_trajectory_torsion", "thermodynamic_dissipation_ratio",
        "neuromotor_tremor_lyapunov_exponent", "phase_space_information_flow_rate",
        "touch_downforce_pressure_derivative", "rqa_recurrence_determinism_det",
        "rqa_laminarity_vertical_trapping", "lempel_ziv_algorithmic_complexity", "fisher_information_measure",
        "phase_space_trapping_time_seconds", "bifurcation_stability_margin", "phase_space_angular_momentum",
        "dissipative_lyapunov_dimension_ky", "holo_cosmic_coherence_index"
    };

    /** One entry of Python's self._recent_coords deque: (timestamp or 0.0, five-dimensional summary). */
    private static final class Coord {
        final double timestamp;
        final double[] summary;
        Coord(double timestamp, double[] summary) { this.timestamp = timestamp; this.summary = summary; }
    }

    // Previous-frame memory (None in Python == null here). Mirrors the self._... attributes.
    private Double lastTsSeconds;
    private Double lastTemp;
    private double[] lastAccelRaw;
    private String residenceRegime;
    private Double residenceStartTs;
    private double[] lastGyroRaw;
    private Double lastConductionRate;
    private final List<Coord> recentCoords = new ArrayList<>();   // deque(maxlen=20)
    private double[] lastVecSummary;
    private Double lastVecTs;
    private Double lastAz;
    private double[] lastJerkRaw;
    private Double lastTremorEnergy;

    /** Python reset_state() for the attributes this group owns. */
    void reset() {
        lastTsSeconds = null;
        lastTemp = null;
        lastAccelRaw = null;
        residenceRegime = null;
        residenceStartTs = null;
        lastGyroRaw = null;
        lastConductionRate = null;
        recentCoords.clear();
        lastVecSummary = null;
        lastVecTs = null;
        lastAz = null;
        lastJerkRaw = null;
        lastTremorEnergy = null;
    }

    double[] extract(Map<String, Object> obs) {
        // 1. Motion
        Map<String, Object> mot = PyRuntime.dict(obs, "motion");
        double accelMag = PyRuntime.safeFloat(mot.get("accel_magnitude"), 9.8);
        double motEnergy = PyRuntime.safeFloat(mot.get("motion_energy"), 0.0);
        double statLike = PyRuntime.safeFloat(mot.get("stationary_likelihood"), 1.0);

        // 2. Environment
        Map<String, Object> env = PyRuntime.dict(obs, "environment");
        double luxVal = PyRuntime.safeFloat(env.get("ambient_light_lux"), 0.0);
        double logLux = Math.log(PyRuntime.max(0.0, luxVal) + 1.0);
        double devTemp = PyRuntime.safeFloat(env.get("device_temp_c"), 30.0);

        // 3. Device relation
        Map<String, Object> dev = PyRuntime.dict(obs, "device_relation");
        Object batRaw = dev.get("battery_percentage");
        double batteryFrac = batRaw != null ? (PyRuntime.safeFloat(batRaw, 50.0) / 100.0) : 0.5;
        // status_str = str(dev.get("status", "DISCHARGING")); only the string "CHARGING" stringifies to it.
        double isCharging = "CHARGING".equals(dev.get("status")) ? 1.0 : 0.0;
        double batteryCurrentUa = PyRuntime.safeFloat(dev.get("current_ua"), 0.0);

        // 4. Audio context (rms_loudness is not read by any output of this group)
        Map<String, Object> aud = PyRuntime.dict(obs, "audio_context");
        double silenceLike;
        double spectralEntropy;
        if ("NOT_SAMPLED".equals(aud.get("status"))) {
            silenceLike = 1.0;
            spectralEntropy = 0.0;
        } else {
            silenceLike = PyRuntime.safeFloat(aud.get("silence_likelihood"), 1.0);
            spectralEntropy = PyRuntime.safeFloat(aud.get("spectral_entropy"), 0.0);
        }

        // 6. Digital context
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
        double isImmersed = (PyRuntime.truthy(dev.get("wired_headset"))
            || PyRuntime.truthy(dev.get("bluetooth_a2dp"))) ? 1.0 : 0.0;

        // 8. Circadian phase. An absent, empty or unparsable timestamp leaves cos_phase = 1.0 and
        // current_ts_seconds = None.
        double cosPhase = 1.0;
        Double currentTs = null;
        PyRuntime.Stamp stamp = PyRuntime.parseTimestamp(obs.get("timestamp_utc"));
        if (stamp.valid) {
            double dailyFraction = stamp.secondsInDay / 86400.0;
            cosPhase = Math.cos(2.0 * Math.PI * dailyFraction);
            currentTs = stamp.epochSeconds;
        }

        // 10. Physiological micro-tremor
        Object physioDirect = mot.get("ballisto_energy");
        double physioTremor;
        if (physioDirect != null) {
            physioTremor = PyRuntime.safeFloat(physioDirect, 0.0);
        } else {
            double gyroEnergy = PyRuntime.safeFloat(mot.get("gyro_energy"), 0.0);
            if (statLike >= 0.8) {
                physioTremor = PyRuntime.round(
                    PyRuntime.min(1.0, motEnergy * statLike * PyRuntime.max(0.0, 1.0 - 50.0 * gyroEnergy)), 4);
            } else {
                physioTremor = 0.0;
            }
        }

        // 11. Net thermal conduction gradient rate in deg C / hour
        double conductionRate = 0.0;
        if (lastTemp != null && lastTsSeconds != null && currentTs != null) {
            double dtS = currentTs - lastTsSeconds;
            if (5.0 <= dtS && dtS <= 3600.0) {
                double rawRate = ((devTemp - lastTemp) / dtS) * 3600.0;
                double internalBias = (isCharging > 0.0 ? 1.5 : 0.0) + (screenRatio > 0.0 ? 0.8 : 0.0);
                conductionRate = PyRuntime.round(
                    PyRuntime.max(-10.0, PyRuntime.min(10.0, rawRate - internalBias)), 3);
            }
        }

        // 14.. Compute power, used by the dissipation ratio (section 19)
        Object compPowerDirect = dev.get("compute_power_w");
        double computePowerW;
        if (compPowerDirect != null) {
            computePowerW = PyRuntime.safeFloat(compPowerDirect, 0.5);
        } else {
            double baseIdle = 0.35;
            double screenPwr = 0.85 * screenRatio;
            double audioPwr = 0.25 * isImmersed;
            double switchPwr = 0.20 * PyRuntime.min(1.0, appSwitchRate / 8.0);
            computePowerW = PyRuntime.round(
                PyRuntime.min(15.0, PyRuntime.max(0.1, baseIdle + screenPwr + audioPwr + switchPwr)), 3);
        }
        double voltageMv = PyRuntime.safeFloat(dev.get("voltage_mv"), 4000.0);

        // 18. Attention fragmentation entropy (feeds memory_psi)
        Object attentionEntDirect = dig.get("attention_fragmentation_entropy");
        double attentionFragEntropy;
        if (attentionEntDirect != null) {
            attentionFragEntropy = PyRuntime.safeFloat(attentionEntDirect, 0.0);
        } else {
            if (appSwitchRate > 1.0) {
                attentionFragEntropy = PyRuntime.round(
                    PyRuntime.min(4.0, log2(PyRuntime.min(16.0, appSwitchRate))), 3);
            } else {
                attentionFragEntropy = 0.0;
            }
        }

        // 22. Kinematic jerk magnitude in m/s^3
        double jerkMetric = 0.0;
        Object accelRaw = mot.get("accel_raw");
        boolean accelUsable = usableVector(accelRaw);
        if (accelUsable) {
            List<?> raw = (List<?>) accelRaw;
            double[] currentAccel = {
                PyRuntime.safeFloat(raw.get(0), 0.0),
                PyRuntime.safeFloat(raw.get(1), 0.0),
                PyRuntime.safeFloat(raw.get(2), 9.8),
            };
            if (lastAccelRaw != null && lastTsSeconds != null && currentTs != null) {
                double dtS = currentTs - lastTsSeconds;
                if (2.0 <= dtS && dtS <= 300.0) {
                    double dAx = currentAccel[0] - lastAccelRaw[0];
                    double dAy = currentAccel[1] - lastAccelRaw[1];
                    double dAz = currentAccel[2] - lastAccelRaw[2];
                    double jerkCalc = Math.sqrt(dAx * dAx + dAy * dAy + dAz * dAz) / dtS;
                    jerkMetric = PyRuntime.round(PyRuntime.min(20.0, jerkCalc), 4);
                }
            }
        }

        // 27. Current macro-state residence persistence in hours
        String currentRegime = statLike >= 0.8 ? "STATIONARY" : "DYNAMIC";
        double residencePersistenceHours;
        if (currentTs != null) {
            if (currentRegime.equals(residenceRegime) && residenceStartTs != null) {
                double dwellSec = PyRuntime.max(0.0, currentTs - residenceStartTs);
                residencePersistenceHours = PyRuntime.round(PyRuntime.min(24.0, dwellSec / 3600.0), 3);
            } else {
                if (newInstant(currentTs)) {
                    residenceRegime = currentRegime;
                    residenceStartTs = currentTs;
                }
                residencePersistenceHours = 0.0;
            }
        } else {
            residencePersistenceHours = 0.0;
        }

        // 30. Rotational angular jerk in rad/s^2 (also maintains the gyro memory inline)
        double rotJerk = 0.0;
        Object gyroRaw = mot.get("gyro_raw");
        if (usableVector(gyroRaw)) {
            List<?> raw = (List<?>) gyroRaw;
            double[] currentGyro = {
                PyRuntime.safeFloat(raw.get(0), 0.0),
                PyRuntime.safeFloat(raw.get(1), 0.0),
                PyRuntime.safeFloat(raw.get(2), 0.0),
            };
            if (lastGyroRaw != null && lastTsSeconds != null && currentTs != null) {
                double dtS = currentTs - lastTsSeconds;
                if (2.0 <= dtS && dtS <= 300.0) {
                    double dGx = currentGyro[0] - lastGyroRaw[0];
                    double dGy = currentGyro[1] - lastGyroRaw[1];
                    double dGz = currentGyro[2] - lastGyroRaw[2];
                    double jerkGCalc = Math.sqrt(dGx * dGx + dGy * dGy + dGz * dGz) / dtS;
                    rotJerk = PyRuntime.round(PyRuntime.min(25.0, jerkGCalc), 4);
                }
            }
            if (newInstant(currentTs)) {
                lastGyroRaw = currentGyro;
            }
        } else {
            if (newInstant(currentTs)) {
                lastGyroRaw = null;
            }
        }

        // 33. Second-order thermal conduction acceleration (also maintains the conduction memory inline)
        double thermalAccelDt2 = 0.0;
        if (lastConductionRate != null && lastTsSeconds != null && currentTs != null) {
            double dtS = currentTs - lastTsSeconds;
            if (2.0 <= dtS && dtS <= 300.0) {
                double dRate = conductionRate - lastConductionRate;
                thermalAccelDt2 = PyRuntime.round(PyRuntime.max(-2.0, PyRuntime.min(2.0, dRate / dtS)), 5);
            }
        }
        if (newInstant(currentTs)) {
            if (lastTemp != null) {
                lastConductionRate = conductionRate;
            } else {
                lastConductionRate = null;
            }
        }

        // 36. Rolling phase-space recurrence density
        double[] coordSummary = {
            PyRuntime.round(accelMag, 2),
            PyRuntime.round(devTemp, 1),
            PyRuntime.round(batteryFrac, 2),
            PyRuntime.round(logLux, 2),
            PyRuntime.round(statLike, 2),
        };
        double tEntry = currentTs != null ? currentTs : 0.0;
        if (!recentCoords.isEmpty() && recentCoords.get(recentCoords.size() - 1).timestamp == tEntry) {
            recentCoords.set(recentCoords.size() - 1, new Coord(tEntry, coordSummary));
        } else {
            recentCoords.add(new Coord(tEntry, coordSummary));
            if (recentCoords.size() > 20) recentCoords.remove(0);   // deque(maxlen=20) evicts the oldest
        }

        int recCount = 0;
        for (Coord previous : recentCoords) {
            if (squaredDistance(coordSummary, previous.summary) <= 4.0) {
                recCount++;
            }
        }
        double recurrenceDensity = PyRuntime.round((double) recCount / (double) recentCoords.size(), 3);

        // 38. Kinematic 3D trajectory Frenet curvature and torsion
        double trajectoryCurvature = 0.0;
        double trajectoryTorsion = 0.0;
        double[] currentJerk = null;

        Object torsionDirect = firstTruthy(mot.get("trajectory_torsion"), mot.get("kinematic_trajectory_torsion"));
        if (torsionDirect != null) {
            trajectoryTorsion = PyRuntime.round(
                PyRuntime.max(-10.0, PyRuntime.min(10.0, PyRuntime.safeFloat(torsionDirect, 0.0))), 4);
        }

        if (accelUsable) {
            List<?> raw = (List<?>) accelRaw;
            double[] currA = {
                PyRuntime.safeFloat(raw.get(0), 0.0),
                PyRuntime.safeFloat(raw.get(1), 0.0),
                PyRuntime.safeFloat(raw.get(2), 9.8),
            };
            if (lastAccelRaw != null && lastTsSeconds != null && currentTs != null) {
                double dtS = currentTs - lastTsSeconds;
                if (2.0 <= dtS && dtS <= 300.0) {
                    double dAx = (currA[0] - lastAccelRaw[0]) / dtS;
                    double dAy = (currA[1] - lastAccelRaw[1]) / dtS;
                    double dAz = (currA[2] - lastAccelRaw[2]) / dtS;
                    currentJerk = new double[]{dAx, dAy, dAz};
                    double cx = currA[1] * dAz - currA[2] * dAy;
                    double cy = currA[2] * dAx - currA[0] * dAz;
                    double cz = currA[0] * dAy - currA[1] * dAx;
                    double crossMag = Math.sqrt(cx * cx + cy * cy + cz * cz);
                    // Python: (a0 ** 2 + a1 ** 2 + a2 ** 2) ** 1.5
                    double aMagCubed = Math.pow(currA[0] * currA[0] + currA[1] * currA[1] + currA[2] * currA[2], 1.5);
                    trajectoryCurvature = PyRuntime.round(
                        PyRuntime.min(5.0, crossMag / PyRuntime.max(0.1, aMagCubed)), 4);

                    if (lastJerkRaw != null) {
                        double sX = (dAx - lastJerkRaw[0]) / dtS;
                        double sY = (dAy - lastJerkRaw[1]) / dtS;
                        double sZ = (dAz - lastJerkRaw[2]) / dtS;
                        // (snap and crackle derived from sX..sZ only feed other groups' outputs)
                        if (torsionDirect == null) {
                            double dotCS = cx * sX + cy * sY + cz * sZ;
                            double crossSq = crossMag * crossMag;
                            if (crossSq > 0.001) {
                                double rawTorsion = dotCS / crossSq;
                                trajectoryTorsion = PyRuntime.round(
                                    PyRuntime.max(-10.0, PyRuntime.min(10.0, rawTorsion)), 4);
                            }
                        }
                    }
                }
            }
        }

        // 44. Multi-modal phase-space kinetic migration velocity
        double phaseVelocity = 0.0;
        double[] summary5d = {accelMag, devTemp, batteryFrac, logLux, statLike};
        if (lastVecSummary != null && lastVecTs != null && currentTs != null) {
            double dtS = currentTs - lastVecTs;
            if (2.0 <= dtS && dtS <= 300.0) {
                double distSum = Math.sqrt(squaredDistance(summary5d, lastVecSummary));
                phaseVelocity = PyRuntime.round(PyRuntime.min(5.0, distSum / dtS), 4);
            }
        }
        if (newInstant(currentTs)) {
            lastVecSummary = summary5d;
            lastVecTs = currentTs;   // may be None (null) when this frame has no valid timestamp
        }

        // 45. Neuromotor touch tap recoil deceleration intensity
        double tapRecoil = 0.0;
        if (accelUsable) {
            double azRawVal = PyRuntime.safeFloat(((List<?>) accelRaw).get(2), 9.8);
            if (lastAz != null) {
                double dAz = Math.abs(azRawVal - lastAz);
                tapRecoil = PyRuntime.round(PyRuntime.min(8.0, divide(dAz, 1.0 + touchVelocity * 0.5)), 3);
            }
            if (newInstant(currentTs)) {
                lastAz = azRawVal;
            }
        } else {
            lastAz = null;
        }

        // 47. Phase-space Grassberger-Procaccia correlation dimension estimate
        Object corrDimDirect = firstTruthy(mot.get("phase_space_correlation_dimension"),
            dig.get("correlation_dimension"));
        double corrDimension;
        if (corrDimDirect != null) {
            corrDimension = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(5.0, PyRuntime.safeFloat(corrDimDirect, 1.2))), 3);
        } else {
            if (recentCoords.size() >= 6) {
                int n1 = 0;
                int n2 = 0;
                for (int i = 0; i < recentCoords.size(); i++) {
                    for (int j = 0; j < recentCoords.size(); j++) {
                        if (i < j) {
                            double d = Math.sqrt(squaredDistance(recentCoords.get(i).summary,
                                recentCoords.get(j).summary));
                            if (d < 1.0) n1++;
                            if (d < 3.0) n2++;
                        }
                    }
                }
                if (n1 > 0 && n2 > n1) {
                    double rawD2 = Math.log((double) n2 / (double) n1) / Math.log(3.0);
                    corrDimension = PyRuntime.round(PyRuntime.min(5.0, PyRuntime.max(0.0, rawD2)), 3);
                } else {
                    corrDimension = statLike >= 0.8
                        ? 0.15
                        : PyRuntime.round(PyRuntime.min(4.0, 1.2 + 1.8 * PyRuntime.min(1.0, motEnergy)), 3);
                }
            } else {
                corrDimension = statLike >= 0.8
                    ? 0.15
                    : PyRuntime.round(PyRuntime.min(4.0, 1.2 + 1.8 * PyRuntime.min(1.0, motEnergy)), 3);
            }
        }

        // 52. Thermodynamic thermal-to-electrical dissipation ratio
        Object dissipDirect = firstTruthy(dig.get("thermodynamic_dissipation_ratio"), env.get("dissipation_ratio"));
        double thermoDissipationRatio;
        if (dissipDirect != null) {
            thermoDissipationRatio = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(5.0, PyRuntime.safeFloat(dissipDirect, 0.5))), 4);
        } else {
            double pElec = (voltageMv * 1e-3) * (Math.abs(batteryCurrentUa) * 1e-6);
            double pThermal = 80.0 * (PyRuntime.max(0.0, conductionRate) / 3600.0);
            double ratioCalc = pThermal / PyRuntime.max(0.05, pElec + computePowerW);
            thermoDissipationRatio = PyRuntime.round(PyRuntime.min(5.0, PyRuntime.max(0.0, ratioCalc)), 4);
        }

        // 54. Circadian phase disruption / chrono-entropy index (feeds the coherence index)
        Object chronoDirect = firstTruthy(dig.get("circadian_phase_disruption_index"), dig.get("chrono_entropy"));
        double circadianDisruption;
        if (chronoDirect != null) {
            circadianDisruption = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(chronoDirect, 0.05))), 3);
        } else {
            boolean isNight = cosPhase > 0.6;
            double disruptVal;
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

        // 61. Neuromotor physiological micro-tremor Lyapunov exponent
        Object lyapDirect = firstTruthy(dig.get("neuromotor_tremor_lyapunov_exponent"),
            mot.get("tremor_lyapunov_exponent"));
        double tremorLyapunov;
        if (lyapDirect != null) {
            tremorLyapunov = PyRuntime.round(
                PyRuntime.max(-2.0, PyRuntime.min(5.0, PyRuntime.safeFloat(lyapDirect, -0.2))), 4);
        } else {
            tremorLyapunov = 0.0;
            if (lastTremorEnergy != null && lastTsSeconds != null && currentTs != null) {
                double dtS = currentTs - lastTsSeconds;
                if (2.0 <= dtS && dtS <= 300.0) {
                    double rTremor = PyRuntime.max(0.001, physioTremor) / PyRuntime.max(0.001, lastTremorEnergy);
                    tremorLyapunov = PyRuntime.round(
                        PyRuntime.max(-2.0, PyRuntime.min(5.0, Math.log(rTremor) / dtS)), 4);
                }
            }
            if (tremorLyapunov == 0.0) {
                tremorLyapunov = statLike >= 0.8
                    ? -0.25
                    : PyRuntime.round(PyRuntime.min(3.0, 0.15 + 0.35 * PyRuntime.min(1.0, motEnergy)), 4);
            }
        }

        // 62. Multimodal phase-space non-equilibrium information flow rate
        Object flowDirect = firstTruthy(mot.get("phase_space_information_flow_rate"),
            dig.get("information_flow_rate"));
        double informationFlow;
        if (flowDirect != null) {
            informationFlow = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(5.0, PyRuntime.safeFloat(flowDirect, 0.1))), 4);
        } else {
            double rawFlow = 0.5 * phaseVelocity
                + 0.2 * (Math.abs(conductionRate) / 30.0)
                + 0.3 * (jerkMetric * 0.1);
            informationFlow = PyRuntime.round(PyRuntime.min(5.0, PyRuntime.max(0.0, rawFlow)), 4);
        }

        // 68. Acoustic spatial phase coherence (feeds the coherence index)
        Object phaseCohDirect = aud.get("spatial_coherence_phase");
        double spatialCoherence;
        if (phaseCohDirect != null) {
            spatialCoherence = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(phaseCohDirect, 0.7))), 3);
        } else {
            spatialCoherence = PyRuntime.round(
                PyRuntime.min(1.0,
                    PyRuntime.max(0.05, 0.90 - 0.4 * spectralEntropy - 0.2 * (1.0 - silenceLike))), 3);
        }

        // 86. Downforce pressure derivative
        Object dpDirect = dig.get("touch_downforce_pressure_derivative");
        double touchDownforceDeriv;
        if (dpDirect != null) {
            touchDownforceDeriv = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(10.0, PyRuntime.safeFloat(dpDirect, 0.5))), 3);
        } else {
            touchDownforceDeriv = PyRuntime.round(PyRuntime.min(8.0, PyRuntime.max(0.0, tapRecoil * 1.2)), 3);
        }

        // 93. Memory pressure stall ratio (PSI) (feeds the coherence index)
        Object psiDirect = firstTruthy(dig.get("memory_pressure_stall_ratio"), dig.get("memory_psi"));
        double memoryPsi;
        if (psiDirect != null) {
            memoryPsi = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(psiDirect, 0.02))), 4);
        } else {
            memoryPsi = PyRuntime.round(
                PyRuntime.min(0.8, PyRuntime.max(0.001, 0.01 + attentionFragEntropy * 0.04)), 4);
        }

        // 99. RQA recurrence determinism (DET)
        Object detDirect = mot.get("rqa_recurrence_determinism_det");
        double rqaDeterminism;
        if (detDirect != null) {
            rqaDeterminism = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(detDirect, 0.8))), 3);
        } else {
            rqaDeterminism = PyRuntime.round(
                PyRuntime.min(0.99, PyRuntime.max(0.05, recurrenceDensity * 0.8 + 0.15)), 3);
        }

        // 100. RQA laminarity trapping ratio (LAM)
        Object lamDirect = mot.get("rqa_laminarity_vertical_trapping");
        double rqaLaminarity;
        if (lamDirect != null) {
            rqaLaminarity = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(lamDirect, 0.75))), 3);
        } else {
            rqaLaminarity = PyRuntime.round(
                PyRuntime.min(0.99, PyRuntime.max(0.05, statLike * 0.7 + recurrenceDensity * 0.25)), 3);
        }

        // 101. Lempel-Ziv algorithmic complexity
        Object lzDirect = mot.get("lempel_ziv_algorithmic_complexity");
        double lzComplexity;
        if (lzDirect != null) {
            lzComplexity = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(2.0, PyRuntime.safeFloat(lzDirect, 0.5))), 3);
        } else {
            lzComplexity = PyRuntime.round(
                PyRuntime.min(1.8, PyRuntime.max(0.05, (1.0 - rqaDeterminism) * 1.2 + 0.1)), 3);
        }

        // 102. Fisher information measure
        Object fisherDirect = mot.get("fisher_information_measure");
        double fisherInfo;
        if (fisherDirect != null) {
            fisherInfo = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(10.0, PyRuntime.safeFloat(fisherDirect, 2.5))), 3);
        } else {
            fisherInfo = PyRuntime.round(
                PyRuntime.min(9.5,
                    PyRuntime.max(0.1, rqaDeterminism * 5.0 + (1.0 - motEnergy) * 2.5)), 3);
        }

        // 103. Phase-space mean trapping residence time in seconds
        Object trapDirect = mot.get("phase_space_trapping_time_seconds");
        double trappingTimeS;
        if (trapDirect != null) {
            trappingTimeS = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(3600.0, PyRuntime.safeFloat(trapDirect, 60.0))), 1);
        } else {
            trappingTimeS = PyRuntime.round(
                PyRuntime.min(3600.0, residencePersistenceHours * 3600.0 + rqaLaminarity * 120.0), 1);
        }

        // 105. Bifurcation stability margin
        Object bifurcDirect = mot.get("bifurcation_stability_margin");
        double bifurcationMargin;
        if (bifurcDirect != null) {
            bifurcationMargin = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(2.0, PyRuntime.safeFloat(bifurcDirect, 0.8))), 3);
        } else {
            bifurcationMargin = PyRuntime.round(
                PyRuntime.max(0.01, PyRuntime.min(1.8, 1.0 - tremorLyapunov * 0.3)), 3);
        }

        // 107. Phase-space orbital angular momentum norm
        Object angMomDirect = mot.get("phase_space_angular_momentum");
        double orbitalAngularMomentum;
        if (angMomDirect != null) {
            orbitalAngularMomentum = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(20.0, PyRuntime.safeFloat(angMomDirect, 1.0))), 3);
        } else {
            orbitalAngularMomentum = PyRuntime.round(
                PyRuntime.min(18.0, PyRuntime.max(0.05, phaseVelocity * 3.5 + rotJerk * 0.2)), 3);
        }

        // 109. Kaplan-Yorke fractal Lyapunov attractor dimension
        Object kyDirect = mot.get("dissipative_lyapunov_dimension_ky");
        double kaplanYorkeDim;
        if (kyDirect != null) {
            kaplanYorkeDim = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(6.0, PyRuntime.safeFloat(kyDirect, 1.5))), 3);
        } else {
            kaplanYorkeDim = PyRuntime.round(
                PyRuntime.min(5.5,
                    PyRuntime.max(0.2, corrDimension + PyRuntime.max(0.0, tremorLyapunov * 0.5))), 3);
        }

        // 110. Composite multimodal holographic phase coherence
        Object cohereDirect = mot.get("holo_cosmic_coherence_index");
        double holoCoherence;
        if (cohereDirect != null) {
            holoCoherence = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(cohereDirect, 0.85))), 3);
        } else {
            double rawCohere = rqaDeterminism * 0.3
                + (1.0 - circadianDisruption) * 0.3
                + spatialCoherence * 0.2
                + (1.0 - memoryPsi) * 0.2;
            holoCoherence = PyRuntime.round(PyRuntime.min(1.0, PyRuntime.max(0.05, rawCohere)), 3);
        }

        // Update persistent state across frames: only on a valid timestamp that differs from the last one.
        if (currentTs != null && (lastTsSeconds == null || currentTs.doubleValue() != lastTsSeconds.doubleValue())) {
            lastTsSeconds = currentTs;
            lastTemp = devTemp;
            if (accelUsable) {
                List<?> raw = (List<?>) accelRaw;
                lastAccelRaw = new double[]{
                    PyRuntime.safeFloat(raw.get(0), 0.0),
                    PyRuntime.safeFloat(raw.get(1), 0.0),
                    PyRuntime.safeFloat(raw.get(2), 9.8),
                };
            } else {
                lastAccelRaw = null;
            }
            lastJerkRaw = currentJerk;
            lastTremorEnergy = physioTremor;
        }

        return new double[]{
            conductionRate, residencePersistenceHours, thermalAccelDt2, recurrenceDensity,
            trajectoryCurvature, phaseVelocity, tapRecoil, trajectoryTorsion, thermoDissipationRatio,
            tremorLyapunov, informationFlow, touchDownforceDeriv, rqaDeterminism, rqaLaminarity,
            lzComplexity, fisherInfo, trappingTimeS, bifurcationMargin, orbitalAngularMomentum,
            kaplanYorkeDim, holoCoherence,
        };
    }

    /** Python: accel_raw and isinstance(accel_raw, (list, tuple)) and len(accel_raw) >= 3. */
    private static boolean usableVector(Object value) {
        return PyRuntime.truthy(value) && value instanceof List && ((List<?>) value).size() >= 3;
    }

    /** Python: a or b. The second operand is returned whenever the first is falsy (even if it is None). */
    private static Object firstTruthy(Object first, Object second) {
        return PyRuntime.truthy(first) ? first : second;
    }

    /**
     * Python: self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds.
     * A missing current timestamp compares unequal to any previous one (None != float is True).
     */
    private boolean newInstant(Double current) {
        return lastTsSeconds == null || current == null || current.doubleValue() != lastTsSeconds.doubleValue();
    }

    /** Python: sum((c1 - c2) ** 2 for c1, c2 in zip(a, b)), including sum()'s compensated summation. */
    private static double squaredDistance(double[] a, double[] b) {
        double[] terms = new double[Math.min(a.length, b.length)];
        for (int i = 0; i < terms.length; i++) {
            double d = a[i] - b[i];
            terms[i] = d * d;
        }
        return pySum(terms);
    }

    /**
     * Python 3.12+ sum() over floats: Neumaier compensated summation, starting from the integer 0.
     * Naive left-to-right addition differs from it in the last bit for roughly a third of 5-term sums.
     */
    private static double pySum(double[] terms) {
        double sum = 0.0;
        double compensation = 0.0;
        for (double x : terms) {
            double t = sum + x;
            if (Math.abs(sum) >= Math.abs(x)) {
                compensation += (sum - t) + x;
            } else {
                compensation += (x - t) + sum;
            }
            sum = t;
        }
        // Do not lose the sign of a negative result, nor turn an infinite sum into NaN.
        if (compensation != 0.0 && !Double.isNaN(compensation) && !Double.isInfinite(compensation)) {
            sum += compensation;
        }
        return sum;
    }

    /** Python numerator / denominator: a zero denominator raises ZeroDivisionError instead of yielding inf/NaN. */
    private static double divide(double numerator, double denominator) {
        if (denominator == 0.0) throw new ArithmeticException("float division by zero");
        return numerator / denominator;
    }

    /** Python math.log2: exact for powers of two (as libm's log2 is), otherwise ln(x) / ln(2). */
    private static double log2(double x) {
        if (x > 0.0 && !Double.isInfinite(x)) {
            int exponent = Math.getExponent(x);
            if (exponent >= Double.MIN_EXPONENT && x == Math.scalb(1.0, exponent)) return exponent;
        }
        return Math.log(x) / Math.log(2.0);
    }
}
