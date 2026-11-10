// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.os.PersistableBundle;

public final class SyntheticProofReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler.getPendingJob(34717) != null) throw new IllegalStateException("proof fixture already pending");
            String nonce = java.util.UUID.randomUUID().toString();
            PersistableBundle extras = new PersistableBundle(); extras.putString("nonce", nonce);
            JobInfo job = new JobInfo.Builder(34717, new ComponentName(context, SyntheticProofJob.class))
                .setMinimumLatency(0).setOverrideDeadline(1000).setExtras(extras).build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("proof fixture scheduling rejected");
            NativeVerificationJob.write(new java.io.File(context.getFilesDir(), "proof_dispatch.json"),
                new org.json.JSONObject().put("nonce", nonce).put("job_id", 34717));
        } catch (Exception error) { android.util.Log.e("TraceformProbe", "proof dispatch failed", error); }
    }
}
