package org.traceform.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs the ported reference extractor on the device's own runtime against a fixture of
 * (a) observations with the values Python computes for them and (b) native-shaped samples with the
 * values the host JVM computes through the supplier. Reads only the fixture file; exercises no
 * sensor, usage-stats, network or actuator API and exports no device data.
 */
public final class SyntheticReferenceParityReceiver extends BroadcastReceiver {
    private static final class Tally {
        int compared, exact, flips, mismatches;
        double maxDeviation;
        final List<String> examples = new ArrayList<>();
        void check(String where, String name, double want, double got, JSONObject quantum) {
            compared++;
            double difference = Math.abs(want - got);
            if (want == got || (Double.isNaN(want) && Double.isNaN(got)) || difference <= 1e-9 * Math.max(1.0, Math.abs(want))) { exact++; return; }
            double step = quantum.optDouble(name, Double.NaN);
            if (!Double.isNaN(step) && difference <= step * 1.0000001) { flips++; return; }
            mismatches++;
            maxDeviation = Math.max(maxDeviation, difference);
            if (examples.size() < 5) examples.add(where + " " + name + " want " + want + " got " + got);
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("compared", compared).put("exact", exact).put("rounding_flips", flips)
                .put("mismatches", mismatches).put("max_mismatch_deviation", maxDeviation).put("examples", new JSONArray(examples));
        }
    }

    @Override public void onReceive(final Context context, Intent intent) {
        final String nonce = intent.getStringExtra("nonce");
        java.util.UUID.fromString(nonce);
        final PendingResult pending = goAsync();
        new Thread(new Runnable() { @Override public void run() {
            File receipt = new File(context.getFilesDir(), "reference_parity.json");
            try {
                JSONObject fixture = new JSONObject(read(new File(context.getFilesDir(), "reference_parity_fixture.json")));
                JSONObject quantum = fixture.getJSONObject("quantum");
                long started = System.nanoTime();
                Tally engineTally = new Tally(), supplierTally = new Tally();
                int engineFrames = engine(fixture.getJSONArray("engine_frames"), quantum, engineTally);
                int supplierFrames = supplier(fixture.getJSONArray("samples"), quantum, supplierTally);
                boolean passed = engineTally.mismatches == 0 && supplierTally.mismatches == 0
                    && engineTally.compared > 0 && supplierTally.compared > 0;
                NativeVerificationJob.write(receipt, new JSONObject().put("passed", passed).put("nonce", nonce)
                    .put("process_id", android.os.Process.myPid()).put("device_model", Build.MODEL)
                    .put("api_level", Build.VERSION.SDK_INT).put("runtime", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"))
                    .put("fixture_sha256", fixture.optString("fixture_id"))
                    .put("engine_vs_python", engineTally.json().put("frames", engineFrames))
                    .put("supplier_vs_host_jvm", supplierTally.json().put("frames", supplierFrames))
                    .put("elapsed_ms", (System.nanoTime() - started) / 1e6)
                    .put("collection_started", false).put("physical_api_calls", 0).put("actuator_calls", 0)
                    .put("scope", "isolated_fixture_parity_of_ported_formulas_on_device_runtime_not_sensor_or_behavioral_validation"));
            } catch (Throwable error) {
                try { NativeVerificationJob.write(receipt, new JSONObject().put("passed", false).put("nonce", nonce).put("error", error.toString())); }
                catch (Exception ignored) { android.util.Log.e("TraceformProbe", "reference parity receipt failed", error); }
            } finally { pending.finish(); }
        }}).start();
    }

    private static int engine(JSONArray frames, JSONObject quantum, Tally tally) throws Exception {
        ReferenceFeatureEngine engine = new ReferenceFeatureEngine();
        int used = 0;
        for (int i = 0; i < frames.length(); i++) {
            JSONObject frame = frames.getJSONObject(i);
            if (frame.optBoolean("reset")) { engine.reset(); continue; }
            Map<String, Double> values = engine.extract(toMap(frame.getJSONObject("obs")));
            used++;
            JSONObject expected = frame.optJSONObject("expected");
            if (expected == null) continue;  // Python itself raised on this frame
            for (Iterator<String> names = expected.keys(); names.hasNext();) {
                String name = names.next();
                tally.check("engine#" + i, name, expected.getDouble(name), values.get(name), quantum);
            }
        }
        return used;
    }

    private static float[] vector(JSONObject sample, String key) throws Exception {
        JSONArray array = sample.optJSONArray(key);
        if (array == null) return null;
        float[] out = new float[array.length()];
        for (int i = 0; i < out.length; i++) out[i] = (float) array.getDouble(i);
        return out;
    }

    private static Double number(JSONObject sample, String key) {
        return sample.isNull(key) || !sample.has(key) ? null : sample.optDouble(key);
    }

    /** Same sequence of FeatureReadings and supplier calls as the host harness (tests/java_parity). */
    private static int supplier(JSONArray samples, JSONObject quantum, Tally tally) throws Exception {
        FeatureReadings readings = new FeatureReadings();
        ReferenceFeatureSupplier supplier = new ReferenceFeatureSupplier();
        int used = 0;
        for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = samples.getJSONObject(i);
            if (sample.optBoolean("reset")) { readings.reset(); supplier.reset(); continue; }
            long wall = sample.getLong("wall"), nanos = sample.getLong("nanos");
            readings.clock(wall, nanos);
            float[] accel = vector(sample, "accel"), gyro = vector(sample, "gyro");
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
            inputs.wallMillis = wall; inputs.nanos = nanos; inputs.screenState = sample.getString("screen");
            if (number(sample, "voltage") != null) inputs.voltageMv = number(sample, "voltage");
            if (number(sample, "rssi") != null) inputs.wifiRssiDbm = number(sample, "rssi");
            if (!sample.isNull("headset") && sample.has("headset")) inputs.headsetConnected = sample.getBoolean("headset");
            JSONArray events = sample.optJSONArray("events");
            if (events != null) {
                NativeUsageSource.Window window = new NativeUsageSource.Window();
                for (int e = 0; e < events.length(); e++) {
                    JSONArray pair = events.getJSONArray(e);
                    window.add(pair.getInt(0), pair.isNull(1) ? null : pair.getString(1));
                }
                inputs.usage = window.countsWithDigital().at(nanos);
            }
            supplier.supply(readings, inputs);
            used++;
            JSONObject expected = sample.optJSONObject("expected");
            if (expected == null) continue;
            for (Iterator<String> names = expected.keys(); names.hasNext();) {
                String name = names.next();
                FeatureReadings.Reading reading = readings.get(name, nanos);
                if (reading == null) { tally.mismatches++; tally.compared++; if (tally.examples.size() < 5) tally.examples.add("sample#" + i + " " + name + " missing"); continue; }
                tally.check("sample#" + i, name, expected.getDouble(name), reading.value, quantum);
            }
        }
        return used;
    }

    private static Map<String, Object> toMap(JSONObject object) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Iterator<String> keys = object.keys(); keys.hasNext();) {
            String key = keys.next();
            map.put(key, convert(object.get(key)));
        }
        return map;
    }

    private static Object convert(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return toMap((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> list = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) list.add(convert(array.get(i)));
            return list;
        }
        if (value instanceof Number) return ((Number) value).doubleValue();
        return value;
    }

    private static String read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0;) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
}
