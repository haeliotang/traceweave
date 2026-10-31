from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeWifiReadings(unittest.TestCase):
    def test_transport_selection_and_legacy_api_guard(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        source = Path(__file__).resolve().parents[1] / 'android/src/com/inceptless/traceweave/edge/NativeWifiSource.java'
        stubs = {
            'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public static int SDK_INT=35; } }',
            'android/net/Network.java': 'package android.net; public class Network { public int index; public Network(int i){index=i;} }',
            'android/net/ConnectivityManager.java': '''package android.net; public class ConnectivityManager {
                public NetworkCapabilities[] caps; public ConnectivityManager(NetworkCapabilities... c){caps=c;}
                public Network[] getAllNetworks(){Network[] n=new Network[caps.length];for(int i=0;i<n.length;i++)n[i]=new Network(i);return n;}
                public NetworkCapabilities getNetworkCapabilities(Network n){return caps[n.index];} }''',
            'android/net/NetworkCapabilities.java': '''package android.net; public class NetworkCapabilities {
                public static final int TRANSPORT_WIFI=1; boolean wifi; Object info;
                public NetworkCapabilities(boolean w,Object i){wifi=w;info=i;}
                public boolean hasTransport(int t){return wifi;}
                public Object getTransportInfo(){if(android.os.Build.VERSION.SDK_INT<29)throw new AssertionError("legacy API called");return info;} }''',
            'android/net/wifi/WifiInfo.java': 'package android.net.wifi; public class WifiInfo { int rssi; public WifiInfo(int r){rssi=r;} public int getRssi(){return rssi;} }',
            'WifiSourceTest.java': '''package com.inceptless.traceweave.edge;
                import android.net.*;import android.net.wifi.WifiInfo;
                public class WifiSourceTest { public static void main(String[] args){
                    ConnectivityManager manager=new ConnectivityManager(null,new NetworkCapabilities(false,new WifiInfo(-20)),
                        new NetworkCapabilities(true,null),new NetworkCapabilities(true,new WifiInfo(-127)),
                        new NetworkCapabilities(true,new WifiInfo(-80)),new NetworkCapabilities(true,new WifiInfo(-50)));
                    NativeWifiSource.Reading r=NativeWifiSource.read(manager);
                    if(!r.transportPresent || !r.infoAvailable || r.rssiDbm!=-50)throw new AssertionError("strongest valid Wi-Fi selection");
                    android.os.Build.VERSION.SDK_INT=26;r=NativeWifiSource.read(manager);
                    if(!r.transportPresent || r.infoAvailable || !Double.isNaN(r.rssiDbm))throw new AssertionError("legacy missing policy");
                    android.os.Build.VERSION.SDK_INT=35;
                    r=NativeWifiSource.read(new ConnectivityManager(new NetworkCapabilities(true,new Object())));
                    if(r.infoAvailable || !Double.isNaN(r.rssiDbm))throw new AssertionError("non-Wifi transport info");
                    r=NativeWifiSource.read(new ConnectivityManager());
                    if(r.transportPresent || !Double.isNaN(r.rssiDbm))throw new AssertionError("disconnected network");
                } }''',
        }
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for name, text in stubs.items():
                path = Path(directory, name)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
                paths.append(str(path))
            subprocess.run(['javac', '-d', directory, str(source), *paths], check=True, capture_output=True)
            subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.WifiSourceTest'], check=True, capture_output=True)

    def test_rssi_scaling_invalid_samples_and_expiry(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        source = Path(__file__).resolve().parents[1] / 'android/src/com/inceptless/traceweave/edge/FeatureReadings.java'
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory, 'WifiReadingsTest.java')
            harness.write_text('''package com.inceptless.traceweave.edge;
public final class WifiReadingsTest {
    public static void main(String[] args) {
        FeatureReadings readings = new FeatureReadings();
        for (double[] sample : new double[][]{{-126,0},{-100,0},{-65,0.5},{-30,1},{0,1}}) {
            readings.wifiRssi(sample[0],1000000000L);
            FeatureReadings.Reading value=readings.get("conn_wifi_rssi_norm",1000000000L);
            if(value==null || value.value!=sample[1] || !value.kind.equals("derived"))
                throw new AssertionError("RSSI units or provenance changed");
        }
        if(readings.get("conn_wifi_rssi_norm",6000000001L)!=null)
            throw new AssertionError("stale RSSI retained");
        for(double invalid:new double[]{-127,-200,1,Double.NaN,Double.POSITIVE_INFINITY}) {
            readings.wifiRssi(-65,1000000000L);
            readings.wifiRssi(invalid,2000000000L);
            if(readings.get("conn_wifi_rssi_norm",2000000000L)!=null)
                throw new AssertionError("invalid RSSI replaced with measurement");
        }
    }
}''')
            compiled = subprocess.run(['javac', '-d', directory, str(source), str(source.with_name('PyRuntime.java')), str(harness)], capture_output=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr.decode())
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.WifiReadingsTest'], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
