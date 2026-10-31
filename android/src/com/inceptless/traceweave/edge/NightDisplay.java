package com.inceptless.traceweave.edge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.provider.Settings;
import org.json.JSONObject;

final class NightDisplay {
    static JSONObject adjust(Context context, int target) throws Exception {
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquireColorTemperature(context.getFilesDir())) {
            JSONObject capability = capabilities(context);
            if (!capability.optBoolean("config_nightDisplayAvailable")
                    || capability.isNull("config_nightDisplayColorTemperatureMin") || capability.isNull("config_nightDisplayColorTemperatureMax"))
                return new JSONObject().put("delivered", false).put("reason", "night_display_unavailable").put("settings_write_attempts", 0);
            ColorTemperatureJournal journal = new ColorTemperatureJournal(new java.io.File(context.getFilesDir(), "color_temperature_pending.bin"));
            if (journal.exists()) return new JSONObject().put("delivered", false).put("reason", "pending_recovery_required")
                .put("eligible_for_outcome", false).put("settings_write_attempts", 0);
            ColorTemperatureAdjustment.Result result = ColorTemperatureAdjustment.run(control(context, journal), target,
                capability.getInt("config_nightDisplayColorTemperatureMin"), capability.getInt("config_nightDisplayColorTemperatureMax"),
                context.getSharedPreferences("interventions", Context.MODE_PRIVATE).getBoolean("enabled", false)
                    && !new java.io.File(context.getFilesDir(), ".silent").exists(), capability.getBoolean("write_secure_settings_granted"));
            return new JSONObject().put("delivered", result.delivered).put("restored", result.restored)
                .put("restoration_status", result.restorationStatus).put("eligible_for_outcome", result.delivered && result.restored)
                .put("reason", result.reason).put("settings_write_attempts", result.writes)
                .put("scope", "temporary_night_display_setting_readback_not_optical_measurement_or_human_response");
        }
    }
    private static ColorTemperatureAdjustment.Control control(Context context, ColorTemperatureJournal journal) {
        return new ColorTemperatureAdjustment.Control() {
            @Override public int temperature() throws Exception { return Settings.Secure.getInt(context.getContentResolver(), "night_display_color_temperature"); }
            @Override public boolean active() throws Exception { return Settings.Secure.getInt(context.getContentResolver(), "night_display_activated") == 1; }
            @Override public int autoMode() throws Exception { return Settings.Secure.getInt(context.getContentResolver(), "night_display_auto_mode"); }
            @Override public boolean setTemperature(int value) { return Settings.Secure.putInt(context.getContentResolver(), "night_display_color_temperature", value); }
            @Override public void pause() throws Exception { Thread.sleep(2000); }
            @Override public void beforeAdjustment(int original, int target) throws Exception {
                if (journal != null) {
                    journal.save(original, target);
                    ColorRecoveryJob.schedule(context, "adjustment");
                }
            }
            @Override public void afterAdjustment(ColorTemperatureAdjustment.Result result) throws Exception {
                if (journal != null && (result.restored || result.restorationStatus.equals("external_change_preserved"))) journal.clear();
            }
        };
    }
    static JSONObject recover(Context context) throws Exception {
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquireColorTemperature(context.getFilesDir())) {
            ColorTemperatureJournal journal = new ColorTemperatureJournal(new java.io.File(context.getFilesDir(), "color_temperature_pending.bin"));
            ColorTemperatureJournal.Pending pending = journal.read();
            if (pending == null) return new JSONObject().put("delivered", false).put("reason", "no_pending_recovery")
                .put("eligible_for_outcome", false).put("settings_write_attempts", 0);
            JSONObject capability = capabilities(context);
            if (!capability.optBoolean("config_nightDisplayAvailable") || capability.isNull("config_nightDisplayColorTemperatureMin") || capability.isNull("config_nightDisplayColorTemperatureMax"))
                return new JSONObject().put("delivered", false).put("reason", "recovery_capability_unavailable")
                    .put("eligible_for_outcome", false).put("settings_write_attempts", 0);
            ColorTemperatureAdjustment.Result result = ColorTemperatureAdjustment.recover(control(context, null), pending.original, pending.target,
                capability.getInt("config_nightDisplayColorTemperatureMin"), capability.getInt("config_nightDisplayColorTemperatureMax"),
                capability.getBoolean("write_secure_settings_granted"));
            if (result.restored || result.restorationStatus.equals("external_change_preserved")) journal.clear();
            return new JSONObject().put("delivered", false).put("restored", result.restored).put("restoration_status", result.restorationStatus)
                .put("reason", result.reason).put("eligible_for_outcome", false).put("settings_write_attempts", result.writes)
                .put("scope", "pending_color_setting_cleanup_not_new_intervention_or_human_response");
        }
    }
    static JSONObject capabilities(Context context) throws Exception {
        JSONObject report = new JSONObject().put("scope", "native_read_only_night_display_capability_not_actuation")
            .put("settings_writes", 0)
            .put("write_secure_settings_granted", context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED)
            .put("control_display_color_transforms_granted", context.checkSelfPermission("android.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS") == PackageManager.PERMISSION_GRANTED);
        Resources resources = context.getResources();
        for (String name : new String[]{"config_nightDisplayAvailable", "config_nightDisplayColorTemperatureMin", "config_nightDisplayColorTemperatureMax"}) {
            boolean availability = name.equals("config_nightDisplayAvailable");
            int identifier = resources.getIdentifier(name, availability ? "bool" : "integer", "android");
            if (identifier == 0) report.put(name, JSONObject.NULL);
            else if (availability) report.put(name, resources.getBoolean(identifier));
            else report.put(name, resources.getInteger(identifier));
        }
        JSONObject settings = new JSONObject();
        for (String name : new String[]{"night_display_activated", "night_display_auto_mode", "night_display_color_temperature"}) {
            JSONObject reading = new JSONObject();
            try {
                String value = Settings.Secure.getString(context.getContentResolver(), name);
                reading.put("status", value == null ? "unset" : "read").put("value", value == null ? JSONObject.NULL : value);
            } catch (SecurityException error) {
                reading.put("status", "permission_denied").put("value", JSONObject.NULL);
            }
            settings.put(name, reading);
        }
        return report.put("settings", settings);
    }
}
