package com.inceptless.traceweave.edge;

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
        int requestedSeconds = getIntent().getIntExtra("duration_seconds", 0);
        if (requestedSeconds != 0 && (requestedSeconds < 30 || requestedSeconds > 3600 || twoSessions || fullWindow)) { finish(); return; }
        final int durationMillis = requestedSeconds != 0 ? requestedSeconds * 1000 : fullWindow ? 360000 : 45000;
        // Visible over the lock screen without unlocking it, so the sensors stay in a foreground context.
        setShowWhenLocked(true); setTurnScreenOn(true);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.widget.TextView message = new android.widget.TextView(this);
        message.setText("正在验证连续采样\n" + (durationMillis >= 120000 ? durationMillis / 60000 + " 分钟" : durationMillis / 1000 + " 秒") + "后自动停止"); setContentView(message);
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
                // Scan the stored frames off the main thread, and always leave a receipt, even a failed one.
                new Thread(() -> {
                    try { CollectionSummary.summarize(SyntheticCollectionActivity.this, nonce, twoSessions, bindingConnected); }
                    catch (Throwable error) {
                        android.util.Log.e("Traceform", "collection probe failed", error);
                        try { NativeVerificationJob.write(new File(getFilesDir(), "collection_probe.json"),
                            new JSONObject().put("nonce", nonce).put("passed", false).put("error", error.toString())); }
                        catch (Exception ignored) { }
                    }
                    runOnUiThread(this::finish);
                }).start();
            }, 3000);
        }, durationMillis);
    }
    private void stopAcquisition() {
        if (bound) startService(new Intent(this, EdgeService.class).setAction(EdgeService.STOP_COLLECTION));
        else stopService(new Intent(this, EdgeService.class));
        started = false;
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (started) stopService(new Intent(this, EdgeService.class));
        if (bound) { unbindService(connection); bound = false; }
        getSystemService(JobScheduler.class).cancel(WeeklyMaintenanceJob.JOB_ID);
        super.onDestroy();
    }
}
