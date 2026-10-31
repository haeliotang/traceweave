from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeMemoryPressure(unittest.TestCase):
    def test_units_invalid_input_and_unavailable_source(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / 'pressure'
            fixture.write_text('some avg10=12.50 avg60=8.00 avg300=3.00 total=100\nfull avg10=1.00\n')
            harness = Path(directory) / 'MemoryPressureTest.java'
            harness.write_text('''package com.inceptless.traceweave.edge;
public class MemoryPressureTest {
    public static void main(String[] args) {
        if(NativeMemoryPressure.read(args[0])!=0.125)throw new AssertionError("percent to fraction");
        if(!Double.isNaN(NativeMemoryPressure.read(args[0]+".missing")))throw new AssertionError("unavailable must remain missing");
        if(NativeMemoryPressure.parse("some avg10=0.00")!=0)throw new AssertionError("observed zero");
        if(NativeMemoryPressure.parse("some avg10=100.00")!=1)throw new AssertionError("full stall");
        for(String invalid:new String[]{null,"full avg10=1","some avg60=1","some avg10=NaN","some avg10=Infinity",
            "some avg10=-1","some avg10=101","some avg10=bad","some avg10=1 avg10=2"})
            if(!Double.isNaN(NativeMemoryPressure.parse(invalid)))throw new AssertionError("malformed source: "+invalid);
    }
}''')
            subprocess.run(['javac', '-d', directory, str(root / 'android/src/com/inceptless/traceweave/edge/NativeMemoryPressure.java'), str(harness)], check=True, capture_output=True)
            subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.MemoryPressureTest', str(fixture)], check=True, capture_output=True)
