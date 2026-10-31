package com.inceptless.traceweave.edge;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;

final class ShadowComparisonFixture {
    static JSONObject verify(Context context) throws Exception {
        return new JSONObject().put("passed", true).put("bound_trigger", verify(context, false))
            .put("replaced_trigger", verify(context, true));
    }
    private static JSONObject verify(Context context, boolean missingTrigger) throws Exception {
        File directory = new File(context.getCacheDir(), "shadow-" + java.util.UUID.randomUUID());
        if (!directory.mkdir()) throw new IllegalStateException("fixture directory unavailable");
        File path = new File(directory, "synthetic.sqlite"), output = new File(directory, "shadow_comparison.json");
        ContextWrapper synthetic = new ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
            @Override public File getDatabasePath(String name) { return path; }
            @Override public Object getSystemService(String name) {
                throw new IllegalStateException("shadow fixture requested physical resource:" + name);
            }
        };
        EdgeService service = new EdgeService();
        java.lang.reflect.Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        attach.setAccessible(true); attach.invoke(service, synthetic);
        long instant = System.currentTimeMillis();
        try {
            service.onCreate();
            ModelEngine model = new ModelEngine(context);
            try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(path, null)) {
                database.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)");
                for (int index = 0; index < 40; index++) {
                    long epoch = instant - (39 - index) * 30000L - (missingTrigger ? 1000 : 0);
                    JSONObject frame = new JSONObject().put("timestamp_epoch_ms", epoch).put("state_token", model.tokens[0])
                        .put("model_version", model.modelVersion).put("model_sha256", model.modelDigest)
                        .put("normalizer_sha256", model.normalizerDigest).put("source_mode", "synthetic_fixture")
                        .put("session_id", "shadow-fixture").put("continuous_from_previous", index > 0);
                    database.execSQL("INSERT INTO warm_trajectory VALUES (?,?,?,1)", new Object[]{index, epoch, frame.toString()});
                }
            }
            java.lang.reflect.Method schedule = EdgeService.class.getDeclaredMethod("scheduleShadowComparison",
                long.class, boolean.class, String.class, CriticalSlowingDetector.Result.class);
            schedule.setAccessible(true);
            schedule.invoke(service, instant, false, model.tokens[0], new CriticalSlowingDetector.Result(31,true,1,1,3));
            schedule.invoke(service, instant, true, model.tokens[0], new CriticalSlowingDetector.Result(30,true,1,1,3));
            schedule.invoke(service, instant, true, model.tokens[0], new CriticalSlowingDetector.Result(31,true,1,1,2));
            if (output.exists()) throw new IllegalStateException("ineligible CSD gate published");
            schedule.invoke(service, instant, true, model.tokens[0], new CriticalSlowingDetector.Result(31,true,1,1,3));
            for (int index = 0; index < 500 && !output.exists(); index++) Thread.sleep(10);
            if (!output.exists()) throw new IllegalStateException("shadow comparison did not complete");
            String raw = new String(Files.readAllBytes(output.toPath()), "UTF-8");
            JSONObject receipt = new JSONObject(raw);
            if (missingTrigger) {
                if (receipt.getBoolean("available") || receipt.has("counterfactual")
                        || !receipt.getString("reason").equals("trigger_history_binding_mismatch"))
                    throw new IllegalStateException("older initial history accepted as trigger frame");
                return new JSONObject().put("passed", true).put("trigger_mismatch_rejected", true)
                    .put("collection_started", false).put("actuator_calls", 0);
            }
            if (!receipt.getBoolean("available") || !receipt.getString("mode").equals("shadow")
                    || receipt.getBoolean("eligible_for_intervention") || receipt.getInt("actuator_calls") != 0
                    || receipt.has("occupancy_probabilities")
                    || receipt.getJSONObject("counterfactual").has("scenario_occupancy_probabilities"))
                throw new IllegalStateException("shadow authority or receipt scope mismatch");
            if (!receipt.getJSONObject("policy").getString("status").equals("shadow")
                    || receipt.getJSONObject("policy").getJSONObject("action_probabilities").getDouble("none") != .25)
                throw new IllegalStateException("automatic shadow policy not recorded");
            try (SQLiteDatabase ledger = SQLiteDatabase.openDatabase(new File(directory, "intervention_trials.sqlite").getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                if (android.database.DatabaseUtils.queryNumEntries(ledger, "trials") != 1
                        || android.database.DatabaseUtils.queryNumEntries(ledger, "policy") != 0)
                    throw new IllegalStateException("automatic shadow policy credit or trial mismatch");
            }
            schedule.invoke(service, instant + 1, true, model.tokens[0], new CriticalSlowingDetector.Result(31,true,1,1,3));
            Thread.sleep(50);
            if (!raw.equals(new String(Files.readAllBytes(output.toPath()), "UTF-8")))
                throw new IllegalStateException("cooldown receipt replaced");
            try (SQLiteDatabase database = SQLiteDatabase.openDatabase(path.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                if (android.database.DatabaseUtils.queryNumEntries(database, "warm_trajectory") != 40)
                    throw new IllegalStateException("shadow changed history");
            }
            return new JSONObject().put("passed", true).put("gate_and_cooldown_verified", true)
                .put("history_unchanged", true).put("collection_started", false).put("actuator_calls", 0)
                .put("comparison", receipt.getJSONObject("counterfactual"))
                .put("scope", "synthetic_service_CSD_gate_to_history_MC_shadow_not_live_capture_or_actuation");
        } finally {
            service.onDestroy();
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
    }
}
