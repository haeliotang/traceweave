from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeSampleClock(unittest.TestCase):
    def test_deadlines_exclude_processing_time_and_skip_missed_ticks(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "ClockTest.java"
            harness.write_text('''package org.traceform.edge;
public final class ClockTest {
    public static void main(String[] args) {
        SampleClock clock = new SampleClock(1000);
        if (clock.next(1100) != 2000) throw new AssertionError("processing delay");
        if (clock.next(2150) != 3000) throw new AssertionError("accumulated delay");
        if (clock.next(6200) != 7000 || clock.skipped != 3) throw new AssertionError("missed deadlines");
        if (clock.next(7000) != 8000) throw new AssertionError("cutoff equality");
    }
}''')
            subprocess.run(["javac", "-d", directory, str(root / "android/src/org/traceform/edge/SampleClock.java"), str(harness)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "org.traceform.edge.ClockTest"], check=True, capture_output=True)
