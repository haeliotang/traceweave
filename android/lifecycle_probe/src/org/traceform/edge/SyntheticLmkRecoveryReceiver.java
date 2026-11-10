// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import java.io.File;
import java.nio.file.Files;
import java.security.PublicKey;
import org.json.JSONObject;

/** System exit reason is mandatory; a signal or forced stop never certifies LMK. */
public final class SyntheticLmkRecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        JSONObject report=new JSONObject();
        try {
            int oldPid=intent.getIntExtra("old_pid",0);
            long started=intent.getLongExtra("prepared_epoch_ms",0);
            if(oldPid<=0 || started<=0) throw new IllegalArgumentException("prepared process identity required");
            org.json.JSONArray exits=new org.json.JSONArray(); boolean lowMemory=false;
            for(ApplicationExitInfo exit:context.getSystemService(ActivityManager.class)
                .getHistoricalProcessExitReasons(context.getPackageName(),oldPid,16)) {
                JSONObject entry=new JSONObject().put("pid",exit.getPid()).put("reason",exit.getReason())
                    .put("timestamp_epoch_ms",exit.getTimestamp()).put("description",exit.getDescription())
                    .put("importance",exit.getImportance()).put("status",exit.getStatus());
                exits.put(entry);
                if(exit.getPid()==oldPid && exit.getTimestamp()>=started && exit.getReason()==ApplicationExitInfo.REASON_LOW_MEMORY)
                    lowMemory=true;
            }
            report.put("exit_records",exits).put("actual_system_lmk",lowMemory).put("prepared_epoch_ms",started);
            File ledger=new File(context.getFilesDir(),"computation_sessions.bin");
            byte[] before=Files.readAllBytes(ledger.toPath());
            int snapshots=0;
            for(File file:context.getCacheDir().listFiles()) if(file.getName().matches("private-circuit-approved-[0-9]+\\.txt")) snapshots++;
            File root=new File(context.getFilesDir(),"private_uniform16");
            PublicKey[] owners=new PublicKey[2];
            for(int owner=0;owner<2;owner++) owners[owner]=java.security.KeyFactory.getInstance("EC").generatePublic(
                new java.security.spec.X509EncodedKeySpec(OwnerAuthentication.readBounded(new File(root,"owner"+owner+".der"),256)));
            boolean replay=false;
            try { UniformPrivateAlignment.computeApproved(context,null,null,root,owners,null); }
            catch(SecurityException error) { replay="replayed computation session".equals(error.getMessage()); }
            int after=0;
            for(File file:context.getCacheDir().listFiles()) if(file.getName().matches("private-circuit-approved-[0-9]+\\.txt")) after++;
            boolean unchanged=java.util.Arrays.equals(before,Files.readAllBytes(ledger.toPath()));
            report.put("snapshots_before",snapshots).put("snapshots_after",after).put("replay_rejected",replay)
                .put("ledger_unchanged",unchanged).put("pid",android.os.Process.myPid()).put("old_pid",oldPid)
                .put("passed",lowMemory && snapshots>0 && after==0 && replay && unchanged && oldPid!=android.os.Process.myPid());
        } catch(Exception error) { try { report.put("passed",false).put("error",error.toString()); } catch(Exception ignored) { } }
        try {
            report.put("nonce",intent.getStringExtra("nonce")).put("scope","system_exit_reason_bound_approved_waiting_request_recovery_not_CPU_phase_LMK");
            NativeVerificationJob.write(new File(context.getFilesDir(),"lmk_recovery.json"),report);
        } catch(Exception error) { android.util.Log.e("TraceformProbe","LMK recovery receipt failed",error); }
    }
}
