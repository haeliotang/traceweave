package org.traceform.edge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import java.io.File;
import org.json.JSONObject;

final class NativeMicroActuator {
    static JSONObject execute(Context context, String action) throws Exception {
        if (!action.equals("none") && !java.util.Arrays.asList(ContextualPolicy.ACTIONS).contains(action))
            throw new IllegalArgumentException("supported micro action required");
        if (!context.getSharedPreferences("interventions", Context.MODE_PRIVATE).getBoolean("enabled", false)
                || !context.getSharedPreferences("interventions", Context.MODE_PRIVATE).getString("mode","shadow").equals("active")
                || new File(context.getFilesDir(), ".silent").exists()) return denied("disabled_or_silent");
        if (action.equals("none")) return acknowledged("randomized_no_action", "authorized_randomized_control_no_actuator_call");
        if (action.equals("pulse")) {
            if (!context.getSharedPreferences("interventions", Context.MODE_PRIVATE).getBoolean("haptics_enabled", false)
                    || context.checkSelfPermission("android.permission.VIBRATE") != PackageManager.PERMISSION_GRANTED)
                return denied("haptics_disabled_or_permission_denied");
            Vibrator vibrator = context.getSystemService(Vibrator.class);
            if (vibrator == null || !vibrator.hasVibrator()) return denied("vibrator_unavailable");
            vibrator.vibrate(VibrationEffect.createOneShot(100,VibrationEffect.DEFAULT_AMPLITUDE));
            return acknowledged("vibration_api_acknowledged", "vibration_API_ack_not_physical_or_human_response");
        }
        if (action.equals("notification")) {
            NotificationManager notifications = context.getSystemService(NotificationManager.class);
            if (notifications == null || !notifications.areNotificationsEnabled()) return denied("notifications_unavailable");
            notifications.createNotificationChannel(new NotificationChannel("micro", "活动提醒", NotificationManager.IMPORTANCE_DEFAULT));
            if (notifications.getNotificationChannel("micro").getImportance() == NotificationManager.IMPORTANCE_NONE)
                return denied("notification_channel_blocked");
            notifications.notify(34715,new Notification.Builder(context,"micro").setContentTitle("Traceform")
                .setContentText("停一刻，留意当前的活动。").setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true).build());
            boolean posted = false;
            for (android.service.notification.StatusBarNotification item : notifications.getActiveNotifications())
                if (item.getId() == 34715) posted = true;
            return posted ? acknowledged("notification_posted", "notification_posted_not_seen_or_human_response") : denied("notification_not_verified");
        }
        if (action.equals("color_temperature")) {
            JSONObject capability = NightDisplay.capabilities(context);
            if (!capability.optBoolean("write_secure_settings_granted")
                    || capability.isNull("config_nightDisplayColorTemperatureMin")) return denied("color_permission_or_capability_unavailable");
            int original = Settings.Secure.getInt(context.getContentResolver(),"night_display_color_temperature");
            return NightDisplay.adjust(context,Math.max(capability.getInt("config_nightDisplayColorTemperatureMin"),original-100));
        }
        if (context.checkSelfPermission("android.permission.MODIFY_AUDIO_SETTINGS") != PackageManager.PERMISSION_GRANTED)
            return denied("audio_permission_denied");
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquireVolume(context.getFilesDir())) {
            VolumeJournal journal = new VolumeJournal(new File(context.getFilesDir(),"volume_pending.bin"));
            if (journal.exists()) return denied("pending_volume_recovery_required");
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null || audio.isVolumeFixed()) return denied("music_volume_unavailable");
            VolumeAdjustment.Result result = VolumeAdjustment.run(control(context,audio,journal),audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),true);
            return volumeReceipt(result);
        }
    }
    private static VolumeAdjustment.Control control(Context context, AudioManager audio, VolumeJournal journal) {
        return new VolumeAdjustment.Control() {
            public int volume() { return audio.getStreamVolume(AudioManager.STREAM_MUSIC); }
            public boolean setVolume(int value) { audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);return true; }
            public void pause() throws Exception { Thread.sleep(2000); }
            public void beforeAdjustment(int original,int target) throws Exception {
                if (journal != null) { journal.save(original,target);VolumeRecoveryJob.schedule(context,"adjustment"); }
            }
            public void afterAdjustment(VolumeAdjustment.Result result) throws Exception {
                if (journal != null && (result.restored || result.restorationStatus.equals("external_change_preserved"))) journal.clear();
            }
        };
    }
    static JSONObject recoverVolume(Context context) throws Exception {
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquireVolume(context.getFilesDir())) {
            VolumeJournal journal = new VolumeJournal(new File(context.getFilesDir(),"volume_pending.bin"));
            VolumeJournal.Pending pending = journal.read();
            if (pending == null) return denied("no_pending_volume_recovery").put("volume_write_attempts",0);
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null || audio.isVolumeFixed()) return denied("volume_recovery_unavailable");
            VolumeAdjustment.Result result = VolumeAdjustment.recover(control(context,audio,null),pending.original,pending.target,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            if (result.restored || result.restorationStatus.equals("external_change_preserved")) journal.clear();
            return volumeReceipt(result).put("delivered",false).put("eligible_for_outcome",false);
        }
    }
    private static JSONObject volumeReceipt(VolumeAdjustment.Result result) throws Exception {
        return new JSONObject().put("delivered",result.delivered).put("restored",result.restored)
            .put("eligible_for_outcome",result.delivered && result.restored).put("reason",result.reason)
            .put("restoration_status",result.restorationStatus).put("volume_write_attempts",result.writes)
            .put("scope","temporary_music_volume_readback_not_human_response");
    }
    private static JSONObject denied(String reason) throws Exception {
        return new JSONObject().put("delivered",false).put("eligible_for_outcome",false).put("reason",reason);
    }
    private static JSONObject acknowledged(String reason,String scope) throws Exception {
        return new JSONObject().put("delivered",true).put("eligible_for_outcome",true).put("reason",reason).put("scope",scope);
    }
}
