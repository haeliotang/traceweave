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
        harness = '''package com.inceptless.traceweave.edge;
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
            subprocess.run(['javac', '-d', directory, str(root / 'android/src/com/inceptless/traceweave/edge/CriticalSlowingDetector.java'), str(source)], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.CsdTest'],
                                    input='\n'.join(samples), text=True, check=True, capture_output=True)
        actual = result.stdout.splitlines()
        self.assertEqual(len(actual), len(expected))
        for line, (dwell, tipping, variance, lag1) in zip(actual, expected):
            fields = line.split()
            self.assertEqual(int(fields[0]), dwell)
            self.assertEqual(fields[1] == 'true', tipping)
            self.assertAlmostEqual(float(fields[2]), variance, places=10)
            self.assertAlmostEqual(float(fields[3]), lag1, places=10)

    def test_continuous_detector_parity_inside_constant_token(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        manifold = DynamicalEnergyManifold()
        daemon = EdgeManifoldDaemon(MultimodalNormalizer(), manifold, csd_input_space='continuous')
        rng = random.Random(42)
        samples, expected = [], []
        alerts = 0
        for index in range(1500):
            value = 0.5 + (rng.uniform(-0.001, 0.001) if index < 100 else 0.3 * math.sin((index - 100) / 4))
            def quantize(*args, **kwargs):
                manifold.last_obs_vector = [value + 0.01 * math.sin(axis + index) for axis in range(128)]
                return 'M01', (1, 1), 0
            instant = datetime(2026, 10, 1, tzinfo=timezone.utc) + timedelta(seconds=index + (300 if index >= 750 else 0))
            with patch.object(manifold, 'quantize', side_effect=quantize):
                frame, events = daemon.process_observation({'timestamp_utc': instant.isoformat()})
            tipping = any(event.event_type == 'CSD_TIPPING' for event in events)
            alerts += tipping
            expected.append((frame['dwell_steps'], tipping,
                             daemon.var_history[-1] if daemon.var_history else 0,
                             daemon.ar1_history[-1] if daemon.ar1_history else 0))
            samples.append(f'{index} {value!r} {str(index > 0 and index != 750).lower()}')
        self.assertGreater(alerts, 0)
        harness = '''package com.inceptless.traceweave.edge;
public final class ContinuousCsdTest {
    public static void main(String[] args) {
        CriticalSlowingDetector detector = new CriticalSlowingDetector();
        java.util.Scanner input = new java.util.Scanner(System.in).useLocale(java.util.Locale.ROOT);
        while(input.hasNext()) {
            int index=input.nextInt();double value=input.nextDouble();
            double[] vector=new double[128];for(int axis=0;axis<vector.length;axis++)vector[axis]=value+0.01*Math.sin(axis+index);
            CriticalSlowingDetector.Result r=detector.updateContinuous("M01",vector,input.nextBoolean());
            System.out.println(r.dwellSteps+" "+r.tipping+" "+r.variance+" "+r.lag1);
        }
        CriticalSlowingDetector.Result changed=detector.updateContinuous("M01",new double[]{0.5,0.5},true);
        if(changed.ready || changed.dwellSteps!=1)throw new AssertionError("dimension change warmup");
        detector.reset();
        for(int i=0;i<350;i++) {
            double[] vector=new double[128];java.util.Arrays.fill(vector,0.5);
            if(detector.updateContinuous("M01",vector,i>0).tipping)throw new AssertionError("constant vector alert");
        }
        for(double[] invalid:new double[][]{new double[0],new double[129],{Double.NaN},{11}}) {
            boolean rejected=false;
            try{detector.updateContinuous("M01",invalid,true);}catch(IllegalArgumentException expected){rejected=true;}
            if(!rejected)throw new AssertionError("invalid normalized vector");
        }
    }
}'''
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'ContinuousCsdTest.java'
            source.write_text(harness)
            compiled = subprocess.run(['javac', '-d', directory, str(root / 'android/src/com/inceptless/traceweave/edge/CriticalSlowingDetector.java'), str(source)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.ContinuousCsdTest'], input='\n'.join(samples), text=True, check=True, capture_output=True)
        self.assertEqual(len(result.stdout.splitlines()), len(expected))
        for line, (dwell, tipping, variance, lag1) in zip(result.stdout.splitlines(), expected):
            fields = line.split()
            self.assertEqual(int(fields[0]), dwell)
            self.assertEqual(fields[1] == 'true', tipping)
            self.assertAlmostEqual(float(fields[2]), variance, places=10)
            self.assertAlmostEqual(float(fields[3]), lag1, places=10)
