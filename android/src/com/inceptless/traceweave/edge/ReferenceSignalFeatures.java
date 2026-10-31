package com.inceptless.traceweave.edge;

import java.util.ArrayList;
import java.util.Map;

/**
 * Statement-by-statement port of the motion, environment, RF and thermal formula outputs of
 * MultimodalNormalizer.extract_raw_features (traceform/representation/normalizer.py), group B.
 * Missing fields take the same defaults as Python; see tests/test_reference_features_signal.py.
 *
 * Self state (reset exactly as MultimodalNormalizer.reset_state does for it): _last_ts_seconds,
 * _last_voltage_mv, _last_current_ua, _recent_coords. The preamble (timestamp, screen ratio, touch
 * velocity, ...) is a private copy of the Python lines the outputs read, so this class does not
 * depend on any other group.
 */
final class ReferenceSignalFeatures {
    static final String[] NAMES = {
        "physio_ballisto_tremor_energy",
        "rf_spectral_penetration_ratio",
        "neuromotor_tremor_spectral_ratio",
        "wifi_rssi_distribution_skewness",
        "phase_space_correlation_dimension",
        "wifi_rssi_distribution_kurtosis",
        "photometric_spectral_warmth_index",
        "device_structural_resonance_energy",
        "ultrasonic_proximity_micro_doppler",
        "gnss_doppler_velocity_drift",
        "thermal_soc_to_skin_gradient",
        "thermal_entropy_production_rate",
        "fractal_hurst_exponent",
        "multiscale_approximate_entropy",
        "topological_betti_number_proxy",
    };

    /** deque(maxlen=20) of (timestamp, coord_summary); each entry is {t_entry, c0, c1, c2, c3, c4}. */
    private static final int RECENT_COORDS_MAX = 20;

    /** MultimodalNormalizer.feature_extraction_version: 2 unless the normalization params say 1. */
    private final int featureExtractionVersion;

    private Double lastTsSeconds;
    private Double lastVoltageMv;
    private Double lastCurrentUa;
    private final ArrayList<double[]> recentCoords = new ArrayList<double[]>();

    ReferenceSignalFeatures() {
        this(2);
    }

    ReferenceSignalFeatures(int featureExtractionVersion) {
        if (featureExtractionVersion != 1 && featureExtractionVersion != 2) {
            throw new IllegalArgumentException("unsupported feature extraction version");
        }
        this.featureExtractionVersion = featureExtractionVersion;
    }

