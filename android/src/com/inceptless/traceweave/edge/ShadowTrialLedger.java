package com.inceptless.traceweave.edge;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent contextual proposals and probabilities; shadow trials never train the policy. */
final class ShadowTrialLedger {
    static JSONObject propose(File path, JSONObject comparison, String modelVersion, String modelDigest,
            String normalizerDigest, String session, String token, String timezone, long instant, double draw) throws Exception {
        return propose(path,comparison,modelVersion,modelDigest,normalizerDigest,session,token,timezone,instant,draw,null,false);
    }
    static JSONObject propose(File path, JSONObject comparison, String modelVersion, String modelDigest,
            String normalizerDigest, String session, String token, String timezone, long instant, double draw,
            JSONObject trigger, boolean active) throws Exception {
        if (active) {
            trigger = NativeFeedbackLedger.boundFrame(trigger);
            if (trigger.getLong("timestamp_epoch_ms") != instant || !trigger.getBoolean("continuous_from_previous")
                    || !trigger.getString("model_version").equals(modelVersion) || !trigger.getString("model_sha256").equals(modelDigest)
                    || !trigger.getString("normalizer_sha256").equals(normalizerDigest) || !trigger.getString("session_id").equals(session)
                    || !trigger.getString("state_token").equals(token) || !trigger.getString("source_mode").equals("native_sensor_subset"))
                throw new IllegalArgumentException("active proposal requires exact native trigger binding");
        }
        if (!comparison.getString("recommendation").equals("RECOMMEND_INTERVENTION")) return null;
        int phase = Instant.ofEpochMilli(instant).atZone(ZoneId.of(timezone)).getHour() / 6;
        String context = new JSONArray().put(modelVersion).put(modelDigest).put(normalizerDigest)
            .put(token).put("CSD_TIPPING").put(phase).toString();
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(path, null)) {
            database.execSQL("CREATE TABLE IF NOT EXISTS trials (id TEXT PRIMARY KEY, context TEXT, action TEXT, propensity REAL, created INTEGER, frame TEXT, status TEXT, receipt TEXT, outcome TEXT)");
            database.execSQL("CREATE TABLE IF NOT EXISTS policy (context TEXT, action TEXT, n INTEGER, reward INTEGER, PRIMARY KEY(context,action))");
            database.beginTransaction();
            try {
                try (Cursor rows = database.rawQuery("SELECT MAX(created) FROM trials", null)) {
                    rows.moveToFirst();
                    if (!rows.isNull(0) && instant - rows.getLong(0) < 900000)
                        return new JSONObject().put("status", "suppressed").put("reason", "global_cooldown");
                }
                int failures = 0;
                Long lastEvaluated = null;
                try (Cursor rows = database.rawQuery("SELECT created,outcome FROM trials WHERE context=? AND action!='none' AND status='evaluated' ORDER BY created DESC LIMIT 3", new String[]{context})) {
                    while (rows.moveToNext()) {
                        if (lastEvaluated == null) lastEvaluated = rows.getLong(0);
                        if (new JSONObject(rows.getString(1)).getInt("reward") != 0) break;
                        failures++;
                    }
                }
                long cooldown = ContextualPolicy.cooldownMillis(failures);
                if (lastEvaluated != null && instant - lastEvaluated < cooldown)
                    return new JSONObject().put("status", "suppressed").put("reason", "context_fatigue_cooldown");
                long[] counts = new long[ContextualPolicy.ACTIONS.length], rewards = new long[counts.length];
                try (Cursor rows = database.rawQuery("SELECT action,n,reward FROM policy WHERE context=?", new String[]{context})) {
                    while (rows.moveToNext()) {
                        int index = java.util.Arrays.asList(ContextualPolicy.ACTIONS).indexOf(rows.getString(0));
                        if (index >= 0) { counts[index] = rows.getLong(1); rewards[index] = rows.getLong(2); }
                    }
                }
                ContextualPolicy.Decision decision = ContextualPolicy.select(counts, rewards, draw);
                String id = java.util.UUID.randomUUID().toString();
                JSONObject frame = new JSONObject().put("timestamp_epoch_ms", instant).put("state_token", token)
                    .put("model_version", modelVersion).put("model_sha256", modelDigest).put("normalizer_sha256", normalizerDigest)
                    .put("session_id", session).put("source_mode", "native_sensor_subset");
                if (active) frame = trigger;
                String status = active ? (decision.action.equals("none") ? "observing" : "queued") : "shadow";
                JSONObject probabilities = new JSONObject().put("none", decision.probabilities[0]);
                for (int index = 0; index < ContextualPolicy.ACTIONS.length; index++)
                    probabilities.put(ContextualPolicy.ACTIONS[index], decision.probabilities[index + 1]);
                JSONObject report = new JSONObject().put("trial_id", id).put("action", decision.action)
                    .put("propensity", decision.propensity).put("status", status).put("action_probabilities", probabilities)
                    .put("context_cooldown_ms", cooldown).put("consecutive_no_escape_outcomes", failures)
                    .put("eligible_for_intervention", false).put("execution_authorized",active)
                    .put("evidence_scope", "contextual_policy_proposal_not_delivery_or_outcome");
                JSONObject delivery = active && decision.action.equals("none") ? new JSONObject().put("delivered",true)
                    .put("eligible_for_outcome",true).put("reason","randomized_no_action") : report;
                database.execSQL("INSERT INTO trials VALUES (?,?,?,?,?,?,?,?,NULL)", new Object[]{id, context, decision.action,
                    decision.propensity, instant, frame.toString(), status, delivery.toString()});
                database.execSQL("DELETE FROM trials WHERE created < ? AND status IN ('shadow','evaluated','censored','censored_restart','censored_delivery','delivery_failed')",
                    new Object[]{Math.subtractExact(instant, 30L * 86400000)});
                database.setTransactionSuccessful();
                return report;
            } finally { database.endTransaction(); }
        }
    }
}
