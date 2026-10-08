package org.traceform.edge;

import android.app.Activity;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.security.PublicKey;
import org.json.JSONObject;

/** Approved computation waits for a peer, then the finished activity becomes cached. */
public final class SyntheticLmkActivity extends Activity {
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long deadline;
    private String nonce;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        nonce = getIntent().getStringExtra("nonce");
        try { java.util.UUID.fromString(nonce); }
        catch (RuntimeException invalid) { finish(); return; }
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText("隔离内存回收验证\n准备完成后自动退出页面"); setContentView(label);
        deadline = android.os.SystemClock.elapsedRealtime() + 30000;
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                File root = new File(getFilesDir(), "private_uniform16");
                byte[] bindings = OwnerAuthentication.readBounded(new File(root,"bindings.bin"),192);
                StringBuilder session = new StringBuilder();
                for(int index=0;index<32;index++) session.append(String.format("%02x",bindings[index==31 ? 32 : index]&255));
                String pin = new String(OwnerAuthentication.readBounded(new File(root,"2.pin"),64),"US-ASCII");
                String[] command = {new File(getApplicationInfo().nativeLibraryDir,"libtraceform_private_participant.so").getAbsolutePath(),
                    "1","24885",new File(root,"circuit.txt").getAbsolutePath(),new File(root,"1.pem").getAbsolutePath(),
                    new File(root,"1.key").getAbsolutePath(),new File(root,"2.pem").getAbsolutePath(),pin,session.toString()};
                PublicKey[] owners = new PublicKey[2];
                for(int owner=0;owner<2;owner++) owners[owner]=java.security.KeyFactory.getInstance("EC").generatePublic(
                    new java.security.spec.X509EncodedKeySpec(OwnerAuthentication.readBounded(new File(root,"owner"+owner+".der"),256)));
                String bits = new String(OwnerAuthentication.readBounded(new File(root,"own.bits"),6224),"US-ASCII");
                UniformPrivateAlignment.computeApproved(this,command,new File(root,"circuit.txt"),root,owners,bits);
                result.put("unexpected_completed",true);
            } catch(Exception error) {
                try { result.put("error",error.toString()); } catch(Exception ignored) { }
            }
            try { result.put("nonce",nonce).put("completed_epoch_ms",System.currentTimeMillis());
                NativeVerificationJob.write(new File(getFilesDir(),"lmk_worker_result.json"),result);
            } catch(Exception error) { android.util.Log.e("TraceformProbe","LMK worker receipt failed",error); }
        },"synthetic-lmk-waiting-request").start();
        handler.postDelayed(this::observePrepared,200);
    }
    private void observePrepared() {
        try {
            int child=0;
            File[] processes=new File("/proc").listFiles();
            if(processes!=null) for(File process:processes) try {
                if(!process.getName().matches("[0-9]+") || android.system.Os.stat(process.getAbsolutePath()).st_uid!=android.os.Process.myUid()) continue;
                String command=new String(Files.readAllBytes(new File(process,"cmdline").toPath()),"UTF-8");
                if(command.contains("libtraceform_private_participant.so") && command.contains("private-circuit-approved-")) child=Integer.parseInt(process.getName());
            } catch(java.io.IOException | android.system.ErrnoException unavailable) { }
            if(child!=0) {
                JSONObject report=new JSONObject().put("nonce",nonce).put("pid",android.os.Process.myPid())
                    .put("child_pid",child).put("prepared_epoch_ms",System.currentTimeMillis())
                    .put("low_memory_kill_reporting_supported",android.app.ActivityManager.isLowMemoryKillReportSupported())
                    .put("reservation_bytes",new File(getFilesDir(),"computation_sessions.bin").length())
                    .put("scope","approved_request_waiting_for_peer_not_authenticated_CPU_phase");
                NativeVerificationJob.write(new File(getFilesDir(),"lmk_prepared.json"),report);
                finishAndRemoveTask(); return;
            }
            if(android.os.SystemClock.elapsedRealtime()<deadline) handler.postDelayed(this::observePrepared,200);
            else finish();
        } catch(Exception error) { android.util.Log.e("TraceformProbe","LMK prepare failed",error); finish(); }
    }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
