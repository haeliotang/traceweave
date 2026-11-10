// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;

public final class SyntheticMemoryPressureReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(34718) != null) return;
        scheduler.schedule(new JobInfo.Builder(34718, new ComponentName(context, SyntheticMemoryPressureJob.class))
            .setMinimumLatency(0).setOverrideDeadline(1000).build());
    }
}
