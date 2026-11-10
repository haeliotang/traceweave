// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of the formula layer of DigitalSensoriumProbe.sample_digital_interaction_metrics
 * (traceform/probes/digital_sensorium.py): it turns the values Python extracts from dumpsys text into
 * the nine-key digital_context map. The native app does not parse dumpsys; it supplies the parsed
 * values, defined here so that they carry exactly what the Python probe extracts.
 *
 * How a caller expresses the Python probe's states:
 *
 * - screenState: "ON", "DOZING", "OFF" or "UNKNOWN". Python returns "UNKNOWN" when dumpsys is absent
 *   (has_dumpsys false), when "dumpsys power" fails or when no mWakefulness= line says Awake, Dozing
 *   or Asleep.
 * - wifiApCount: Python's fallback_wifi_ap_count.
 * - bluetoothDumpAvailable / bluetoothDeviceCount: false and 0.0 when dumpsys is absent, the
 *   bluetooth_manager dump fails or its stdout is empty. Otherwise true and the sum Python derives:
 *   starting from 0.0, in dump order, every parsable number on a "Connected count:", "Bonded devices:"
 *   or "Other devices:" line (lines whose value does not parse as a float contribute nothing).
 *   A true flag with a sum of 0.0 behaves like false, as in Python.
 * - recentEvents: null when the usagestats dump is unavailable (dumpsys absent, non-zero exit,
 *   exception or empty stdout). Python then returns early with only screen_on_ratio,
 *   ambient_social_ble_density, ble_rssi_spatial_variance and ambient_rf_shannon_entropy set.
 *   Otherwise the chronological list of every event line that precedes the "In-memory daily stats"
 *   marker and contains both "time=" and "type=", of any event type (the 120-event window counts all
 *   of them, not only the ones that matter). Each entry is {type, packageOrNull}: type is the token
 *   after "type=" (up to whitespace), package is the first "package=" token's non-blank value, or
 *   null when the line has none. An empty list means a non-empty dump with no event lines, which is
 *   not the same as null: with the screen ON Python still reports touch_interaction_velocity 1.0.
 *   The list may be longer than 120; like Python only the last 120 entries are used.
 *
 * Python tests events with substring checks ("type=ACTIVITY_RESUMED" in line); with one "type="
 * token per line that is a prefix test on the type token, which is what is done here. Package names
 * are used only inside this call to count ACTIVITY_RESUMED events per package; none is stored.
 *
 * Every value in the result is a Double, as Python's values are floats.
 */
final class ReferenceDigitalProbe {
    private ReferenceDigitalProbe() {}

    /** Python keeps event_lines[-120:]. */
    static final int RECENT_EVENT_WINDOW = 120;

    /** math.log2 for a positive finite double: exact for powers of two, ln(x)/ln(2) otherwise. */
    private static double log2(double value) {
        if (value > 0.0 && !Double.isInfinite(value)) {
            int exponent = Math.getExponent(value);
            if (exponent >= Double.MIN_EXPONENT && value == Math.scalb(1.0, exponent)) return exponent;
        }
        return Math.log(value) / Math.log(2.0);
    }

