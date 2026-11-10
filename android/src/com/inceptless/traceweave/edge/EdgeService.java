// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class EdgeService extends Service implements SensorEventListener {
    static final String STOP_COLLECTION = "com.inceptless.traceweave.edge.STOP_COLLECTION";
    private HandlerThread thread;
    private Handler handler;
    private SensorManager sensors;
    private volatile ModelEngine engine;
    private ModelEngine assetEngine;
    private final SpectrumForecastCache forecastCache = new SpectrumForecastCache();
    private SQLiteDatabase database;
    private final FeatureReadings observed = new FeatureReadings();
    private final NativeUsageSource usageSource = new NativeUsageSource();
    private final ReferenceFeatureSupplier referenceFeatures = new ReferenceFeatureSupplier();
    private final ServiceMetrics metrics = new ServiceMetrics();
    private long phaseMark;
    private void phase(int index) {
        long now = System.nanoTime();
        metrics.recordPhase(index, (now - phaseMark) / 1e6);
        phaseMark = now;
    }
    private int observedFeatureCount, derivedFeatureCount, proxyFeatureCount, missingFeatureCount;
    private final KinematicState kinematics = new KinematicState();
    private final CriticalSlowingDetector slowing = new CriticalSlowingDetector();
    private final ShadowComparisonWorker shadowComparisons = new ShadowComparisonWorker();
    private final NativeDeliveryWorker deliveries = new NativeDeliveryWorker();
    private String shadowToken;
    private String sessionId;
    private long frames;
    private SampleClock sampleClock;
    private WarmBucket previousWarm;
    private JSONObject previousCommitted;
    private long lastFrameMillis;
    private volatile boolean stopped;
    private volatile boolean destroyed;
    private boolean restartAfterStop, foreground;
    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel input, Parcel output, int flags) {
            if (code == INTERFACE_TRANSACTION) { output.writeString("com.inceptless.traceweave.edge.Canonical"); return true; }
            if (code != 1 && code != 2 && code != 3 && code != 4) return false;
            enforceCallingOrSelfPermission("com.inceptless.traceweave.edge.CANONICAL_INPUT", "canonical input requires matching signature");
            input.enforceInterface("com.inceptless.traceweave.edge.Canonical");
            try {
                if (code == 3) {
                    if (destroyed) throw new IllegalStateException("service unavailable");
                    JSONObject result = NightDisplay.capabilities(EdgeService.this)
                        .put("service_process_id", android.os.Process.myPid())
                        .put("service_collection_started", thread != null && !stopped);
                    output.writeNoException(); output.writeString(result.toString());
                    return true;
                }
                if (code == 2 || code == 4) {
                    String timezone = input.readString();
                    long instant = input.readLong(), step = input.readLong();
                    int horizon = input.readInt(), runs = input.readInt();
                    long seed = input.readLong();
                    ModelEngine queryModel = engine;
                    if (destroyed || queryModel == null) throw new IllegalStateException("service unavailable");
                    int maxDwell = 0;
                    boolean[] risk = null;
                    if (code == 4) {
                        maxDwell = input.readInt();
                        String[] selected = input.createStringArray();
                        if (maxDwell < 1 || selected == null || selected.length > queryModel.tokens.length)
                            throw new IllegalArgumentException("bounded explicit comparison states required");
                        risk = new boolean[queryModel.tokens.length];
                        for (String token : selected) {
                            int index = java.util.Arrays.asList(queryModel.tokens).indexOf(token);
                            if (index < 0 || risk[index]) throw new IllegalArgumentException("unknown or duplicate comparison state");
                            risk[index] = true;
                        }
                    }
                    HistoryForecast.validateRequest(queryModel.tokens.length, timezone, instant, step, horizon, runs);
                    JSONObject result;
                    File historyPath = getDatabasePath("edge.sqlite");
                    if (!historyPath.exists()) {
                        result = new JSONObject().put("available", false).put("reason", "no_retained_history")
                            .put("history_records", 0).put("excluded_history_records", 0).put("eligible_pairs", 0)
                            .put("model_version", queryModel.modelVersion).put("model_sha256", queryModel.modelDigest)
                            .put("normalizer_sha256", queryModel.normalizerDigest).put("timezone", timezone)
                            .put("as_of_epoch_ms", instant).put("eligible_for_intervention", false);
                    } else {
                        try (SQLiteDatabase history = SQLiteDatabase.openDatabase(historyPath.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                            result = HistoryForecast.run(history, queryModel.tokens, queryModel.modelVersion, queryModel.modelDigest,
                                queryModel.normalizerDigest, timezone, instant, step, horizon, runs, seed, forecastCache, maxDwell, risk);
                        }
                    }
                    result.put("service_process_id", android.os.Process.myPid()).put("service_collection_started", thread != null && !stopped).put("service_collection_stopped", stopped);
                    output.writeNoException(); output.writeString(result.toString());
                    return true;
                }
                double[] raw = input.createDoubleArray(), velocity = input.createDoubleArray();
                JSONObject result;
                synchronized (EdgeService.this) {
                    if (destroyed || engine == null) throw new IllegalStateException("service unavailable");
                    result = engine.process(raw, velocity);
                }
                output.writeNoException(); output.writeString(result.toString());
            } catch (Exception error) { output.writeException(new IllegalArgumentException(error)); }
            return true;
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        try {
            assetEngine = new ModelEngine(this);
            engine = new NativeModelRegistry(new File(getFilesDir(), "model_registry"), assetEngine).active();
        }
        catch (Exception error) {
            android.util.Log.e("Traceform", "model initialization failed", error);
            stopSelf();
        }
    }

    private void beginCollection() {
        if (destroyed || engine == null) return;
        if (thread != null) { if (stopped) restartAfterStop = true; return; }
        sessionId = java.util.UUID.randomUUID().toString();
        observed.reset();
        usageSource.reset();
        referenceFeatures.reset();
        stopped = false;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("trajectory", "本机轨迹采集", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(11, new Notification.Builder(this, "trajectory").setContentTitle("Traceform 正在采集")
            .setContentText("数据保存在本机，可随时停止").setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(open).build());
        foreground = true;
        thread = new HandlerThread("traceform-sensors"); thread.start(); handler = new Handler(thread.getLooper());
        initializeCollection();
    }

    private void initializeCollection() {
        try {
            write("parity.json", assetEngine.verifyParity(this).put("model_version", assetEngine.modelVersion));
            write("active_model.json", new JSONObject().put("model_version", engine.modelVersion)
                .put("model_sha256", engine.modelDigest).put("normalizer_sha256", engine.normalizerDigest)
                .put("loaded_at_service_creation", true).put("asset_parity_model_version", assetEngine.modelVersion));
            database = openOrCreateDatabase("edge.sqlite", MODE_PRIVATE, null);
            database.setForeignKeyConstraintsEnabled(true);
            database.enableWriteAheadLogging();
            database.execSQL("CREATE TABLE IF NOT EXISTS frames (ts INTEGER PRIMARY KEY, payload TEXT)");
            database.execSQL("CREATE TABLE IF NOT EXISTS summaries (bucket INTEGER, token TEXT, n INTEGER, PRIMARY KEY(bucket,token))");
            database.execSQL("CREATE TABLE IF NOT EXISTS warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)");
            WarmTrajectory.backfillOnce(database);
            NativeFeedbackLedger.censorRestart(new File(getFilesDir(), "intervention_trials.sqlite"));
            ColdTrajectory.initialize(database);
            ColdTrajectory.register(database, engine.modelVersion, engine.normalizerDigest, engine.tokens, engine.coldBundle());
            ColdTrajectory.backfillOnce(database);
            WeeklyMaintenanceJob.schedule(this);
            write("benchmark.json", engine.benchmark());
            sensors = getSystemService(SensorManager.class);
            for (int type : new int[]{Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE}) {
                Sensor sensor = sensors.getDefaultSensor(type);
                if (sensor != null) sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
            }
            for (Sensor sensor : sensors.getSensorList(Sensor.TYPE_ALL)) {
                if (FeatureReadings.isDeviceTemperature(sensor.getName(), sensor.getStringType())) {
                    try { sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler); }
                    catch (SecurityException denied) { android.util.Log.w("Traceform", "IMU temperature access denied"); }
                    break;
                }
            }
            sampleClock = new SampleClock(SystemClock.elapsedRealtime());
            handler.post(sample);
        } catch (Exception error) {
            android.util.Log.e("Traceform", "initialization failed", error);
            restartAfterStop = false;
            stopCollection();
            stopSelf();
        }
    }

    private void write(String name, JSONObject value) throws Exception {
        File temporary = new File(getFilesDir(), name + ".tmp"), destination = new File(getFilesDir(), name);
        Files.write(temporary.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
        if (!temporary.renameTo(destination)) throw new IllegalStateException("atomic file replacement failed");
    }

    private void refreshModel() throws Exception {
        ModelEngine loaded = new NativeModelRegistry(new File(getFilesDir(),"model_registry"),assetEngine)
            .activeIfChanged(engine.modelVersion,database);
        if (loaded == null) return;
        synchronized (EdgeService.this) {
            engine = loaded;
            kinematics.reset(); slowing.reset(); shadowComparisons.invalidate();
            previousWarm = null; previousCommitted = null; shadowToken = null;
        }
        write("active_model.json",new JSONObject().put("model_version",loaded.modelVersion)
            .put("model_sha256",loaded.modelDigest).put("normalizer_sha256",loaded.normalizerDigest)
            .put("loaded_at_service_creation",false).put("loaded_epoch_ms",System.currentTimeMillis())
            .put("asset_parity_model_version",assetEngine.modelVersion));
    }

    private final Runnable sample = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            long tickStartNanos = System.nanoTime();
            phaseMark = tickStartNanos;
            try {
                refreshModel();
                phase(0);
                long now = System.currentTimeMillis(), nanos = SystemClock.elapsedRealtimeNanos();
                observed.clock(now, nanos);
                observed.motion(nanos);
                observed.windows(nanos);
                NativeUsageSource.Counts usage = usageSource.sample(EdgeService.this, now, nanos);
                observed.put("app_switch_rate", usage.resumes, "derived",
                    "latest120_usage_events_within24h_activity_resumed_count_capped50_not_frequency", usage.timestampNanos, 65000000000L);
                observed.put("unlock_burst_count", usage.interactiveChecks, "proxy",
                    "latest120_usage_events_within24h_screen_interactive_or_keyguard_hidden_capped20_not_unique_unlocks", usage.timestampNanos, 65000000000L);
                observed.put("memory_pressure_stall_ratio", NativeMemoryPressure.read(), "derived",
                    "kernel_memory_psi_some_avg10_percent_divided_by_100_not_process_specific", nanos, 5000000000L);
                BatteryManager batteryManager = getSystemService(BatteryManager.class);
                int batteryCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                observed.put("battery_current_ua", batteryCurrent == Integer.MIN_VALUE ? Double.NaN : batteryCurrent,
                    "observed", "battery_current_now_microamperes", nanos, 5000000000L);
                PowerManager power = getSystemService(PowerManager.class);
                observed.put("screen_on_ratio", power.isInteractive() ? 1 : 0,
                    "proxy", "instant_interactive_state_not_window_ratio", nanos, 5000000000L);
                AudioManager audio = getSystemService(AudioManager.class);
                boolean headset = false;
                for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int type = device.getType();
                    if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                            || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) headset = true;
                }
                observed.put("audio_immersion_state", headset ? 1 : 0, "proxy",
                    "connected_wired_or_a2dp_output_not_playback_or_attention", nanos, 5000000000L);
                ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
                NativeWifiSource.Reading wifi = NativeWifiSource.read(connectivity);
                observed.wifiRssi(wifi.rssiDbm, nanos);
                observed.put("conn_wifi_connected", wifi.transportPresent ? 1 : 0, "observed",
                    "available_wifi_transport_network_not_internet_validation", nanos, 5000000000L);
                Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery != null) {
                    int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    observed.put("device_battery_fraction", level >= 0 && scale > 0 ? level / (double) scale : Double.NaN, "derived", "battery_broadcast", nanos, 5000000000L);
                    observed.put("device_is_plugged", battery.hasExtra(BatteryManager.EXTRA_PLUGGED) ? (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ? 1.0 : 0.0) : Double.NaN, "derived", "battery_broadcast", nanos, 5000000000L);
                    observed.put("device_is_charging", battery.hasExtra(BatteryManager.EXTRA_STATUS) ? (battery.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_CHARGING ? 1.0 : 0.0) : Double.NaN, "derived", "battery_broadcast", nanos, 5000000000L);
                    // Battery temperature is retained separately: it is not device temperature.
                }
                double voltageMv = battery != null && battery.hasExtra(BatteryManager.EXTRA_VOLTAGE)
                    ? battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) : Double.NaN;
                observed.electrical(voltageMv, batteryCurrent == Integer.MIN_VALUE ? Double.NaN : batteryCurrent, nanos);
                FeatureReadings.Reading fraction = observed.get("device_battery_fraction", nanos);
                observed.batteryStress(voltageMv, batteryCurrent == Integer.MIN_VALUE ? Double.NaN : batteryCurrent,
                    fraction == null || battery == null ? Double.NaN : fraction.value, nanos);
                // Parity-tested formulas over the inputs gathered above; recorded as derived or proxy readings.
                phase(1);
                ReferenceObservation.Inputs reference = new ReferenceObservation.Inputs();
                reference.wallMillis = now; reference.nanos = nanos;
                reference.screenState = power.isInteractive() ? "ON" : "OFF";
                reference.usage = usage;
                reference.voltageMv = voltageMv;
                reference.wifiRssiDbm = wifi.rssiDbm;
                reference.headsetConnected = headset;
                referenceFeatures.supply(observed, reference);
                phase(2);
                double[] raw = engine.medians.clone();
                boolean[] coverage = new boolean[raw.length];
                JSONArray supplied = new JSONArray(), missing = new JSONArray();
                JSONObject provenance = new JSONObject();
                observedFeatureCount = 0; derivedFeatureCount = 0; proxyFeatureCount = 0;
                for (int index = 0; index < raw.length; index++) {
                    String name = engine.names[index];
                    FeatureReadings.Reading reading = observed.get(name, nanos);
                    if (reading == null) {
                        missing.put(name);
                        provenance.put(name, new JSONObject().put("kind", "missing").put("policy", "median_imputation")
                            .put("reason", StaticallyUnobservableFeatures.missingReason(name)));
                    } else {
                        raw[index] = reading.value; supplied.put(name);
                        coverage[index] = true;
                        if (reading.kind.equals("observed")) observedFeatureCount++;
                        else if (reading.kind.equals("proxy")) proxyFeatureCount++;
                        else derivedFeatureCount++;
                        provenance.put(name, new JSONObject().put("kind", reading.kind).put("source", reading.source)
                            .put("source_age_ms", (nanos - reading.timestampNanos) / 1000000.0)
                            .put("max_age_ms", reading.maxAgeNanos / 1000000.0));
                    }
                }
                missingFeatureCount = missing.length();
                double[] normalized = NativeCore.normalize(raw, engine.medians, engine.iqrs);
                KinematicState.Result motion = kinematics.update(normalized, coverage, nanos, engine.alpha, now);
                JSONObject payload = engine.process(raw, motion.velocity).put("timestamp_epoch_ms", now)
                    .put("continuous_velocity", new JSONArray(motion.velocity))
                    .put("dt_seconds", motion.dt).put("continuous_from_previous", motion.continuous)
                    .put("timestamp_utc", java.time.Instant.ofEpochMilli(now).toString())
                    .put("elapsed_realtime_nanos", nanos).put("session_id", sessionId)
                    .put("source_mode", "native_sensor_subset").put("supplied_features", supplied).put("missing_features", missing)
                    .put("feature_provenance", provenance)
                    .put("missing_policy", "median_imputation_not_measurement").put("eligible_for_intervention", false);
                CriticalSlowingDetector.Result signal = slowing.updateContinuous(payload.getString("state_token"),
                    normalized, motion.continuous);
                payload.put("csd_input_space", "continuous");
                payload.put("dwell_steps", signal.dwellSteps).put("csd_dwell_gate", signal.tipping && signal.dwellSteps > 30)
                    .put("csd", new JSONObject().put("ready", signal.ready).put("tipping", signal.tipping)
                        .put("spatial_variance", signal.variance).put("lag1_ar", signal.lag1)
                        .put("composite_score", signal.score)
                        .put("input_dimension", normalized.length)
                        .put("evidence_scope", "normalized_continuous_CSD_diagnostic_not_biological_identification_or_actuation_authority"));
                phase(3);
                database.beginTransaction();
                WarmBucket warm = WarmBucket.next(now, motion.continuous, previousWarm);
                try {
                    database.execSQL("INSERT OR REPLACE INTO frames VALUES (?,?)", new Object[]{now, payload.toString()});
                    database.execSQL("INSERT OR IGNORE INTO summaries VALUES (?,?,0)",
                        new Object[]{now / 30000 * 30000, payload.getString("state_token")});
                    database.execSQL("UPDATE summaries SET n=n+1 WHERE bucket=? AND token=?",
                        new Object[]{now / 30000 * 30000, payload.getString("state_token")});
                    WarmTrajectory.append(database, payload, warm);
                    ColdTrajectory.append(database, previousCommitted, payload);
                    if (frames % 600 == 0) {
                        database.execSQL("DELETE FROM frames WHERE ts < ?", new Object[]{now - 48L * 3600000});
                        database.execSQL("DELETE FROM summaries WHERE bucket < ?", new Object[]{now - 30L * 86400000});
                        database.execSQL("DELETE FROM warm_trajectory WHERE ts < ?", new Object[]{now - 30L * 86400000});
                    }
                    database.setTransactionSuccessful();
                } finally { database.endTransaction(); }
                phase(4);
                previousWarm = warm;
                previousCommitted = payload;
                if (frames % 600 == 0) write("cold_receipt.json", ColdTrajectory.export(database, new File(getFilesDir(), "cold_statistics.json.gz")));
                frames++;
                JSONArray feedback = NativeFeedbackLedger.observe(new File(getFilesDir(), "intervention_trials.sqlite"), payload);
                if (feedback.length() > 0) write("feedback_receipt.json", new JSONObject().put("outcomes", feedback)
                    .put("evidence_scope", "observational_trial_outcomes_not_individual_causal_effect"));
                scheduleComparison(now, motion.continuous, payload.getString("state_token"), signal, NativeFeedbackLedger.boundFrame(payload));
                lastFrameMillis = now;
                JSONObject coldCounts = ColdTrajectory.countSummary(database);
                write("status.json", new JSONObject().put("frames", frames).put("last_frame_epoch_ms", now)
                    .put("session_id", sessionId).put("last_timestamp_utc", java.time.Instant.ofEpochMilli(now).toString())
                    .put("elapsed_realtime_nanos", nanos).put("dt_seconds", motion.dt)
                    .put("continuous_from_previous", motion.continuous)
                    .put("warm_bucket_epoch_ms", warm.bucket)
                    .put("warm_trajectory_rows", android.database.DatabaseUtils.queryNumEntries(database, "warm_trajectory"))
                    .put("warm_migration_scope", WarmTrajectory.MIGRATION_SCOPE)
                    .put("cold_transitions",coldCounts.getLong("retained_version_transitions")).put("cold_counts",coldCounts)
                    .put("skipped_sample_ticks", sampleClock.skipped)
                    .put("observed_feature_count", observedFeatureCount).put("derived_feature_count", derivedFeatureCount)
                    .put("proxy_feature_count", proxyFeatureCount)
                    .put("supplied_feature_count", supplied.length()).put("missing_feature_count", missingFeatureCount).put("running", true));
            } catch (Exception error) { kinematics.reset(); slowing.reset(); shadowComparisons.invalidate(); android.util.Log.e("Traceform", "sampling failed", error); }
            phase(5);
            metrics.recordTick((System.nanoTime() - tickStartNanos) / 1e6);
            recordServiceMetrics();
            if (!stopped) {
                long now = SystemClock.elapsedRealtime();
                handler.postDelayed(this, sampleClock.next(now) - now);
            }
        }
    };

    /** Whole-service cost: sample duration histogram and process memory, refreshed on a slow cadence. */
    private void recordServiceMetrics() {
        long ticks = metrics.ticks();
        if (ticks % 30 == 1) {
            Runtime runtime = Runtime.getRuntime();
            metrics.recordMemory(android.os.Debug.getPss(), (runtime.totalMemory() - runtime.freeMemory()) / 1024,
                android.os.Debug.getNativeHeapAllocatedSize() / 1024);
        }
        if (ticks % 10 == 0) writeServiceMetrics(true);
    }

    private JSONObject phaseJson(boolean maximum) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < ServiceMetrics.PHASES.length; i++)
            result.put(ServiceMetrics.PHASES[i], finite(maximum ? metrics.phaseMaxMillis(i) : metrics.phaseMeanMillis(i)));
        return result;
    }

    private static Object finite(double value) { return Double.isFinite(value) ? (Object) value : JSONObject.NULL; }

    private void writeServiceMetrics(boolean running) {
        try {
            JSONArray histogram = new JSONArray();
            for (long count : metrics.histogram()) histogram.put(count);
            JSONArray edges = new JSONArray();
            for (double edge : ServiceMetrics.EDGES_MILLIS) edges.put(edge);
            write("service_metrics.json", new JSONObject().put("running", running).put("process_id", android.os.Process.myPid())
                .put("ticks", metrics.ticks()).put("ticks_over_period", metrics.ticksOverBudget())
                .put("skipped_sample_ticks", sampleClock == null ? 0 : sampleClock.skipped)
                .put("tick_ms_mean", finite(metrics.meanMillis())).put("tick_ms_p50_upper_bound", finite(metrics.percentileMillis(0.50)))
                .put("tick_ms_p95_upper_bound", finite(metrics.percentileMillis(0.95))).put("tick_ms_p99_upper_bound", finite(metrics.percentileMillis(0.99)))
                .put("tick_ms_max", finite(metrics.maxMillis())).put("tick_ms_bucket_edges", edges).put("tick_ms_histogram", histogram)
                .put("phase_ms_mean", phaseJson(false)).put("phase_ms_max", phaseJson(true))
                .put("memory_samples", metrics.memorySamples()).put("pss_kb_first", metrics.pssKbFirst())
                .put("pss_kb_last", metrics.pssKbLast()).put("pss_kb_max", metrics.pssKbMax())
                .put("java_heap_kb_last", metrics.javaHeapKbLast()).put("java_heap_kb_max", metrics.javaHeapKbMax())
                .put("native_heap_kb_last", metrics.nativeHeapKbLast()).put("native_heap_kb_max", metrics.nativeHeapKbMax())
                .put("scope", "whole_service_process_tick_cost_and_memory_not_core_compute_only"));
        } catch (Exception error) { android.util.Log.e("Traceform", "service metrics failed", error); }
    }

    private void scheduleShadowComparison(long instant, boolean continuous, String token, CriticalSlowingDetector.Result signal) {
        scheduleComparison(instant,continuous,token,signal,null);
    }
    private void scheduleComparison(long instant, boolean continuous, String token, CriticalSlowingDetector.Result signal, JSONObject trigger) {
        if (!continuous || !token.equals(shadowToken)) shadowComparisons.invalidate();
        shadowToken = token;
        boolean gate = continuous && signal.tipping && signal.dwellSteps > 30 && !stopped && !destroyed;
        shadowComparisons.update(SystemClock.elapsedRealtime(), gate, () -> {
            JSONObject receipt;
            try (SQLiteDatabase history = SQLiteDatabase.openDatabase(getDatabasePath("edge.sqlite").getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                boolean[] selected = new boolean[engine.tokens.length];
                selected[java.util.Arrays.asList(engine.tokens).indexOf(token)] = true;
                receipt = HistoryForecast.run(history, engine.tokens, engine.modelVersion, engine.modelDigest,
                    engine.normalizerDigest, java.time.ZoneId.systemDefault().getId(), instant, 30000, 120, 300, 42,
                    forecastCache, 10, selected);
                if (receipt.optBoolean("available") && (receipt.getLong("history_end_epoch_ms") != instant
                        || !token.equals(receipt.getString("initial_state")))) {
                    receipt.put("available", false).put("reason", "trigger_history_binding_mismatch");
                    receipt.remove("counterfactual");
                }
                receipt.remove("occupancy_probabilities");
                if (receipt.has("counterfactual")) receipt.getJSONObject("counterfactual").remove("scenario_occupancy_probabilities");
            } catch (Exception error) {
                receipt = new JSONObject().put("available", false).put("reason", error.getClass().getSimpleName());
            }
            return receipt.put("mode", "shadow").put("trigger_state", token).put("trigger_epoch_ms", instant)
                .put("eligible_for_intervention", false).put("actuator_calls", 0)
                .put("evidence_scope", "automatic_CSD_dwell_model_comparison_not_execution_authority").toString();
        }, value -> {
            try {
                JSONObject receipt = new JSONObject(value);
                if (receipt.optBoolean("available") && receipt.has("counterfactual")) {
                    boolean active = trigger != null && !stopped && !destroyed
                        && SystemClock.elapsedRealtimeNanos() - trigger.getLong("elapsed_realtime_nanos") <= 5000000000L
                        && getSharedPreferences("interventions",MODE_PRIVATE).getBoolean("enabled",false)
                        && getSharedPreferences("interventions",MODE_PRIVATE).getString("mode","shadow").equals("active")
                        && !new File(getFilesDir(),".silent").exists();
                    receipt.put("mode",active ? "active" : "shadow").put("execution_authorized",active);
                    JSONObject proposal = ShadowTrialLedger.propose(new File(getFilesDir(), "intervention_trials.sqlite"),
                        receipt.getJSONObject("counterfactual"), engine.modelVersion, engine.modelDigest,
                        engine.normalizerDigest, sessionId, token, receipt.getString("timezone"), instant,
                        java.util.concurrent.ThreadLocalRandom.current().nextDouble(),trigger,active);
                    if (proposal != null) {
                        receipt.put("policy", proposal);
                        if (proposal.optString("status").equals("queued")) {
                            receipt.put("actuator_calls",JSONObject.NULL);
                            queueDelivery(proposal);
                        }
                    }
                }
                write("shadow_comparison.json", receipt);
            }
            catch (Exception error) { throw new IllegalStateException("shadow receipt persistence failed", error); }
        });
    }
    private void queueDelivery(JSONObject proposal) throws Exception {
        File path=new File(getFilesDir(),"intervention_trials.sqlite");
        String id=proposal.getString("trial_id"),action=proposal.getString("action");
        boolean accepted=deliveries.submit(() -> {
            JSONObject result;
            try {
                result=stopped || destroyed ? new JSONObject().put("delivered",false).put("eligible_for_outcome",false)
                    .put("reason","collection_stopped") : NativeMicroActuator.execute(this,action);
            } catch(Exception error) {
                result=new JSONObject().put("delivered",false).put("eligible_for_outcome",false)
                    .put("reason","execution_exception").put("error_type",error.getClass().getSimpleName());
            }
            return result.toString();
        }, value -> {
            try { NativeTrialDelivery.complete(path,id,new JSONObject(value)); }
            catch(Exception error){throw new IllegalStateException("delivery receipt persistence failed",error);}
        });
        if(!accepted)NativeTrialDelivery.complete(path,id,new JSONObject().put("delivered",false)
            .put("eligible_for_outcome",false).put("reason","delivery_worker_unavailable"));
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) observed.gyroscope(event.values, event.timestamp);
        else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) observed.accelerometer(event.values, event.timestamp);
        else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) observed.magnetic(event.values, event.timestamp);
        else if (event.sensor.getType() == Sensor.TYPE_LIGHT) observed.light(event.values[0], event.timestamp);
        else if (event.sensor.getType() == Sensor.TYPE_PRESSURE) observed.pressure(event.values[0], event.timestamp);
        else if (FeatureReadings.isDeviceTemperature(event.sensor.getName(), event.sensor.getStringType()))
            observed.deviceTemperature(event.values.length > 0 ? event.values[0] : Double.NaN, event.timestamp);
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP_COLLECTION.equals(intent.getAction())) {
            restartAfterStop = false;
            stopCollection();
            stopSelf();
            return START_NOT_STICKY;
        }
        beginCollection(); return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    private void stopCollection() {
        boolean alreadyStopped = stopped;
        stopped = true;
        shadowComparisons.invalidate(); shadowToken = null;
        deliveries.stop();
        try { NativeTrialDelivery.censorStop(new File(getFilesDir(),"intervention_trials.sqlite")); }
        catch(Exception error){android.util.Log.e("Traceform","pending trial stop censor failed",error);}
        if (alreadyStopped) return;
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (sensors != null) sensors.unregisterListener(this);
        if (handler != null) {
            HandlerThread endingThread = thread;
            handler.post(() -> {
                try { if (database != null && database.isOpen()) write("cold_receipt.json", ColdTrajectory.export(database, new File(getFilesDir(), "cold_statistics.json.gz"))); }
                catch (Exception error) { android.util.Log.e("Traceform", "cold checkpoint failed", error); }
                if (database != null) database.close();
                writeServiceMetrics(false);
                try { write("status.json", new JSONObject().put("running", false).put("frames", frames).put("last_frame_epoch_ms", lastFrameMillis)
                    .put("observed_feature_count", observedFeatureCount).put("derived_feature_count", derivedFeatureCount).put("proxy_feature_count", proxyFeatureCount).put("missing_feature_count", missingFeatureCount)); } catch (Exception ignored) {}
                endingThread.quitSafely();
                new Handler(getMainLooper()).post(() -> {
                    thread = null; handler = null; sensors = null; database = null;
                    previousWarm = null; previousCommitted = null; kinematics.reset(); slowing.reset();
                    boolean restart = restartAfterStop; restartAfterStop = false;
                    if (restart && !destroyed) beginCollection();
                });
            });
        }
    }
    @Override public void onDestroy() {
        destroyed = true;
        deliveries.close();
        shadowComparisons.close();
        forecastCache.close();
        restartAfterStop = false;
        stopCollection();
        super.onDestroy();
    }
}
