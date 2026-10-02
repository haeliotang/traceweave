package org.traceform.edge;

import android.app.Activity;
import android.app.job.JobScheduler;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import org.json.JSONObject;
import java.io.File;

/** Bounded real-service acquisition check; only aggregate diagnostics leave the sandbox. */
public final class SyntheticCollectionActivity extends Activity {
    private final Handler handler = new Handler();
    private boolean started, bound, bindingConnected, twoSessions;
    private final android.content.ServiceConnection connection = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) { bindingConnected = true; }
        @Override public void onServiceDisconnected(android.content.ComponentName name) { bindingConnected = false; }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String nonce = getIntent().getStringExtra("nonce");
        try { java.util.UUID.fromString(nonce); }
        catch (RuntimeException invalid) { finish(); return; }
        twoSessions = getIntent().getBooleanExtra("two_sessions", false);
        final boolean fullWindow = getIntent().getBooleanExtra("full_window", false);
        if (twoSessions && fullWindow) { finish(); return; }
        final int durationMillis = fullWindow ? 360000 : 45000;
        android.widget.TextView message = new android.widget.TextView(this);
        message.setText(fullWindow ? "正在验证连续采样\n6 分钟后自动停止" : "正在验证连续采样\n45 秒后自动停止"); setContentView(message);
        getSharedPreferences("interventions", MODE_PRIVATE).edit().putBoolean("enabled", false).commit();
        if (twoSessions) bound = bindService(new Intent(this, EdgeService.class), connection, BIND_AUTO_CREATE);
        startForegroundService(new Intent(this, EdgeService.class)); started = true;
        if (twoSessions) {
            handler.postDelayed(this::stopAcquisition, 20000);
            handler.postDelayed(() -> {
                if (!bindingConnected) { finish(); return; }
                startForegroundService(new Intent(this, EdgeService.class)); started = true;
            }, 23000);
        }
        handler.postDelayed(() -> {
            stopAcquisition();
            handler.postDelayed(() -> {
                try { summarize(nonce); }
                catch (Exception error) { android.util.Log.e("Traceform", "collection probe failed", error); }
                finish();
            }, 3000);
        }, durationMillis);
    }
    private void stopAcquisition() {
        if (bound) startService(new Intent(this, EdgeService.class).setAction(EdgeService.STOP_COLLECTION));
        else stopService(new Intent(this, EdgeService.class));
        started = false;
    }
    private void summarize(String nonce) throws Exception {
        int frames = 0, temperature = 0, batteryProxy = 0, continuous = 0, dimension = 0;
        int readyFrames = 0, consecutive = 0, longestContinuous = 0, lastBreakFrame = 0;
        int coverageBreaks = 0, clockBreaks = 0, intervalBreaks = 0, unexplainedBreaks = 0;
        int sessionBreaks = 0, invalidSessionStarts = 0;
        java.util.Set<String> sessions = new java.util.HashSet<>();
        JSONObject coverageChanges = new JSONObject();
        JSONObject previous = null;
        double sum = 0, min = Double.POSITIVE_INFINITY, max = 0;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(getDatabasePath("edge.sqlite").getPath(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor cursor = db.rawQuery("SELECT payload FROM frames ORDER BY ts", null)) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject(cursor.getString(0)); frames++;
                String session = row.getString("session_id");
                if (sessions.add(session) && row.optBoolean("continuous_from_previous")) invalidSessionStarts++;
                JSONObject source = row.getJSONObject("feature_provenance").getJSONObject("env_device_temp_c");
                if (source.optString("kind").equals("observed")) temperature++;
                JSONObject stress = row.getJSONObject("feature_provenance").getJSONObject("thermodynamic_battery_stress_index");
                if (stress.optString("kind").equals("proxy")) batteryProxy++;
                if (row.getJSONObject("csd").optBoolean("ready")) readyFrames++;
                if (row.optBoolean("continuous_from_previous")) {
                    consecutive++; longestContinuous = Math.max(longestContinuous, consecutive);
                    double dt = row.getDouble("dt_seconds"); continuous++;
                    sum += dt; min = Math.min(min, dt); max = Math.max(max, dt);
                }
                if (previous != null && !row.optBoolean("continuous_from_previous")) {
                    consecutive = 0; lastBreakFrame = frames;
                    java.util.Set<String> before = features(previous), after = features(row);
                    boolean sessionChanged = !row.getString("session_id").equals(previous.getString("session_id"));
                    if (sessionChanged) sessionBreaks++;
                    boolean coverageChanged = !before.equals(after);
                    if (coverageChanged) {
                        coverageBreaks++;
                        java.util.Set<String> changed = new java.util.HashSet<>(before);
                        changed.addAll(after);
                        for (String name : changed) if (before.contains(name) != after.contains(name))
                            coverageChanges.put(name, coverageChanges.optInt(name) + 1);
                    }
                    double dt = row.getDouble("dt_seconds");
                    boolean intervalInvalid = dt <= 0 || dt > 300;
                    if (intervalInvalid) intervalBreaks++;
                    double wallDt = (row.getLong("timestamp_epoch_ms") - previous.getLong("timestamp_epoch_ms")) / 1000.0;
                    boolean clockInvalid = wallDt <= 0 || Math.abs(wallDt - dt) > 1;
                    if (clockInvalid) clockBreaks++;
                    if (!sessionChanged && !coverageChanged && !intervalInvalid && !clockInvalid) unexplainedBreaks++;
                }
                previous = row;
                dimension = row.getJSONObject("csd").getInt("input_dimension");
            }
        }
        JSONObject report = new JSONObject().put("nonce", nonce).put("frames", frames)
            .put("two_sessions_requested", twoSessions).put("binding_established", bindingConnected)
            .put("collection_sessions", sessions.size()).put("session_breaks", sessionBreaks)
            .put("invalid_session_starts", invalidSessionStarts)
            .put("session_identity_valid", !twoSessions || (bindingConnected && sessions.size() == 2 && sessionBreaks == 1 && invalidSessionStarts == 0))
            .put("csd_ready_frames", readyFrames).put("longest_continuous_pairs", longestContinuous)
            .put("final_continuous_pairs", consecutive).put("last_break_frame", lastBreakFrame)
            .put("battery_stress_proxy_frames", batteryProxy)
            .put("temperature_observed_frames", temperature).put("continuous_pairs", continuous)
            .put("coverage_breaks", coverageBreaks).put("coverage_change_counts", coverageChanges)
            .put("clock_breaks", clockBreaks).put("interval_breaks", intervalBreaks)
            .put("unexplained_breaks", unexplainedBreaks)
            .put("csd_input_dimension", dimension).put("interventions_enabled", false)
            .put("scope", "bounded_real_foreground_service_sampling_not_long_term_or_scientific_acceptance")
            .put("dt_mean_seconds", continuous > 0 ? sum / continuous : JSONObject.NULL)
            .put("dt_min_seconds", continuous > 0 ? min : JSONObject.NULL)
            .put("dt_max_seconds", continuous > 0 ? max : JSONObject.NULL);
        NativeVerificationJob.write(new File(getFilesDir(), "collection_probe.json"), report);
    }
    private static java.util.Set<String> features(JSONObject row) throws Exception {
        java.util.Set<String> result = new java.util.HashSet<>();
        org.json.JSONArray names = row.getJSONArray("supplied_features");
        for (int index = 0; index < names.length(); index++) result.add(names.getString(index));
        return result;
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (started) stopService(new Intent(this, EdgeService.class));
        if (bound) { unbindService(connection); bound = false; }
        getSystemService(JobScheduler.class).cancel(WeeklyMaintenanceJob.JOB_ID);
        super.onDestroy();
    }
}
