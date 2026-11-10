// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;

public final class CapabilityApprovalActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        String requestId = getIntent().getStringExtra("request_id");
        SubstrateService.PendingCapabilityApproval request = SubstrateService.getPendingApproval(requestId);
        if (request == null) {
            finish();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Allow Agent capability?")
            .setMessage(request.packageName + "\nSigner SHA-256: " + request.signerSha256
                + "\n\nRequests " + request.capability + "\nScope: " + request.scope
                + "\nTask: " + request.taskTitle + "\n\nAccess will last 5 minutes.")
            .setPositiveButton("Allow for 5 minutes", (dialog, which) ->
                SubstrateService.resolvePendingApproval(request.id, true))
            .setNegativeButton("Deny", (dialog, which) ->
                SubstrateService.resolvePendingApproval(request.id, false))
            .setOnCancelListener(dialog -> SubstrateService.resolvePendingApproval(request.id, false))
            .setOnDismissListener(dialog -> finish())
            .show();
    }
}
