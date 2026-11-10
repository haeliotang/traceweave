// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.sdkprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private boolean bound, completed;
    private String requiredPermission;
    private boolean targetExported;
    private int targetUid;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            complete(false, "unexpected_service_connection");
            if (bound) { unbindService(this); bound = false; }
        }
        @Override public void onServiceDisconnected(ComponentName name) {}
    };

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        TextView status = new TextView(this); status.setText("Traceform SDK 权限验证"); setContentView(status);
        Intent service = new Intent().setComponent(new ComponentName("com.inceptless.traceweave.edge", "com.inceptless.traceweave.edge.EdgeService"));
        try {
            android.content.pm.ServiceInfo target = getPackageManager().getServiceInfo(service.getComponent(), 0);
            requiredPermission = target.permission; targetExported = target.exported; targetUid = target.applicationInfo.uid;
        } catch (PackageManager.NameNotFoundException error) { complete(false, error.toString()); return; }
        try {
            bound = bindService(service, connection, BIND_AUTO_CREATE);
            if (!bound) complete(false, "service_not_bound_denial_unproven");
            else handler.postDelayed(() -> complete(false, "no_connection_within_deadline"), 3000);
        } catch (SecurityException error) {
            boolean denied = checkSelfPermission("com.inceptless.traceweave.edge.CANONICAL_INPUT") == PackageManager.PERMISSION_DENIED;
            boolean protectedTarget = targetExported && "com.inceptless.traceweave.edge.CANONICAL_INPUT".equals(requiredPermission);
            complete(denied && protectedTarget && targetUid != android.os.Process.myUid(), error.toString());
        }
    }

    private void complete(boolean passed, String reason) {
        if (completed) return;
        completed = true;
        try {
            JSONObject receipt = new JSONObject().put("passed", passed).put("reason", reason)
                .put("requested_signature_permission_denied", checkSelfPermission("com.inceptless.traceweave.edge.CANONICAL_INPUT") == PackageManager.PERMISSION_DENIED)
                .put("target_required_permission", requiredPermission).put("target_exported", targetExported).put("target_uid", targetUid)
                .put("client_uid", android.os.Process.myUid()).put("completed_epoch_ms", System.currentTimeMillis())
                .put("scope", "differently_signed_application_service_binding_denial_not_raw_binder_transaction_test");
            Files.write(new File(getFilesDir(), "permission_test.json").toPath(), receipt.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { android.util.Log.e("TraceformProbe", "receipt failed", error); }
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (bound) { unbindService(connection); bound = false; }
        super.onDestroy();
    }
}
