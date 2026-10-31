package com.inceptless.traceweave.edge;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.json.JSONObject;

final class ShadowPolicyFixture {
    static JSONObject verify(Context context) throws Exception {
        File path = new File(context.getCacheDir(), "policy-" + java.util.UUID.randomUUID() + ".sqlite");
        long instant = java.time.Instant.parse("2026-10-02T02:00:00Z").toEpochMilli();
        JSONObject comparison = new JSONObject().put("recommendation", "RECOMMEND_INTERVENTION");
        try {
            JSONObject first = propose(path, comparison, instant, .1);
            if (!first.getString("action").equals("none") || first.getDouble("propensity") != .25
                    || !first.getString("status").equals("shadow")) throw new IllegalStateException("randomized control mismatch");
            if (!propose(path, comparison, instant + 899999, .4).getString("reason").equals("global_cooldown"))
                throw new IllegalStateException("persisted cooldown lost on reopen");
            JSONObject boundary = propose(path, comparison, instant + 900000, .4);
            if (!boundary.getString("status").equals("shadow")) throw new IllegalStateException("cooldown equality rejected");
            try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(path, null)) {
                if (android.database.DatabaseUtils.queryNumEntries(database, "trials") != 2
                        || android.database.DatabaseUtils.queryNumEntries(database, "policy") != 0)
                    throw new IllegalStateException("shadow learned or duplicated trial");
                database.execSQL("DELETE FROM trials");
                String binding = "[\"v1\",\"model\",\"normalizer\",\"A\",\"CSD_TIPPING\",0]";
                database.execSQL("INSERT INTO policy VALUES (?,?,?,?)", new Object[]{binding,"notification",10,10});
                for (int index = 0; index < 3; index++) database.execSQL("INSERT INTO trials VALUES (?,?,?,?,?,?,?,?,?)",
                    new Object[]{"fatigue"+index,binding,"pulse",.6375,instant-1000000-index,"{}","evaluated","{}","{\"reward\":0}"});
            }
            if (!propose(path, comparison, instant, .4).getString("reason").equals("context_fatigue_cooldown"))
                throw new IllegalStateException("fatigue cooldown absent");
            try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(path, null)) {
                database.execSQL("UPDATE trials SET outcome=? WHERE id='fatigue0'", new Object[]{"{\"reward\":1}"});
            }
            JSONObject learned = propose(path, comparison, instant, .4);
            if (!learned.getString("action").equals("notification") || Math.abs(learned.getDouble("propensity")-.6375)>1e-12
                    || learned.getLong("context_cooldown_ms") != 900000)
                throw new IllegalStateException("context policy or fatigue reset mismatch");
            try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(path, null);
                    Cursor rows = database.rawQuery("SELECT n,reward FROM policy", null)) {
                rows.moveToFirst();
                if (rows.getLong(0)!=10 || rows.getLong(1)!=10) throw new IllegalStateException("shadow policy credit assigned");
            }
            return new JSONObject().put("passed", true).put("reopen_cooldown_boundary_verified", true)
                .put("fatigue_and_success_reset_verified", true).put("learned_context_selection_verified", true)
                .put("shadow_no_policy_credit_verified", true).put("actuator_calls", 0)
                .put("scope", "synthetic_sqlite_shadow_policy_not_real_delivery_or_feedback");
        } finally {
            for (String suffix : new String[]{"","-journal","-wal","-shm"}) new File(path.getPath()+suffix).delete();
        }
    }
    private static JSONObject propose(File path, JSONObject comparison, long instant, double draw) throws Exception {
        return ShadowTrialLedger.propose(path, comparison, "v1", "model", "normalizer", "session", "A", "UTC", instant, draw);
    }
}
