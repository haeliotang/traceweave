from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeWifiReadings(unittest.TestCase):
    def test_rssi_scaling_invalid_samples_and_expiry(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        source = Path(__file__).resolve().parents[1] / 'android/src/org/traceform/edge/FeatureReadings.java'
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory, 'WifiReadingsTest.java')
            harness.write_text('''package org.traceform.edge;
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
            compiled = subprocess.run(['javac', '-d', directory, str(source), str(harness)], capture_output=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr.decode())
            result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.WifiReadingsTest'], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
