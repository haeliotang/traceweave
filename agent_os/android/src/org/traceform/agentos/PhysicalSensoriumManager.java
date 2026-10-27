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

    private volatile float mAccelX = 0f, mAccelY = 0f, mAccelZ = Float.NaN;
    private volatile float mGyroX = 0f, mGyroY = 0f, mGyroZ = 0f;
    private volatile float mLightLux = Float.NaN;
    private volatile float mProximityCm = Float.NaN;
    private volatile float mTemperatureC = Float.NaN;
    private volatile float mBatteryLevel = Float.NaN;

    private long mAccelAt, mGyroAt, mLightAt, mProximityAt;
    private final MotionWindow mMotionWindow = new MotionWindow();

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
        mAccelAt = mGyroAt = mLightAt = mProximityAt = 0;
        mMotionWindow.clear();
        boolean accel = register(mAccelSensor), gyro = register(mGyroSensor);
        boolean light = register(mLightSensor), proximity = register(mProximitySensor);
        mIsMonitoring = accel || gyro || light || proximity;
    }

    private boolean register(Sensor sensor) {
        if (sensor == null) return false;
        try { return mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI); }
        catch (SecurityException error) { Log.w(TAG, "Sensor access unavailable"); return false; }
    }

    public synchronized void stopMonitoring() {
        if (mSensorManager != null) mSensorManager.unregisterListener(this);
        mIsMonitoring = false;
        mAccelAt = mGyroAt = mLightAt = mProximityAt = 0;
        mMotionWindow.clear();
        mLastContext = new JSONObject();
    }

    public boolean isMonitoring() {
        return mIsMonitoring;
    }

    @Override
    public synchronized void onSensorChanged(SensorEvent event) {
        if (!mIsMonitoring || event == null || event.sensor == null || event.values == null || event.values.length == 0) return;
        for (float value : event.values) if (Float.isNaN(value) || Float.isInfinite(value)) return;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                if (event.values.length < 3) return;
                mAccelAt = event.timestamp / 1000000L;
                mAccelX = event.values[0];
                mAccelY = event.values[1];
                mAccelZ = event.values[2];
                break;
            case Sensor.TYPE_GYROSCOPE:
                if (event.values.length < 3) return;
                mGyroAt = event.timestamp / 1000000L;
                mGyroX = event.values[0];
                mGyroY = event.values[1];
                mGyroZ = event.values[2];
                break;
            case Sensor.TYPE_LIGHT:
                mLightAt = event.timestamp / 1000000L;
                mLightLux = event.values[0];
                break;
            case Sensor.TYPE_PROXIMITY:
                mProximityAt = event.timestamp / 1000000L;
                mProximityCm = event.values[0];
                break;
        }
        if ((event.sensor.getType() == Sensor.TYPE_ACCELEROMETER || event.sensor.getType() == Sensor.TYPE_GYROSCOPE)
                && mAccelAt > 0 && mGyroAt > 0 && Math.abs(mAccelAt - mGyroAt) <= 1000) {
            try {
                double a = Math.sqrt(mAccelX*mAccelX + mAccelY*mAccelY + mAccelZ*mAccelZ);
                double g = Math.sqrt(mGyroX*mGyroX + mGyroY*mGyroY + mGyroZ*mGyroZ);
                mMotionWindow.add(event.timestamp / 1000000L, System.currentTimeMillis(), a, g);
            } catch (Exception error) { Log.w(TAG, "Motion window update failed"); }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private Object reading(float value, boolean available) {
        return available && !Float.isNaN(value) && !Float.isInfinite(value) ? value : JSONObject.NULL;
    }

    public synchronized JSONObject evaluateCurrentContext() {
        updateBatteryAndThermal();
        JSONObject telemetry = new JSONObject();
        long now = android.os.SystemClock.elapsedRealtime();
        boolean accel = mIsMonitoring && mAccelAt > 0 && now - mAccelAt <= 5000;
        boolean gyro = mIsMonitoring && mGyroAt > 0 && now - mGyroAt <= 5000;
        // Light/proximity are on-change sensors: an unchanged value remains valid
        // during this registration session. Its age is displayed, never invented.
        boolean light = mIsMonitoring && mLightAt > 0, proximity = mIsMonitoring && mProximityAt > 0;
        try {
            telemetry.put("accel_x", reading(mAccelX, accel)).put("accel_y", reading(mAccelY, accel)).put("accel_z", reading(mAccelZ, accel));
            telemetry.put("gyro_x", reading(mGyroX, gyro)).put("gyro_y", reading(mGyroY, gyro)).put("gyro_z", reading(mGyroZ, gyro));
            telemetry.put("light_lux", reading(mLightLux, light)).put("proximity_cm", reading(mProximityCm, proximity));
            telemetry.put("light_age_ms", light ? now - mLightAt : JSONObject.NULL);
            telemetry.put("temperature_c", reading(mTemperatureC, true)).put("battery_level", reading(mBatteryLevel, true));
            if (NativeAgentCore.isAvailable() && accel && gyro && light && proximity && !Float.isNaN(mTemperatureC) && !Float.isNaN(mBatteryLevel)) {
                JSONObject result = new JSONObject(NativeAgentCore.processSensorTelemetry(telemetry.toString()));
                java.util.Iterator<String> keys = telemetry.keys();
                while (keys.hasNext()) { String key = keys.next(); result.put(key, telemetry.get(key)); }
                result.put("monitoring", true).put("engine", "native_sensor_fusion");
                result.put("native_motion_estimate", result.optString("motion_state", "Unknown"));
                mLastContext = result;
            } else {
                mLastContext = SensorContext.fallback(telemetry, mIsMonitoring);
            }
            JSONObject change = mMotionWindow.lastChange();
            mLastContext.put("motion_state", accel && gyro ? mMotionWindow.state(now) : "Unknown")
                .put("motion_sample_count", mMotionWindow.sampleCount(now))
                .put("motion_method", "three_second_rule_window")
                .put("motion_change", change == null ? JSONObject.NULL : change);
        } catch (Exception error) { Log.e(TAG, "Sensor context unavailable", error); mLastContext = new JSONObject(); }
        return mLastContext;
    }

    private void updateBatteryAndThermal() {
        mBatteryLevel = mTemperatureC = Float.NaN;
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = mContext.registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    mBatteryLevel = ((float) level / (float) scale) * 100.0f;
                }
                int tempTenths = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                if (tempTenths != Integer.MIN_VALUE) mTemperatureC = tempTenths / 10.0f;
            }
        } catch (Exception ignored) {}
    }

    synchronized JSONObject lastMotionChange() throws Exception {
        return mIsMonitoring ? mMotionWindow.lastChange() : null;
    }

    public JSONObject getLastContext() {
        return mLastContext;
    }

    public synchronized boolean isVoiceWakeAllowed() {
        return !mIsMonitoring || mLightAt == 0 || mProximityAt == 0 || !(mProximityCm < 1 && mLightLux < 5);
    }
}
