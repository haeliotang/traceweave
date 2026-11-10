// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import java.io.File;
import org.json.JSONObject;

/** Read-only network capability check; no identities or numeric readings exported. */
public final class SyntheticWifiReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            NativeWifiSource.Reading wifi = NativeWifiSource.read(manager);
            FeatureReadings readings = new FeatureReadings();
            long nanos = android.os.SystemClock.elapsedRealtimeNanos();
            readings.wifiRssi(wifi.rssiDbm, nanos);
            FeatureReadings.Reading value = readings.get("conn_wifi_rssi_norm", nanos);
            JSONObject report = new JSONObject().put("nonce", intent.getStringExtra("nonce"))
                .put("wifi_transport_present", wifi.transportPresent).put("wifi_info_available", wifi.infoAvailable)
                .put("rssi_reading_available", value != null)
                .put("missing_preserved", Double.isNaN(wifi.rssiDbm) && value == null)
                .put("derived_provenance", value != null && "derived".equals(value.kind))
                .put("scope", "isolated_read_only_wifi_capability_not_production_service_acceptance_no_network_identifiers_or_numeric_readings");
            NativeVerificationJob.write(new File(context.getFilesDir(), "wifi_capability.json"), report);
        } catch (Exception error) { android.util.Log.e("TraceformProbe", "wifi capability check failed", error); }
    }
}
