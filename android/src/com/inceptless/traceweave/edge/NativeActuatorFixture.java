package com.inceptless.traceweave.edge;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;

final class NativeActuatorFixture {
    static JSONObject verify(Context context) throws Exception {
        String name = "actuator-fixture-" + java.util.UUID.randomUUID();
        File directory = new File(context.getCacheDir(), name);
        if (!directory.mkdir()) throw new IllegalStateException("fixture directory unavailable");
        SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        ContextWrapper guarded = new ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) { return preferences; }
            @Override public Object getSystemService(String name) { throw new IllegalStateException("physical resource requested:"+name); }
        };
        int guardedActions = 0;
        try {
            for (String action : new String[]{"none","pulse","notification","volume_duck","color_temperature"}) {
                if (NativeMicroActuator.execute(guarded,action).getBoolean("delivered"))
                    throw new IllegalStateException("disabled actuator delivered");
                guardedActions++;
            }
            if (!preferences.edit().putBoolean("enabled",true).putString("mode","active").commit()) throw new IllegalStateException("fixture preferences failed");
            Files.write(new File(directory,".silent").toPath(),new byte[0]);
            for (String action : ContextualPolicy.ACTIONS) {
                if (NativeMicroActuator.execute(guarded,action).getBoolean("delivered"))
                    throw new IllegalStateException("silent actuator delivered");
                guardedActions++;
            }
            new File(directory,".silent").delete();
            if (NativeMicroActuator.execute(guarded,"pulse").getBoolean("delivered"))
                throw new IllegalStateException("haptics default enabled");
            File path = new File(directory,"journal.bin");
            VolumeJournal journal = new VolumeJournal(path);journal.save(5,4);
            VolumeJournal.Pending pending = new VolumeJournal(path).read();
            if (pending.original!=5 || pending.target!=4) throw new IllegalStateException("volume journal reopen");
            boolean duplicate=false;
            try{journal.save(6,5);}catch(java.io.IOException expected){duplicate=true;}
            if(!duplicate)throw new IllegalStateException("pending journal overwritten");
            journal.clear();Files.write(path.toPath(),new byte[]{0,0,0,0});
            boolean corrupt=false;
            try{journal.read();}catch(java.io.IOException expected){corrupt=true;}
            if(!corrupt)throw new IllegalStateException("corrupt journal accepted");
            journal.clear();
            try(AlignmentQueryLease lease=AlignmentQueryLease.acquireVolume(directory)) {
                boolean rejected=false;
                try{NativeMicroActuator.recoverVolume(guarded);}catch(SecurityException expected){rejected=true;}
                if(!rejected)throw new IllegalStateException("overlapping recovery admitted");
            }
            if(NativeMicroActuator.recoverVolume(guarded).getBoolean("delivered"))
                throw new IllegalStateException("empty recovery delivered");
            return new JSONObject().put("passed",true).put("disabled_and_silent_guards",guardedActions)
                .put("haptics_default_disabled",true).put("volume_journal_reopen_corruption_and_overlap_verified",true)
                .put("physical_api_calls",0).put("scope","synthetic_preferences_journal_and_native_actuator_guards_not_physical_execution");
        } finally {
            preferences.edit().clear().commit();context.deleteSharedPreferences(name);
            File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();
        }
    }
}
