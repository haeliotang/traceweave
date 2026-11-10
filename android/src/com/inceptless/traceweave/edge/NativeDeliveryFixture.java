// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.json.JSONObject;

final class NativeDeliveryFixture {
    static JSONObject verify(Context context) throws Exception {
        verifyStopBarrier(context);
        File path=new File(context.getCacheDir(),"delivery-"+java.util.UUID.randomUUID()+".sqlite");
        JSONObject comparison=new JSONObject().put("recommendation","RECOMMEND_INTERVENTION");
        try {
            String[] expected={"delivery_failed","censored_delivery","observing","delivery_failed"};
            String last=null;
            for(int index=0;index<4;index++) {
                JSONObject frame=NativeFeedbackFixture.frame(index*900,"A");
                JSONObject proposal=ShadowTrialLedger.propose(path,comparison,"v1","model","norm","session","A","UTC",
                    frame.getLong("timestamp_epoch_ms"),.4,frame,true);
                if(!proposal.getString("status").equals("queued"))throw new IllegalStateException("authorized trial not queued");
                JSONObject receipt=new JSONObject().put("delivered",index!=0).put("eligible_for_outcome",index>1);
                if(index==3)receipt.put("delivered","true");
                last=proposal.getString("trial_id");NativeTrialDelivery.complete(path,last,receipt);
                try(SQLiteDatabase database=SQLiteDatabase.openDatabase(path.getPath(),null,SQLiteDatabase.OPEN_READONLY);
                        Cursor rows=database.rawQuery("SELECT status FROM trials WHERE id=?",new String[]{last})) {
                    rows.moveToFirst();if(!rows.getString(0).equals(expected[index]))throw new IllegalStateException("receipt status mismatch");
                }
            }
            JSONObject frame=NativeFeedbackFixture.frame(3600,"A");
            JSONObject control=ShadowTrialLedger.propose(path,comparison,"v1","model","norm","session","A","UTC",
                frame.getLong("timestamp_epoch_ms"),.1,frame,true);
            if(!control.getString("action").equals("none")||!control.getString("status").equals("observing"))
                throw new IllegalStateException("randomized no-action not observing");
            NativeTrialDelivery.censorStop(path);
            NativeTrialDelivery.complete(path,control.getString("trial_id"),new JSONObject().put("delivered",true).put("eligible_for_outcome",true));
            try(SQLiteDatabase database=SQLiteDatabase.openDatabase(path.getPath(),null,SQLiteDatabase.OPEN_READONLY);
                    Cursor rows=database.rawQuery("SELECT status FROM trials WHERE id=?",new String[]{control.getString("trial_id")})) {
                rows.moveToFirst();if(!rows.getString(0).equals("censored")||android.database.DatabaseUtils.queryNumEntries(database,"policy")!=0)
                    throw new IllegalStateException("stopped trial resurrected or credited");
            }
            return new JSONObject().put("passed",true).put("receipt_status_and_strict_boolean_verified",true)
                .put("stop_publication_barrier_verified",true)
                .put("randomized_control_observing_verified",true).put("stop_censor_and_late_receipt_rejection_verified",true)
                .put("physical_api_calls",0).put("scope","synthetic_authorized_trial_receipts_not_actual_actuator_delivery");
        } finally {
            for(String suffix:new String[]{"","-journal","-wal","-shm"})new File(path.getPath()+suffix).delete();
        }
    }
    private static void verifyStopBarrier(Context context) throws Exception {
        File directory=new File(context.getCacheDir(),"stop-barrier-"+java.util.UUID.randomUUID());
        if(!directory.mkdir())throw new IllegalStateException("stop fixture directory unavailable");
        android.content.ContextWrapper guarded=new android.content.ContextWrapper(context){
            @Override public File getFilesDir(){return directory;}
            @Override public Object getSystemService(String name){throw new IllegalStateException("physical resource requested:"+name);}
        };
        EdgeService service=new EdgeService();
        java.lang.reflect.Method attach=android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);
        attach.setAccessible(true);attach.invoke(service,guarded);service.onCreate();
        java.lang.reflect.Field field=EdgeService.class.getDeclaredField("shadowComparisons");field.setAccessible(true);
        ShadowComparisonWorker worker=(ShadowComparisonWorker)field.get(service);
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.lang.reflect.Method stop=EdgeService.class.getDeclaredMethod("stopCollection");stop.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        Thread stopping=new Thread(() -> {try{stop.invoke(service);}catch(Throwable failure){error.set(failure);}});
        try {
            worker.update(0,true,() -> "synthetic",value -> {entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            if(!entered.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("publication did not start");
            stopping.start();
            java.lang.reflect.Field stopped=EdgeService.class.getDeclaredField("stopped");stopped.setAccessible(true);
            for(int index=0;index<100&&!stopped.getBoolean(service);index++)Thread.sleep(5);
            if(!stopped.getBoolean(service))throw new IllegalStateException("stop flag delayed until publication barrier released");
        } finally {
            release.countDown();stopping.join(5000);service.onDestroy();
            File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();
        }
        if(stopping.isAlive()||error.get()!=null)throw new IllegalStateException("stop fixture worker failed",error.get());
    }
}
