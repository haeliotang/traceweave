package com.inceptless.traceweave.edge;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only acknowledged, outcome-eligible observing trials can update policy aggregates. */
final class NativeFeedbackLedger {
    static JSONObject boundFrame(JSONObject frame) throws Exception {
        JSONObject result = new JSONObject();
        for (String name : new String[]{"model_version","model_sha256","normalizer_sha256","session_id","source_mode","state_token"}) {
            if (!(frame.opt(name) instanceof String)) throw new IllegalArgumentException("string source binding required");
            String value = frame.getString(name);
            if (value.isEmpty()) throw new IllegalArgumentException("nonempty source binding required");
            result.put(name,value);
        }
        if (frame.getLong("elapsed_realtime_nanos") < 0) throw new IllegalArgumentException("monotonic clock required");
        return result.put("timestamp_epoch_ms",frame.getLong("timestamp_epoch_ms"))
            .put("elapsed_realtime_nanos",frame.getLong("elapsed_realtime_nanos"))
            .put("continuous_from_previous",frame.getBoolean("continuous_from_previous"));
    }
    private static FeedbackWindow.Frame parse(JSONObject frame) throws Exception {
        JSONArray identity = new JSONArray();
        for (String name : new String[]{"model_version","model_sha256","normalizer_sha256","session_id","source_mode"})
            identity.put(frame.getString(name));
        return new FeedbackWindow.Frame(frame.getLong("timestamp_epoch_ms"), frame.getLong("elapsed_realtime_nanos") / 1000000,
            identity.toString(), frame.getString("state_token"), frame.getBoolean("continuous_from_previous"));
    }
    static JSONArray observe(File path, JSONObject rawFrame) throws Exception {
        JSONArray reports = new JSONArray();
        if (!path.exists()) return reports;
        JSONObject current = boundFrame(rawFrame);
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(path.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            database.beginTransaction();
            try (Cursor rows = database.rawQuery("SELECT id,context,action,frame,receipt,outcome FROM trials WHERE status='observing'", null)) {
                while (rows.moveToNext()) {
                    JSONObject receipt = new JSONObject(rows.getString(4));
                    FeedbackWindow.Outcome result;
                    if (!Boolean.TRUE.equals(receipt.opt("delivered")) || !Boolean.TRUE.equals(receipt.opt("eligible_for_outcome"))) {
                        result = new FeedbackWindow.Outcome("censored_delivery", "delivery_not_outcome_eligible", -1);
                    } else {
                        JSONObject original = boundFrame(new JSONObject(rows.getString(3)));
                        JSONObject previous = rows.isNull(5) ? original : new JSONObject(rows.getString(5)).getJSONObject("last_frame");
                        result = FeedbackWindow.observe(parse(original),parse(previous),parse(current));
                    }
                    JSONObject outcome = new JSONObject().put("status",result.status).put("reason",result.reason)
                        .put("timestamp_epoch_ms",current.getLong("timestamp_epoch_ms"));
                    if (result.status.equals("observing")) outcome.put("last_frame", current);
                    if (result.status.equals("evaluated")) {
                        outcome.put("reward",result.reward).put("evidence_scope","state_change_association_not_individual_causal_effect");
                        database.execSQL("INSERT INTO policy VALUES (?,?,1,?) ON CONFLICT(context,action) DO UPDATE SET n=n+1,reward=reward+excluded.reward",
                            new Object[]{rows.getString(1),rows.getString(2),result.reward});
                    }
                    database.execSQL("UPDATE trials SET status=?,outcome=? WHERE id=? AND status='observing'",
                        new Object[]{result.status,outcome.toString(),rows.getString(0)});
                    if (!result.status.equals("observing")) reports.put(new JSONObject(outcome.toString()).put("trial_id",rows.getString(0)));
                }
                database.setTransactionSuccessful();
            } finally { database.endTransaction(); }
        }
        return reports;
    }
    static void censorRestart(File path) {
        if (!path.exists()) return;
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(path.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            database.execSQL("UPDATE trials SET status='censored_restart' WHERE status IN ('queued','observing')");
        }
    }
}
