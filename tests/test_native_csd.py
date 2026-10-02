from datetime import datetime, timedelta, timezone
from pathlib import Path
import math
import random
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from traceform.representation.manifold_lattice import DynamicalEnergyManifold
from traceform.representation.normalizer import MultimodalNormalizer
from traceform.streaming.edge_manifold_daemon import EdgeManifoldDaemon


class TestNativeCsd(unittest.TestCase):
    def test_python_detector_parity_and_continuity_reset(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        rng = random.Random(42)
        manifold = DynamicalEnergyManifold()
        daemon = EdgeManifoldDaemon(MultimodalNormalizer(), manifold)
        samples, expected = [], []
        alerts = 0
        for index in range(700):
            stage = index if index < 400 else index - 400
            u = 1 + (rng.gauss(0, 0.02) if stage < 140 else 3 * math.sin(stage * 0.05))
            v = 1 + (rng.gauss(0, 0.02) if stage < 140 else 2 * math.cos(stage * 0.04))
            token = 'B' if index % 200 == 199 else 'A'
            instant = datetime(2026, 10, 1, tzinfo=timezone.utc) + timedelta(seconds=index + (300 if index >= 400 else 0))
            with patch.object(manifold, 'quantize', return_value=(token, (u, v), 0)):
                payload, events = daemon.process_observation({'timestamp_utc': instant.isoformat()})
            tipping = any(event.event_type == 'CSD_TIPPING' for event in events)
            alerts += tipping
            expected.append((payload['dwell_steps'], tipping,
                             daemon.var_history[-1] if daemon.var_history else 0,
                             daemon.ar1_history[-1] if daemon.ar1_history else 0))
            samples.append(f'{token} {u!r} {v!r} {str(index > 0 and index != 400).lower()}')
        self.assertGreater(alerts, 0)
        harness = '''package org.traceform.edge;
public final class CsdTest {
    public static void main(String[] args) {
        CriticalSlowingDetector detector = new CriticalSlowingDetector();
        java.util.Scanner input = new java.util.Scanner(System.in).useLocale(java.util.Locale.ROOT);
        while (input.hasNext()) {
            CriticalSlowingDetector.Result result = detector.update(input.next(), input.nextDouble(), input.nextDouble(), input.nextBoolean());
            System.out.println(result.dwellSteps + " " + result.tipping + " " + result.variance + " " + result.lag1);
        }
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            boolean rejected = false;
            try { detector.update("A", value, 0, true); } catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("nonfinite coordinate");
        }
        detector.reset();
        CriticalSlowingDetector.Result first = detector.update("A", 1, 1, true);
        if (first.dwellSteps != 1 || first.ready || first.tipping) throw new AssertionError("reset");
    }
}'''
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'CsdTest.java'
            source.write_text(harness)
            subprocess.run(['javac', '-d', directory, str(root / 'android/src/org/traceform/edge/CriticalSlowingDetector.java'), str(source)], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.CsdTest'],
                                    input='\n'.join(samples), text=True, check=True, capture_output=True)
        actual = result.stdout.splitlines()
        self.assertEqual(len(actual), len(expected))
        for line, (dwell, tipping, variance, lag1) in zip(actual, expected):
            fields = line.split()
            self.assertEqual(int(fields[0]), dwell)
            self.assertEqual(fields[1] == 'true', tipping)
            self.assertAlmostEqual(float(fields[2]), variance, places=10)
            self.assertAlmostEqual(float(fields[3]), lag1, places=10)
