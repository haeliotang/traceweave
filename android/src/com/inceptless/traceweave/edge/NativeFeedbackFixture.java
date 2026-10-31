package com.inceptless.traceweave.edge;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

final class NativeFeedbackFixture {
    static JSONObject frame(long seconds, String token) throws Exception {
        return new JSONObject().put("timestamp_epoch_ms",1700000000000L+seconds*1000)
            .put("elapsed_realtime_nanos",seconds*1000000000L).put("state_token",token)
            .put("model_version","v1").put("model_sha256","model").put("normalizer_sha256","norm")
            .put("session_id","session").put("source_mode","native_sensor_subset").put("continuous_from_previous",true);
    }
    static JSONObject verify(Context context) throws Exception {
        File path=new File(context.getCacheDir(),"feedback-"+java.util.UUID.randomUUID()+".sqlite");
        try {
            ShadowTrialLedger.propose(path,new JSONObject().put("recommendation","RECOMMEND_INTERVENTION"),
                "v1","model","norm","session","A","UTC",1700000000000L,.1);
            try(SQLiteDatabase database=SQLiteDatabase.openOrCreateDatabase(path,null)) {
                for(String id:new String[]{"eligible","failed","changed"}) {
                    JSONObject original=frame(0,"A");
                    if(id.equals("changed"))original.put("normalizer_sha256","different");
                    JSONObject delivery=new JSONObject().put("delivered",!id.equals("failed")).put("eligible_for_outcome",true);
                    database.execSQL("INSERT INTO trials VALUES (?,?,?,?,?,?,?,?,NULL)",new Object[]{id,"context","none",.25,1700000000000L,original.toString(),"observing",delivery.toString()});
                }
            }
            JSONArray early=NativeFeedbackLedger.observe(path,frame(200,"A"));
            if(early.length()!=2)throw new IllegalStateException("delivery or binding censor missing");
            if(NativeFeedbackLedger.observe(path,frame(400,"B")).length()!=0)throw new IllegalStateException("early reward");
            JSONArray evaluated=NativeFeedbackLedger.observe(path,frame(600,"B"));
            if(evaluated.length()!=1 || evaluated.getJSONObject(0).getInt("reward")!=1)
                throw new IllegalStateException("ten minute reward missing");
            if(NativeFeedbackLedger.observe(path,frame(601,"B")).length()!=0)throw new IllegalStateException("duplicate outcome");
            try(SQLiteDatabase database=SQLiteDatabase.openOrCreateDatabase(path,null);
                    Cursor rows=database.rawQuery("SELECT n,reward FROM policy",null)) {
                rows.moveToFirst();
                if(rows.getLong(0)!=1 || rows.getLong(1)!=1 || rows.getCount()!=1)
                    throw new IllegalStateException("policy credit mismatch");
                database.execSQL("INSERT INTO trials VALUES ('restart','context','pulse',.5,0,'{}','queued','{}',NULL)");
            }
            NativeFeedbackLedger.censorRestart(path);
            try(SQLiteDatabase database=SQLiteDatabase.openOrCreateDatabase(path,null);
                    Cursor rows=database.rawQuery("SELECT status FROM trials WHERE id='restart'",null)) {
                rows.moveToFirst();if(!rows.getString(0).equals("censored_restart"))throw new IllegalStateException("restart censor missing");
            }
            return new JSONObject().put("passed",true).put("delivery_and_binding_censor_verified",true)
                .put("ten_minute_feedback_and_idempotence_verified",true).put("restart_censor_verified",true)
                .put("shadow_not_rewarded",true).put("actuator_calls",0)
                .put("scope","synthetic_delivered_trial_receipts_and_SQLite_feedback_not_actual_actuation_or_human_outcomes");
        } finally {
            for(String suffix:new String[]{"","-journal","-wal","-shm"})new File(path.getPath()+suffix).delete();
        }
    }
}