    static Map<String, Object> synthesize(String screenState, List<String[]> recentEvents, int wifiApCount,
                                          boolean bluetoothDumpAvailable, double bluetoothDeviceCount) {
        double screenOnRatio;
        double appSwitchRate = 0.0;
        double unlockBurstCount = 0.0;
        double touchInteractionVelocity = 0.0;
        double ambientSocialBleDensity;
        double ambientRfShannonEntropy = 0.0;
        double touchIntervalJitter = 0.0;
        double attentionFragmentationEntropy = 0.0;
        double bleRssiSpatialVariance = 0.0;

        // Check immediate screen state
        if ("ON".equals(screenState)) {
            screenOnRatio = 1.0;
        } else if ("DOZING".equals(screenState)) {
            screenOnRatio = 0.2;
        } else {
            screenOnRatio = 0.0;
        }

        // sample_social_ble_density(fallback_wifi_ap_count)
        double bleCount = 0.0;
        if (bluetoothDumpAvailable) {
            bleCount += bluetoothDeviceCount;
        }
        if (bleCount == 0.0 && wifiApCount > 0) {
            // Multi-RF fallback: dense WiFi AP environments correlate with social density
            bleCount = PyRuntime.round(PyRuntime.min(30.0, wifiApCount / 3.0), 2);
        }
        ambientSocialBleDensity = PyRuntime.min(50.0, bleCount);
        if (ambientSocialBleDensity > 1.0) {
            bleRssiSpatialVariance = PyRuntime.round(PyRuntime.min(25.0, ambientSocialBleDensity * 1.6), 2);
        }

        if (wifiApCount > 0) {
            ambientRfShannonEntropy = PyRuntime.round(
                PyRuntime.min(5.0, log2((double) wifiApCount + 1.0) * 0.85), 3);
        }

        if (recentEvents != null) {
            int from = recentEvents.size() > RECENT_EVENT_WINDOW ? recentEvents.size() - RECENT_EVENT_WINDOW : 0;
            int appSwitches = 0;
            int unlockEvents = 0;
            Map<String, Integer> packageCounts = new LinkedHashMap<String, Integer>();

            for (int i = from; i < recentEvents.size(); i++) {
                String[] event = recentEvents.get(i);
                String type = event[0];
                String pkg = event[1];
                if (type != null && type.startsWith("ACTIVITY_RESUMED")) {
                    appSwitches += 1;
                    if (pkg != null && !pkg.isEmpty()) {
                        Integer seen = packageCounts.get(pkg);
                        packageCounts.put(pkg, seen == null ? 1 : seen + 1);
                    }
                } else if (type != null
                    && (type.startsWith("SCREEN_INTERACTIVE") || type.startsWith("KEYGUARD_HIDDEN"))) {
                    unlockEvents += 1;
                }
            }

            // Normalize metrics
            appSwitchRate = (double) Math.min(50, appSwitches);
            unlockBurstCount = (double) Math.min(20, unlockEvents);

            // Touch velocity cadence & motor jitter estimation
            if ("ON".equals(screenState)) {
                touchInteractionVelocity = PyRuntime.round(
                    PyRuntime.min(10.0, 1.0 + appSwitches * 0.4 + unlockEvents * 0.3), 3);
                double jitterVal = (appSwitches * 0.25 + unlockEvents * 0.2)
                    / (1.0 + touchInteractionVelocity * 0.2);
                touchIntervalJitter = PyRuntime.round(PyRuntime.min(2.0, jitterVal), 3);
            } else {
                touchInteractionVelocity = 0.0;
                touchIntervalJitter = 0.0;
            }

            // Attention fragmentation entropy from the active package distribution
            if (!packageCounts.isEmpty() && appSwitches > 0) {
                long totalCount = 0;
                for (Integer count : packageCounts.values()) totalCount += count;
                double totalP = (double) totalCount;
                double ent = 0.0;
                for (Integer count : packageCounts.values()) {
                    double p = count / totalP;
                    if (p > 0.0) {
                        ent -= p * log2(p);
                    }
                }
                attentionFragmentationEntropy = PyRuntime.round(PyRuntime.min(4.0, ent), 3);
            }
        }

        Map<String, Object> metrics = new LinkedHashMap<String, Object>();
        metrics.put("screen_on_ratio", screenOnRatio);
        metrics.put("app_switch_rate", appSwitchRate);
        metrics.put("unlock_burst_count", unlockBurstCount);
        metrics.put("touch_interaction_velocity", touchInteractionVelocity);
        metrics.put("ambient_social_ble_density", ambientSocialBleDensity);
        metrics.put("ambient_rf_shannon_entropy", ambientRfShannonEntropy);
        metrics.put("touch_interval_jitter", touchIntervalJitter);
        metrics.put("attention_fragmentation_entropy", attentionFragmentationEntropy);
        metrics.put("ble_rssi_spatial_variance", bleRssiSpatialVariance);
        return metrics;
    }
}
