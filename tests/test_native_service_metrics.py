import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HARNESS = '''package com.inceptless.traceweave.edge;
public class MetricsTest {
  static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
  public static void main(String[] args) {
    ServiceMetrics m = new ServiceMetrics();
    check(m.ticks() == 0 && Double.isNaN(m.meanMillis()) && Double.isNaN(m.percentileMillis(0.95)), "empty");
    for (int i = 0; i < 90; i++) m.recordTick(3.0);          // bucket < 5 ms
    for (int i = 0; i < 9; i++) m.recordTick(40.0);          // bucket < 50 ms
    m.recordTick(1500.0);                                      // over the 1 s sampling period
    check(m.ticks() == 100, "tick count");
    check(m.ticksOverBudget() == 1, "over budget counts ticks that cannot fit in the 1 s period");
    check(Math.abs(m.meanMillis() - (90 * 3.0 + 9 * 40.0 + 1500.0) / 100) < 1e-9, "mean");
    check(m.maxMillis() == 1500.0, "max");
    check(m.percentileMillis(0.50) == 5.0, "p50 is the upper edge of its bucket: " + m.percentileMillis(0.50));
    check(m.percentileMillis(0.95) == 50.0, "p95 upper edge: " + m.percentileMillis(0.95));
    check(m.percentileMillis(1.0) == 1500.0, "p100 is the observed maximum, not a bucket edge");
    long[] histogram = m.histogram();
    check(histogram[0] == 90 && histogram[3] == 9 && histogram[histogram.length - 1] == 1, "histogram");
    m.recordTick(Double.NaN); m.recordTick(-1.0);
    check(m.ticks() == 100, "invalid durations are ignored");
    m.recordMemory(50000, 12000, 20000);
    m.recordMemory(60000, 11000, 25000);
    m.recordMemory(55000, 13000, 21000);
    check(m.memorySamples() == 3, "memory samples");
    check(m.pssKbMax() == 60000 && m.pssKbLast() == 55000 && m.pssKbFirst() == 50000, "pss tracking");
    check(m.javaHeapKbMax() == 13000 && m.nativeHeapKbMax() == 25000, "heap maxima");
    m.recordMemory(-1, -1, -1);
    check(m.memorySamples() == 3, "unavailable memory readings are ignored");
    ServiceMetrics p = new ServiceMetrics();
    p.recordPhase(0, 10.0); p.recordPhase(0, 30.0); p.recordPhase(2, 8.0);
    p.recordPhase(99, 5.0); p.recordPhase(1, Double.NaN); p.recordPhase(1, -3.0);
    check(p.phaseMeanMillis(0) == 20.0 && p.phaseMeanMillis(2) == 8.0, "phase means");
    check(Double.isNaN(p.phaseMeanMillis(1)) && Double.isNaN(p.phaseMeanMillis(99)), "unused or invalid phases have no mean");
    check(p.phaseMaxMillis(0) == 30.0, "phase max");
    check(ServiceMetrics.PHASES.length == 6, "six named phases");
  }
}
'''


class TestServiceMetrics(unittest.TestCase):
    def test_tick_histogram_percentiles_and_memory_tracking(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java toolchain required')
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / 'MetricsTest.java'
            harness.write_text(HARNESS)
            compiled = subprocess.run(['javac', '--release', '8', '-d', directory,
                                       str(ROOT / 'android/src/com/inceptless/traceweave/edge/ServiceMetrics.java'), str(harness)],
                                      capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.MetricsTest'],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