    void reset() {
        lastTsSeconds = null;
        lastVoltageMv = null;
        lastCurrentUa = null;
        recentCoords.clear();
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

        // 3. Device Relation
        Map<String, Object> dev = PyRuntime.dict(obs, "device_relation");
        Object batRaw = dev.get("battery_percentage");
        double batteryFrac = batRaw != null ? (PyRuntime.safeFloat(batRaw, 50.0) / 100.0) : 0.5;
        double batteryCurrentUa = PyRuntime.safeFloat(dev.get("current_ua"), 0.0);

        // 4. Audio Context
        Map<String, Object> aud = PyRuntime.dict(obs, "audio_context");
        double silenceLike;
        if ("NOT_SAMPLED".equals(aud.get("status"))) {
            silenceLike = 1.0;
        } else {
            silenceLike = PyRuntime.safeFloat(aud.get("silence_likelihood"), 1.0);
        }

        // 5. Connectivity
        Map<String, Object> conn = PyRuntime.dict(obs, "connectivity");
        double wifiConn = PyRuntime.truthy(conn.get("wifi_connected")) ? 1.0 : 0.0;
        Object wifiRssiRaw = conn.get("wifi_rssi");
        double wifiRssiNorm;
        if (wifiRssiRaw != null) {
            double rssiVal = PyRuntime.safeFloat(wifiRssiRaw, -100.0);
            wifiRssiNorm = PyRuntime.max(0.0, PyRuntime.min(1.0, (rssiVal + 100.0) / 70.0));
        } else {
            wifiRssiNorm = 0.0;
        }

        // 6. Digital & Cognitive Features
        Map<String, Object> dig = PyRuntime.dict(obs, "digital_context");
        double screenRatio = PyRuntime.safeFloat(dig.get("screen_on_ratio"), 0.0);
        if (!dig.containsKey("screen_on_ratio")) {
            Object screenState = PyRuntime.getOr(dev, "screen_state", "UNKNOWN");
            if ("ON".equals(screenState)) {
                screenRatio = 1.0;
            } else if ("DOZING".equals(screenState)) {
                screenRatio = 0.2;
            } else {
                screenRatio = 0.0;
            }
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

        // 7. Audio Immersion State
        double isImmersed =
            (PyRuntime.truthy(dev.get("wired_headset")) || PyRuntime.truthy(dev.get("bluetooth_a2dp"))) ? 1.0 : 0.0;

        // 8. Circadian phases: only daily_fraction and current_ts_seconds are read by these outputs
        PyRuntime.Stamp stamp = PyRuntime.parseTimestamp(obs.containsKey("timestamp_utc") ? obs.get("timestamp_utc") : "");
        Double currentTsSeconds = null;
        boolean hasDailyFraction = false;
        double dailyFraction = 0.0;
        if (stamp.valid) {
            double secondsInDay = stamp.secondsInDay;
            dailyFraction = secondsInDay / 86400.0;
            hasDailyFraction = true;
            currentTsSeconds = stamp.epochSeconds;
        }

        // 10. Physiological micro-tremor & ballistocardiogram
        double physioTremor;
        Object physioDirect = mot.get("ballisto_energy");
        if (physioDirect != null) {
            physioTremor = PyRuntime.safeFloat(physioDirect, 0.0);
        } else {
            double gyroEnergy = PyRuntime.safeFloat(mot.get("gyro_energy"), 0.0);
            if (statLike >= 0.8) {
                physioTremor = PyRuntime.round(
                    PyRuntime.min(
                        1.0, motEnergy * statLike * PyRuntime.max(0.0, 1.0 - 50.0 * gyroEnergy)
                    ),
                    4);
            } else {
                physioTremor = 0.0;
            }
        }

        // 19. Estimated SoC computational power dissipation in Watts
        double computePowerW;
        Object compPowerDirect = dev.get("compute_power_w");
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

        // 20. Battery dynamic electrochemical internal impedance in mOhm
        double batteryImpedance;
        Object rIntDirect = dev.get("internal_resistance_miliohm");
        double voltageMv = PyRuntime.safeFloat(dev.get("voltage_mv"), 4000.0);
        if (rIntDirect != null) {
            batteryImpedance = PyRuntime.safeFloat(rIntDirect, 180.0);
        } else {
            batteryImpedance = 180.0;
            if (lastVoltageMv != null && lastCurrentUa != null) {
                double dIUa = Math.abs(batteryCurrentUa - lastCurrentUa.doubleValue());
                double dVMv = Math.abs(voltageMv - lastVoltageMv.doubleValue());
                if (dIUa >= 40000.0) {
                    double calcR = (dVMv / dIUa) * 1000000.0;
                    batteryImpedance = PyRuntime.round(PyRuntime.max(50.0, PyRuntime.min(800.0, calcR)), 1);
                } else {
                    batteryImpedance = PyRuntime.round(
                        PyRuntime.max(80.0, 160.0 + PyRuntime.max(0.0, 30.0 - devTemp) * 6.0), 1);
                }
            } else {
                batteryImpedance = PyRuntime.round(
                    PyRuntime.max(80.0, 160.0 + PyRuntime.max(0.0, 30.0 - devTemp) * 6.0), 1);
            }
        }

        // 23. Dual-band RF 2.4GHz vs 5GHz propagation ratio
        double rfPenetration;
        double wifiFreq = PyRuntime.safeFloat(conn.get("wifi_frequency_mhz"), 2412.0);
        if (wifiConn > 0.0) {
            if (wifiFreq >= 4900.0) {
                rfPenetration = PyRuntime.round(
                    PyRuntime.max(0.1, PyRuntime.min(2.0, 0.6 + wifiRssiNorm * 0.8)), 3);
            } else {
                rfPenetration = PyRuntime.round(
                    PyRuntime.max(0.1, PyRuntime.min(2.0, 1.1 + wifiRssiNorm * 0.7)), 3);
            }
        } else {
            rfPenetration = 0.0;
        }

        // 28. Physiological micro-tremor to motion spectral ratio
        double neuromotorRatio;
        Object tremorRatioDirect = or(dig.get("neuromotor_tremor_spectral_ratio"),
            mot.get("neuromotor_tremor_spectral_ratio"));
        if (tremorRatioDirect != null) {
            neuromotorRatio = PyRuntime.safeFloat(tremorRatioDirect, 0.35);
        } else {
            neuromotorRatio = PyRuntime.round(
                PyRuntime.min(5.0, physioTremor / PyRuntime.max(0.015, motEnergy * 0.4 + 0.02)), 3);
        }

        // 36. Rolling phase-space recurrence density: only the coordinate buffer update is read here
        double[] coordSummary = {
            PyRuntime.round(accelMag, 2),
            PyRuntime.round(devTemp, 1),
            PyRuntime.round(batteryFrac, 2),
            PyRuntime.round(logLux, 2),
            PyRuntime.round(statLike, 2),
        };
        double tEntry = currentTsSeconds != null ? currentTsSeconds.doubleValue() : 0.0;
        if (!recentCoords.isEmpty() && recentCoords.get(recentCoords.size() - 1)[0] == tEntry) {
            recentCoords.set(recentCoords.size() - 1, coordEntry(tEntry, coordSummary));
        } else {
            recentCoords.add(coordEntry(tEntry, coordSummary));
            if (recentCoords.size() > RECENT_COORDS_MAX) recentCoords.remove(0);
        }

        // 42. Wi-Fi multi-AP RSSI distribution skewness
        double wifiSkewness;
        Object wifiSkewDirect = or(conn.get("wifi_rssi_distribution_skewness"),
            dig.get("wifi_rssi_distribution_skewness"));
        if (wifiSkewDirect != null) {
            wifiSkewness = PyRuntime.round(
                PyRuntime.max(-3.0, PyRuntime.min(3.0, PyRuntime.safeFloat(wifiSkewDirect, 0.0))), 3);
        } else {
            if (wifiConn > 0.0) {
                wifiSkewness = PyRuntime.round(
                    PyRuntime.max(-2.5, PyRuntime.min(2.5, (0.5 - wifiRssiNorm) * 3.0)), 3);
            } else {
                wifiSkewness = 0.0;
            }
        }

        // 43. Battery internal electrochemical Joule heat loss in Watts
        double iAmp = Math.abs(batteryCurrentUa) * 1e-6;
        double rOhm = batteryImpedance * 1e-3;
        double jouleHeatW = PyRuntime.round(PyRuntime.min(5.0, square(iAmp) * rOhm), 5);

        // 47. Phase-space Grassberger-Procaccia correlation dimension estimate
        double corrDimension;
        Object corrDimDirect = or(mot.get("phase_space_correlation_dimension"), dig.get("correlation_dimension"));
        if (corrDimDirect != null) {
            corrDimension = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(5.0, PyRuntime.safeFloat(corrDimDirect, 1.2))), 3);
        } else {
            if (recentCoords.size() >= 6) {
                // dists: every pair i < j of the buffer, d = sqrt(sum of squared coordinate differences)
                int n1 = 0;
                int n2 = 0;
                int count = recentCoords.size();
                double[] dists = new double[count * (count - 1) / 2];
                int filled = 0;
                for (int i = 0; i < count; i++) {
                    for (int j = 0; j < count; j++) {
                        if (i < j) {
                            dists[filled++] = Math.sqrt(squaredDistance(recentCoords.get(i), recentCoords.get(j)));
                        }
                    }
                }
                for (int k = 0; k < filled; k++) if (dists[k] < 1.0) n1 += 1;
                for (int k = 0; k < filled; k++) if (dists[k] < 3.0) n2 += 1;
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

        // 50. Wi-Fi multi-AP RSSI distribution excess kurtosis
        double wifiKurtosis;
        Object wifiKurtDirect = or(conn.get("wifi_rssi_distribution_kurtosis"), conn.get("wifi_kurtosis"));
        if (wifiKurtDirect != null) {
            wifiKurtosis = PyRuntime.round(
                PyRuntime.max(-2.0, PyRuntime.min(8.0, PyRuntime.safeFloat(wifiKurtDirect, 0.0))), 3);
        } else {
            if (wifiConn > 0.0) {
                double calcKurt = PyRuntime.max(-2.0, PyRuntime.min(8.0, square(wifiSkewness) - 1.2));
                wifiKurtosis = PyRuntime.round(calcKurt, 3);
            } else {
                wifiKurtosis = 0.0;
            }
        }

        // 56. Photometric dynamic spectral warmth / CCT evolution index
        double warmthIndex;
        Object warmthDirect = or(env.get("photometric_spectral_warmth_index"), env.get("color_temperature_warmth"));
        if (warmthDirect != null) {
            warmthIndex = PyRuntime.round(PyRuntime.max(0.0, PyRuntime.min(5.0, PyRuntime.safeFloat(warmthDirect, 1.0))), 3);
        } else {
            Object cctVal = or(env.get("cct_k"), env.get("color_temperature"));
            if (cctVal != null) {
                double calcWarmth = 6500.0 / PyRuntime.max(1500.0, PyRuntime.safeFloat(cctVal, 4500.0));
                warmthIndex = PyRuntime.round(PyRuntime.min(5.0, PyRuntime.max(0.0, calcWarmth)), 3);
            } else {
                double dailyFrac = hasDailyFraction ? dailyFraction : 0.5;
                double warmthEst =
                    1.0
                    + 0.8 * PyRuntime.max(0.0, Math.cos(dailyFrac * 2.0 * Math.PI))
                    + 0.4 * (1.0 - PyRuntime.min(1.0, logLux / 4.0));
                warmthIndex = PyRuntime.round(PyRuntime.min(5.0, PyRuntime.max(0.0, warmthEst)), 3);
            }
        }

        // 66. Device Structural Resonance Energy
        double resonanceEnergy;
        Object resDirect = mot.get("device_structural_resonance_energy");
        if (resDirect != null) {
            resonanceEnergy = PyRuntime.round(PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(resDirect, 0.1))), 3);
        } else {
            resonanceEnergy = PyRuntime.round(
                PyRuntime.min(1.0, PyRuntime.max(0.01, 0.12 + 0.45 * physioTremor + 0.25 * motEnergy)), 3);
        }

