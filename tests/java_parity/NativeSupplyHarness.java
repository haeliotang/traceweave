// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Replays JSONL "native samples" through the same calls the service makes (FeatureReadings sensor
 * methods, usage window, ReferenceFeatureSupplier) and prints, per sample, the observation handed to
 * the reference extractor plus every supplied value, so Python can recompute them from that observation.
 */
public final class NativeSupplyHarness {
    private static Double number(Map<String, Object> sample, String key) {
        Object value = sample.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private static float[] vector(Object value) {
        if (!(value instanceof List)) return null;
        List<?> list = (List<?>) value;
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = ((Number) list.get(i)).floatValue();
        return out;
    }

    public static void main(String[] args) throws Exception {
        FeatureReadings readings = new FeatureReadings();
        ReferenceFeatureSupplier supplier = new ReferenceFeatureSupplier();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> sample = (Map<String, Object>) MiniJson.parse(line);
                if (sample.containsKey("__reset")) {
                    readings.reset();
                    supplier.reset();
                    out.append("#RESET\n");
                    continue;
                }
                long wall = number(sample, "wall").longValue(), nanos = number(sample, "nanos").longValue();
                readings.clock(wall, nanos);
                float[] accel = vector(sample.get("accel")), gyro = vector(sample.get("gyro"));
                if (accel != null) readings.accelerometer(accel, nanos);
                if (gyro != null) readings.gyroscope(gyro, nanos);
                readings.motion(nanos);
                if (number(sample, "lux") != null) readings.light(number(sample, "lux"), nanos);
                if (number(sample, "pressure") != null) readings.pressure(number(sample, "pressure"), nanos);
                if (number(sample, "temp") != null) readings.deviceTemperature(number(sample, "temp"), nanos);
                readings.windows(nanos);
                if (number(sample, "battery") != null) readings.put("device_battery_fraction", number(sample, "battery"), "derived", "battery_broadcast", nanos, 5000000000L);
                if (number(sample, "plugged") != null) readings.put("device_is_plugged", number(sample, "plugged"), "derived", "battery_broadcast", nanos, 5000000000L);
                if (number(sample, "charging") != null) readings.put("device_is_charging", number(sample, "charging"), "derived", "battery_broadcast", nanos, 5000000000L);
                if (number(sample, "current") != null) readings.put("battery_current_ua", number(sample, "current"), "observed", "battery_current_now_microamperes", nanos, 5000000000L);
                if (number(sample, "wifi") != null) readings.put("conn_wifi_connected", number(sample, "wifi"), "observed", "available_wifi_transport", nanos, 5000000000L);
                ReferenceObservation.Inputs inputs = new ReferenceObservation.Inputs();
                inputs.wallMillis = wall;
                inputs.nanos = nanos;
                inputs.screenState = String.valueOf(sample.get("screen"));
                if (number(sample, "voltage") != null) inputs.voltageMv = number(sample, "voltage");
                if (number(sample, "rssi") != null) inputs.wifiRssiDbm = number(sample, "rssi");
                if (sample.get("headset") instanceof Boolean) inputs.headsetConnected = (Boolean) sample.get("headset");
                if (sample.get("events") instanceof List) {
                    NativeUsageSource.Window window = new NativeUsageSource.Window();
                    for (Object item : (List<?>) sample.get("events")) {
                        List<?> pair = (List<?>) item;
                        window.add(((Number) pair.get(0)).intValue(), (String) pair.get(1));
                    }
                    inputs.usage = window.countsWithDigital().at(nanos);
                }
                supplier.supply(readings, inputs);
                out.append("{\"ran\":").append(supplier.lastRan()).append(",\"obs\":");
                json(out, supplier.lastObservation());
                out.append(",\"values\":{");
                boolean first = true;
                for (String name : ReferenceFeatureEngine.NAMES) {
                    FeatureReadings.Reading reading = readings.get(name, nanos);
                    if (reading == null) continue;
                    if (!first) out.append(',');
                    first = false;
                    out.append('"').append(name).append("\":").append(Double.toString(reading.value));
                }
                out.append("},\"kinds\":{");
                first = true;
                for (String name : ReferenceFeatureEngine.NAMES) {
                    FeatureReadings.Reading reading = readings.get(name, nanos);
                    if (reading == null) continue;
                    if (!first) out.append(',');
                    first = false;
                    out.append('"').append(name).append("\":\"").append(reading.kind).append('"');
                }
                out.append("}}\n");
            }
        }
        System.out.print(out);
    }

    private static void json(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append('"').append(entry.getKey()).append("\":");
                json(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                json(out, item);
            }
            out.append(']');
        } else if (value instanceof String) out.append('"').append(((String) value).replace("\"", "\\\"")).append('"');
        else if (value instanceof Double) {
            double d = (Double) value;
            out.append(Double.isNaN(d) ? "NaN" : Double.isInfinite(d) ? (d > 0 ? "Infinity" : "-Infinity") : Double.toString(d));
        } else out.append(value);
    }
}
