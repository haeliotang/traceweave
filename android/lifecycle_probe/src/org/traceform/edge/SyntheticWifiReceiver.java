package org.traceform.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import java.io.File;
import org.json.JSONObject;

/** Read-only network capability check; no identities or numeric readings exported. */
public final class SyntheticWifiReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            double rssi = Double.NaN;
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            boolean wifi = false, infoAvailable = false;
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                wifi = true;
                if (!(caps.getTransportInfo() instanceof WifiInfo)) continue;
                infoAvailable = true;
                int value = ((WifiInfo) caps.getTransportInfo()).getRssi();
                if (value >= -126 && value <= 0) rssi = Double.isNaN(rssi) ? value : Math.max(value, rssi);
            }
            FeatureReadings readings = new FeatureReadings();
            long nanos = android.os.SystemClock.elapsedRealtimeNanos();
            readings.wifiRssi(rssi, nanos);
            FeatureReadings.Reading value = readings.get("conn_wifi_rssi_norm", nanos);
            JSONObject report = new JSONObject().put("nonce", intent.getStringExtra("nonce"))
                .put("wifi_transport_present", wifi).put("wifi_info_available", infoAvailable)
                .put("rssi_reading_available", value != null)
                .put("missing_preserved", Double.isNaN(rssi) && value == null)
                .put("derived_provenance", value != null && "derived".equals(value.kind))
                .put("scope", "isolated_read_only_wifi_capability_not_production_service_acceptance_no_network_identifiers_or_numeric_readings");
            NativeVerificationJob.write(new File(context.getFilesDir(), "wifi_capability.json"), report);
        } catch (Exception error) { android.util.Log.e("TraceformProbe", "wifi capability check failed", error); }
    }
}
