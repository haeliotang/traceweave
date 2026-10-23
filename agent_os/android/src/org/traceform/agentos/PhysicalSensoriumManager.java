package org.traceform.agentos;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import org.json.JSONObject;

/**
 * Direction 2: Physical World Grounding & Real-Time Sensor Fusion Manager.
 * Interfaces with Google Pixel 6a physical IMU, light, and proximity hardware.
 */
public final class PhysicalSensoriumManager implements SensorEventListener {
    private static final String TAG = "PhysicalSensorium";
    private static volatile PhysicalSensoriumManager sInstance;

    private final Context mContext;
    private final SensorManager mSensorManager;

    private final Sensor mAccelSensor;
    private final Sensor mGyroSensor;
    private final Sensor mLightSensor;
    private final Sensor mProximitySensor;

    private volatile float mAccelX = 0f, mAccelY = 0f, mAccelZ = 9.81f;
    private volatile float mGyroX = 0f, mGyroY = 0f, mGyroZ = 0f;
    private volatile float mLightLux = 150f;
    private volatile float mProximityCm = 8.0f;
    private volatile float mTemperatureC = 31.5f;
    private volatile float mBatteryLevel = 80.0f;

    private volatile JSONObject mLastContext = new JSONObject();
    private volatile boolean mIsMonitoring = false;

    public static PhysicalSensoriumManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (PhysicalSensoriumManager.class) {
                if (sInstance == null) {
                    sInstance = new PhysicalSensoriumManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private PhysicalSensoriumManager(Context context) {
        mContext = context;
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (mSensorManager != null) {
            mAccelSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mGyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        } else {
            mAccelSensor = null;
            mGyroSensor = null;
            mLightSensor = null;
            mProximitySensor = null;
        }
    }

    public synchronized void startMonitoring() {
        if (mIsMonitoring || mSensorManager == null) return;
        mIsMonitoring = true;

        if (mAccelSensor != null) {
            mSensorManager.registerListener(this, mAccelSensor, SensorManager.SENSOR_DELAY_UI);
        }
        if (mGyroSensor != null) {
            mSensorManager.registerListener(this, mGyroSensor, SensorManager.SENSOR_DELAY_UI);
        }
        if (mLightSensor != null) {
            mSensorManager.registerListener(this, mLightSensor, SensorManager.SENSOR_DELAY_UI);
        }
        if (mProximitySensor != null) {
            mSensorManager.registerListener(this, mProximitySensor, SensorManager.SENSOR_DELAY_UI);
        }
        Log.i(TAG, "Physical sensor monitoring started on Pixel 6a");
    }

    public synchronized void stopMonitoring() {
        if (!mIsMonitoring || mSensorManager == null) return;
        mIsMonitoring = false;
        mSensorManager.unregisterListener(this);
        Log.i(TAG, "Physical sensor monitoring stopped");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null) return;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                mAccelX = event.values[0];
                mAccelY = event.values[1];
                mAccelZ = event.values[2];
                break;
            case Sensor.TYPE_GYROSCOPE:
                mGyroX = event.values[0];
                mGyroY = event.values[1];
                mGyroZ = event.values[2];
                break;
            case Sensor.TYPE_LIGHT:
                mLightLux = event.values[0];
                break;
            case Sensor.TYPE_PROXIMITY:
                mProximityCm = event.values[0];
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * Executes real-time fusion via the Rust Native physical sensorium engine.
     */
    public JSONObject evaluateCurrentContext() {
        updateBatteryAndThermal();

        JSONObject telemetry = new JSONObject();
        try {
            telemetry.put("accel_x", mAccelX);
            telemetry.put("accel_y", mAccelY);
            telemetry.put("accel_z", mAccelZ);
            telemetry.put("gyro_x", mGyroX);
            telemetry.put("gyro_y", mGyroY);
            telemetry.put("gyro_z", mGyroZ);
            telemetry.put("light_lux", mLightLux);
            telemetry.put("proximity_cm", mProximityCm);
            telemetry.put("temperature_c", mTemperatureC);
            telemetry.put("battery_level", mBatteryLevel);

            if (NativeAgentCore.isAvailable()) {
                String resultJson = NativeAgentCore.processSensorTelemetry(telemetry.toString());
                mLastContext = new JSONObject(resultJson);
                return mLastContext;
            }
        } catch (Exception e) {
            Log.e(TAG, "Sensor telemetry fusion failed", e);
        }

        // Fallback
        JSONObject fb = new JSONObject();
        try {
            fb.put("motion_state", "Stationary");
            fb.put("ambient_environment", "IndoorLighting");
            fb.put("in_pocket", false);
            fb.put("voice_wake_allowed", true);
            fb.put("thermal_state", "Nominal");
            fb.put("inference_pacing_ms", 0);
            fb.put("context_summary", "Device stationary with indoor lighting.");
        } catch (Exception ignored) {}
        mLastContext = fb;
        return fb;
    }

    private void updateBatteryAndThermal() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = mContext.registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    mBatteryLevel = ((float) level / (float) scale) * 100.0f;
                }
                int tempTenths = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300);
                mTemperatureC = tempTenths / 10.0f;
            }
        } catch (Exception ignored) {}
    }

    public JSONObject getLastContext() {
        return mLastContext;
    }

    public boolean isVoiceWakeAllowed() {
        return mLastContext.optBoolean("voice_wake_allowed", true);
    }
}
