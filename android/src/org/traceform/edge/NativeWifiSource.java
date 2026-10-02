package org.traceform.edge;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;

/** Numeric Wi-Fi transport subset; never requests network identifiers or scans. */
final class NativeWifiSource {
    static final class Reading {
        boolean transportPresent, infoAvailable;
        double rssiDbm = Double.NaN;
    }
    static Reading read(ConnectivityManager manager) {
        Reading result = new Reading();
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            result.transportPresent = true;
            if (android.os.Build.VERSION.SDK_INT < 29 || !(caps.getTransportInfo() instanceof WifiInfo)) continue;
            result.infoAvailable = true;
            int rssi = ((WifiInfo) caps.getTransportInfo()).getRssi();
            if (rssi >= -126 && rssi <= 0)
                result.rssiDbm = Double.isNaN(result.rssiDbm) ? rssi : Math.max(result.rssiDbm, rssi);
        }
        return result;
    }
}
