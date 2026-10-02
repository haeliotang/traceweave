package org.traceform.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.os.PersistableBundle;

public final class SyntheticPrivateAlignmentReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler.getPendingJob(34716) != null) throw new IllegalStateException("private fixture already pending");
            String nonce = java.util.UUID.randomUUID().toString();
            PersistableBundle extras = new PersistableBundle(); extras.putString("nonce", nonce);
            JobInfo job = new JobInfo.Builder(34716, new ComponentName(context, SyntheticPrivateAlignmentJob.class))
                .setMinimumLatency(0).setOverrideDeadline(1000).setExtras(extras).build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("private fixture scheduling rejected");
            NativeVerificationJob.write(new java.io.File(context.getFilesDir(), "private_dispatch.json"),
                new org.json.JSONObject().put("nonce", nonce).put("job_id", 34716));
        } catch (Exception error) { android.util.Log.e("TraceformProbe", "private dispatch failed", error); }
    }
}