        // 70. Ultrasonic Proximity Micro-Doppler
        double microDoppler;
        Object dopplerDirect = or(aud.get("ultrasonic_proximity_micro_doppler"), aud.get("micro_doppler"));
        if (dopplerDirect != null) {
            microDoppler = PyRuntime.round(PyRuntime.max(0.0, PyRuntime.min(5.0, PyRuntime.safeFloat(dopplerDirect, 0.0))), 3);
        } else {
            microDoppler = PyRuntime.round(
                PyRuntime.min(5.0, PyRuntime.max(0.0, touchVelocity * 0.8 + (1.0 - silenceLike) * 0.3)), 3);
        }

        // 74. GNSS Doppler Carrier Drift Velocity in m/s
        Map<String, Object> plc = PyRuntime.dict(obs, "place");
        double dopplerSpeed;
        Object dopplerDriftDirect = plc.get("doppler_velocity_drift");
        if (dopplerDriftDirect == null || (featureExtractionVersion == 1 && !PyRuntime.truthy(dopplerDriftDirect))) {
            dopplerDriftDirect = plc.get("gnss_speed_mps");
        }
        if (dopplerDriftDirect != null) {
            dopplerSpeed = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(30.0, PyRuntime.safeFloat(dopplerDriftDirect, 0.0))), 2);
        } else {
            dopplerSpeed = PyRuntime.round(
                PyRuntime.min(30.0, PyRuntime.max(0.0, (1.0 - statLike) * 3.5 + motEnergy * 2.0)), 2);
        }

        // 75. SoC Core to Skin Thermal Gradient in deg C
        double socSkinGrad;
        Object socGradDirect = or(env.get("thermal_soc_to_skin_gradient"), env.get("soc_skin_gradient"));
        if (socGradDirect != null) {
            socSkinGrad = PyRuntime.round(
                PyRuntime.max(-10.0, PyRuntime.min(30.0, PyRuntime.safeFloat(socGradDirect, 4.0))), 2);
        } else {
            socSkinGrad = PyRuntime.round(
                PyRuntime.max(
                    -5.0,
                    PyRuntime.min(25.0, 2.0 + computePowerW * 2.8 + (devTemp - 25.0) * 0.2)),
                2);
        }

        // 78. Irreversible Thermal Entropy Production in mW/K
        double entropyProdRate;
        Object sProdDirect = env.get("thermal_entropy_production_rate");
        if (sProdDirect != null) {
            entropyProdRate = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(10.0, PyRuntime.safeFloat(sProdDirect, 0.5))), 3);
        } else {
            entropyProdRate = PyRuntime.round(
                PyRuntime.min(
                    10.0,
                    PyRuntime.max(0.01, (jouleHeatW * 1000.0) / PyRuntime.max(273.15, devTemp + 273.15))),
                3);
        }

        // 97. Multi-scale R/S Hurst Exponent H
        double hurstExp;
        Object hurstDirect = mot.get("fractal_hurst_exponent");
        if (hurstDirect != null) {
            hurstExp = PyRuntime.round(PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(hurstDirect, 0.65))), 3);
        } else {
            hurstExp = statLike >= 0.8
                ? 0.82
                : PyRuntime.round(PyRuntime.min(0.95, PyRuntime.max(0.2, 0.55 + 0.3 * motEnergy)), 3);
        }

        // 98. Permutation Entropy Complexity (read by the approximate entropy fallback)
        double permutationEntropy;
        Object permDirect = mot.get("permutation_entropy_complexity");
        if (permDirect != null) {
            permutationEntropy = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(1.0, PyRuntime.safeFloat(permDirect, 0.4))), 3);
        } else {
            permutationEntropy = statLike >= 0.8
                ? 0.15
                : PyRuntime.round(PyRuntime.min(0.98, PyRuntime.max(0.1, 0.4 + 0.45 * motEnergy)), 3);
        }

        // 104. Multiscale Approximate Entropy (ApEn)
        double approximateEntropy;
        Object apenDirect = mot.get("multiscale_approximate_entropy");
        if (apenDirect != null) {
            approximateEntropy = PyRuntime.round(
                PyRuntime.max(0.0, PyRuntime.min(3.0, PyRuntime.safeFloat(apenDirect, 0.6))), 3);
        } else {
            approximateEntropy = PyRuntime.round(
                PyRuntime.min(2.8, PyRuntime.max(0.05, permutationEntropy * 2.2)), 3);
        }

        // 108. Topological Betti Number Proxy
        double bettiProxy;
        Object bettiDirect = mot.get("topological_betti_number_proxy");
        if (bettiDirect != null) {
            bettiProxy = PyRuntime.round(PyRuntime.max(0.0, PyRuntime.min(5.0, PyRuntime.safeFloat(bettiDirect, 1.0))), 2);
        } else {
            bettiProxy = PyRuntime.round(PyRuntime.min(4.5, PyRuntime.max(0.1, corrDimension * 0.8)), 2);
        }

        // Update persistent state across frames
        if (currentTsSeconds != null && (lastTsSeconds == null || currentTsSeconds.doubleValue() != lastTsSeconds.doubleValue())) {
            lastTsSeconds = currentTsSeconds;
            lastVoltageMv = Double.valueOf(voltageMv);
            lastCurrentUa = Double.valueOf(batteryCurrentUa);
        }

        return new double[]{
            physioTremor,
            rfPenetration,
            neuromotorRatio,
            wifiSkewness,
            corrDimension,
            wifiKurtosis,
            warmthIndex,
            resonanceEnergy,
            microDoppler,
            dopplerSpeed,
            socSkinGrad,
            entropyProdRate,
            hurstExp,
            approximateEntropy,
            bettiProxy,
        };
    }

    /** Python "a or b": the first operand when it is truthy, otherwise the second (which may be None). */
    private static Object or(Object first, Object second) {
        return PyRuntime.truthy(first) ? first : second;
    }

    private static double[] coordEntry(double timestamp, double[] coordSummary) {
        return new double[]{timestamp, coordSummary[0], coordSummary[1], coordSummary[2], coordSummary[3], coordSummary[4]};
    }

    /** Python float ** 2: overflow of a finite operand raises OverflowError instead of returning infinity. */
    private static double square(double x) {
        double result = x * x;
        if (Double.isInfinite(result) && !Double.isInfinite(x)) {
            throw new ArithmeticException("(34, 'Numerical result out of range')");
        }
        return result;
    }

    /**
     * sum((c1 - c2) ** 2 for c1, c2 in zip(c_i, c_j)) over two buffer entries (coordinates at index 1..5).
     * CPython 3.12+ sums floats with Neumaier compensation, starting from int 0, so a plain left-to-right
     * addition would differ in the last bit; this reproduces builtin sum().
     */
    private static double squaredDistance(double[] left, double[] right) {
        double result = 0.0 + square(left[1] - right[1]);
        double compensation = 0.0;
        for (int k = 2; k <= 5; k++) {
            double x = square(left[k] - right[k]);
            double t = result + x;
            if (Math.abs(result) >= Math.abs(x)) {
                compensation += (result - t) + x;
            } else {
                compensation += (x - t) + result;
            }
            result = t;
        }
        if (compensation != 0.0 && !Double.isInfinite(compensation) && !Double.isNaN(compensation)) {
            result += compensation;
        }
        return result;
    }
}
