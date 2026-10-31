package com.inceptless.traceweave;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Visible, consented test controller. Its costs are included in both phases. */
public final class ControlledProbeService extends Service {
    private volatile boolean stopped;
    private volatile boolean measuring;
    private final java.util.concurrent.atomic.AtomicInteger screenOnEvents = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger powerConnectedEvents = new java.util.concurrent.atomic.AtomicInteger();
    private final android.content.BroadcastReceiver transitions = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!measuring) return;
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) screenOnEvents.incrementAndGet();
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) powerConnectedEvents.incrementAndGet();
        }
    };
    private PowerManager.WakeLock wake;
    private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
    static File file(Context context) { return new File(context.getFilesDir(), "controlled-probe.json"); }
    static void write(Context context, JSONObject state) throws Exception {
        AtomicFile file = new AtomicFile(file(context)); FileOutputStream stream = file.startWrite();
        try { stream.write(state.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
        catch (Exception error) { file.failWrite(stream); throw error; }
    }
    private Notification notice(String channel, String text) {
        return new Notification.Builder(this, channel).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Traceform controlled measurement").setContentText(text).build();
    }
    @Override public void onCreate() {
        super.onCreate();
        android.content.IntentFilter filter = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(transitions, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(transitions, filter);
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("controlled-running", "Controlled measurement", NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel("controlled-finished", "Measurement complete", NotificationManager.IMPORTANCE_DEFAULT));
        Notification notification = notice("controlled-running", "Waiting for physical USB unplug and screen off");
        if (Build.VERSION.SDK_INT >= 34) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(42, notification);
        wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traceform:controlled-probe");
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started.compareAndSet(false, true)) return START_NOT_STICKY;
        new Thread(() -> {
            JSONObject state = new JSONObject();
            try (AgentStore store = new AgentStore(this)) {
                state.put("format", "traceform.controlled-probe.v1").put("status", "await_unplug")
                    .put("foregroundController", true).put("sameWakeLockBothPhases", true)
                    .put("executionMode", "controlled_in_process_executor_not_background_scheduler")
                    .put("phaseDurationMs", BackgroundProbeJob.PHASE_MS).put("tasksSubmitted", 0).put("samples", new JSONArray());
                write(this, state);
                wake.acquire(35 * 60 * 1000L);
                long waitUntil = SystemClock.elapsedRealtime() + 30 * 60 * 1000;
                while (!stopped) {
                    JSONObject sample = BackgroundProbeJob.sample(this, "waiting");
                    if (sample.getInt("plugged") == 0 && !sample.getBoolean("interactive")) break;
                    if (SystemClock.elapsedRealtime() >= waitUntil) throw new IllegalStateException("USB unplug/screen-off wait expired");
                    Thread.sleep(1000);
                }
                if (stopped) throw new IllegalStateException("Measurement stopped");
                releaseWake();
                wake.acquire(25 * 60 * 1000L);
                measuring = true;
                state.put("status", "baseline"); state.getJSONArray("samples").put(BackgroundProbeJob.sample(this, "baseline_start")); write(this, state);
                long start = SystemClock.elapsedRealtime();
                for (int tick = 1; tick <= 10; tick++) {
                    sleepUntil(start + tick * 60000L);
                    state.getJSONArray("samples").put(BackgroundProbeJob.sample(this, tick == 10 ? "baseline_end" : "baseline_tick")); write(this, state);
                }
                state.put("status", "work"); state.getJSONArray("samples").put(BackgroundProbeJob.sample(this, "work_start")); write(this, state);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(42, notice("controlled-running", "Synthetic local task phase; no network or user files"));
                start = SystemClock.elapsedRealtime();
                for (int tick = 1; tick <= 10; tick++) {
                    AgentStore.Task task = BackgroundProbeJob.createWork(store);
                    new AgentExecutor(store, new CapabilityBroker(this, store)).execute(task.id, new java.util.concurrent.atomic.AtomicBoolean());
                    if (!AgentStore.COMPLETED.equals(store.getTask(task.id).status)) throw new IllegalStateException("Controlled local task failed");
                    state.put("tasksSubmitted", tick).put("tasksCompleted", tick).put("tasksStillActive", 0);
                    sleepUntil(start + tick * 60000L);
                    state.getJSONArray("samples").put(BackgroundProbeJob.sample(this, tick == 10 ? "work_end" : "work_tick")); write(this, state);
                }
                measuring = false;
                state.put("screenOnEvents", screenOnEvents.get()).put("powerConnectedEvents", powerConnectedEvents.get());
                state.put("status", "complete"); write(this, state);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(43,
                    notice("controlled-finished", "采样完成，可以接回 USB / Measurement complete; reconnect USB"));
            } catch (Exception error) {
                try { state.put("status", "failed").put("error", error.getClass().getSimpleName()); write(this, state); } catch (Exception ignored) {}
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(43, notice("controlled-finished", "测量已停止，请接回 USB / Reconnect USB to inspect the record"));
            } finally {
                releaseWake(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            }
        }, "controlled-synthetic-measurement").start();
        return START_NOT_STICKY;
    }
    private void sleepUntil(long deadline) throws Exception {
        while (!stopped && SystemClock.elapsedRealtime() < deadline) Thread.sleep(Math.max(1, Math.min(1000, deadline - SystemClock.elapsedRealtime())));
        if (stopped) throw new IllegalStateException("Measurement stopped");
    }
    private void releaseWake() { if (wake != null) synchronized (wake) { if (wake.isHeld()) wake.release(); } }
    @Override public void onDestroy() { stopped = true; measuring = false; unregisterReceiver(transitions); releaseWake(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
