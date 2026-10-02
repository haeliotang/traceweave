from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeCalibrationGate(unittest.TestCase):
    def test_distance_coverage_topology_and_finite_boundaries(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        harness = '''package org.traceform.edge;
public final class GateTest {
    public static void main(String[] args) {
        if(!CalibrationGate.accepts(1,0.94,3,2,2,2)) throw new AssertionError("valid improvement");
        if(CalibrationGate.accepts(1,0.95,3,2,2,2)) throw new AssertionError("exact five percent accepted");
        if(CalibrationGate.accepts(1,0.96,3,2,2,2)) throw new AssertionError("insufficient improvement");
        if(CalibrationGate.accepts(1,0.90,3,1,2,2)) throw new AssertionError("coverage reduction");
        if(CalibrationGate.accepts(1,0.90,3,2,2,3)) throw new AssertionError("topology deterioration");
        if(!CalibrationGate.accepts(1,0.90,1,1,0,0)) throw new AssertionError("single-state coverage");
        if(CalibrationGate.accepts(0,0,1,1,0,0)) throw new AssertionError("zero baseline gain");
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1}) {
            if(CalibrationGate.accepts(value,0.9,2,2,0,0) || CalibrationGate.accepts(1,value,2,2,0,0))
                throw new AssertionError("invalid score accepted");
        }
        if(CalibrationGate.accepts(1,0.9,0,2,0,0) || CalibrationGate.accepts(1,0.9,2,0,0,0)
                || CalibrationGate.accepts(1,0.9,2,2,-1,0) || CalibrationGate.accepts(1,0.9,2,2,0,-1))
            throw new AssertionError("invalid counters accepted");
    }
}'''
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'GateTest.java'
            source.write_text(harness)
            subprocess.run(['javac', '-d', directory, str(root / 'android/src/org/traceform/edge/CalibrationGate.java'), str(source)], check=True, capture_output=True)
            subprocess.run(['java', '-cp', directory, 'org.traceform.edge.GateTest'], check=True, capture_output=True)
