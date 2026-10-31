package com.inceptless.traceweave;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

final class LocalNotificationProvider implements CapabilityProvider {
    static final String CAPABILITY = "system.notification.post";
    static final String SCOPE = "system.notify";
    static final String CHANNEL_ID = "agent_os_tasks";
    static final String CHANNEL_NAME = "Agent OS 任务通知";

    private final Context context;
    private final NotificationManager notificationManager;

    LocalNotificationProvider(Context context) {
        this.context = context.getApplicationContext();
        this.notificationManager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Traceform OS 本地任务执行状态与推理结果通知");
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public String capability() {
        return CAPABILITY;
    }

    @Override
    public boolean supportsScope(String scope) {
        return SCOPE.equals(scope);
    }

    @Override
    public boolean isAvailable(String taskId, String scope) {
        if (!supportsScope(scope) || notificationManager == null || !notificationManager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        NotificationChannel channel = notificationManager.getNotificationChannel(CHANNEL_ID);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    @Override
    public String read(String taskId, String scope) {
        if (!isAvailable(taskId, scope)) {
            throw new IllegalStateException("Notification manager is unavailable");
        }

        long now = System.currentTimeMillis();
        int notificationId = taskId.hashCode() & Integer.MAX_VALUE;
        String notificationTitle = "Agent OS 任务通知";
        String notificationText = "任务 [" + taskId.substring(0, Math.min(8, taskId.length())) + "] 正在推进本地执行。";

        try {
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }

            builder.setContentTitle(notificationTitle)
                   .setContentText(notificationText)
                   .setSmallIcon(android.R.drawable.ic_dialog_info)
                   .setAutoCancel(true);

            notificationManager.notify(notificationId, builder.build());

            JSONObject result = new JSONObject();
            result.put("provider", "system.notification");
            result.put("channel", CHANNEL_ID);
            result.put("notificationId", notificationId);
            result.put("posted", true);
            result.put("status", "accepted_by_system");
            result.put("postedAt", now);
            result.put("title", notificationTitle);
            result.put("summary", notificationText);
            return result.toString();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to post system notification: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void releaseTask(String taskId) {}
}
