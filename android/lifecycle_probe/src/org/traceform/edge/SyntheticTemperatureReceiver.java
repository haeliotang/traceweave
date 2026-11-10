// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Bounded direct sensor callback probe; no service, trajectory or actuator starts. */
public final class SyntheticTemperatureReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context receiverContext, Intent intent) {
        Context context = receiverContext.getApplicationContext();
        String nonce = intent.getStringExtra("nonce"); java.util.UUID.fromString(nonce);
        PendingResult pending = goAsync();
        HandlerThread thread = new HandlerThread("temperature-probe"); thread.start();
        Handler handler = new Handler(thread.getLooper());
        SensorManager manager = context.getSystemService(SensorManager.class);
        AtomicBoolean completed = new AtomicBoolean();
        class Probe implements SensorEventListener {
            private void finish(boolean valid, String reason) {
                if (!completed.compareAndSet(false, true)) return;
                try {
                    if (manager != null) manager.unregisterListener(this);
                    NativeVerificationJob.write(new File(context.getFilesDir(), "temperature_source.json"),
                        new JSONObject().put("passed", valid).put("nonce", nonce).put("reason", reason)
                            .put("collection_started", false).put("actuator_calls", 0)
                            .put("scope", "bounded_IMU_chip_temperature_callback_not_whole_device_temperature_or_full_acquisition"));
                } catch (Exception error) { android.util.Log.e("TraceformProbe", "temperature receipt failed", error); }
                finally { thread.quitSafely(); pending.finish(); }
            }
            @Override public void onSensorChanged(SensorEvent event) {
                FeatureReadings readings = new FeatureReadings();
                readings.deviceTemperature(event.values.length > 0 ? event.values[0] : Double.NaN, event.timestamp);
                FeatureReadings.Reading reading = readings.get("env_device_temp_c", android.os.SystemClock.elapsedRealtimeNanos());
                finish(reading != null && reading.kind.equals("observed"), "sensor_callback_checked");
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            void start() {
                try {
                    Sensor selected = null;
                    if (manager != null) for (Sensor sensor : manager.getSensorList(Sensor.TYPE_ALL))
                        if (FeatureReadings.isDeviceTemperature(sensor.getName(), sensor.getStringType())) { selected = sensor; break; }
                    if (selected == null) { finish(false, "source_unavailable"); return; }
                    if (!manager.registerListener(this, selected, SensorManager.SENSOR_DELAY_NORMAL, handler)) {
                        finish(false, "registration_rejected"); return;
                    }
                    handler.postDelayed(() -> finish(false, "callback_deadline"), 10000);
                } catch (RuntimeException unavailable) { finish(false, "registration_failed"); }
            }
        }
        Probe probe = new Probe();
        handler.post(probe::start);
    }
}
