# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""End to end: native-shaped samples -> ReferenceFeatureSupplier (Java) versus the Python reference.

Java builds the observation from FeatureReadings, the usage window and the Android-side inputs, runs
the ported extractor and records the values. Python then recomputes every value from the very
observation Java handed to the extractor. This checks the observation builder, the engine and the
provenance kinds together; it does not claim that native sensors agree with Python's acquisition
beyond the fields compared explicitly in test_motion_fields_follow_the_python_acquisition_formulas.
"""
import json
import math
import os
import random
import re
import shutil
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path

import parity_support as parity
from traceform.representation.normalizer import MultimodalNormalizer

ROOT = Path(__file__).resolve().parents[1]
EDGE = ROOT / 'android/src/com/inceptless/traceweave/edge'
JAR = Path(os.environ.get('TRACEFORM_ANDROID_JAR', '/private/tmp/traceform-android-tools/platform/android-35/android.jar'))
SOURCES = ['PyRuntime.java', 'FeatureReadings.java', 'NativeUsageSource.java', 'ReferenceDigitalProbe.java',
           'ReferenceUsageFeatures.java', 'ReferenceSignalFeatures.java', 'ReferenceDynamicsFeatures.java',
           'ReferenceFeatureEngine.java', 'ReferenceFeatureKinds.java', 'ReferenceObservation.java',
           'ReferenceFeatureSupplier.java']
EVENT_TYPES = [1, 1, 1, 15, 18, 7, 23]  # resumed x3, interactive, keyguard hidden, others
PACKAGES = ['app.one', 'app.two', 'app.three', 'app.four', None]
SCREENS = ['ON', 'ON', 'OFF', 'DOZING', 'UNKNOWN']
BASE_EPOCH_MS = 1790900000000


def native_samples(seed, count=240):
    rng = random.Random(seed)
    frames = [{'__reset': True}]
    wall, nanos = BASE_EPOCH_MS + rng.randint(0, 5 * 86400000), 10_000_000_000
    pressure, temp, battery = 913.0, 31.0, 0.8
    for index in range(count):
        gap = rng.choice([30.0] * 10 + [1.0, 4.0, 6.0, 31.5, 299.0, 301.0, 700.0, 3700.0])
        wall += int(gap * 1000)
        nanos += int(gap * 1e9)
        if rng.random() < 0.01:
            frames.append({'__reset': True})
        pressure = min(925.0, max(905.0, pressure + rng.uniform(-0.4, 0.4)))
        temp = min(42.0, max(25.0, temp + rng.uniform(-0.3, 0.3)))
        battery = min(1.0, max(0.05, battery + rng.uniform(-0.002, 0.001)))
        moving = rng.random() < 0.35
        sample = {
            'wall': wall, 'nanos': nanos,
            'accel': [round(rng.gauss(0, 0.5 if moving else 0.03), 4), round(rng.gauss(0, 0.5 if moving else 0.03), 4),
                      round(9.8 + rng.gauss(0, 0.6 if moving else 0.03), 4)],
            'gyro': [round(rng.gauss(0, 0.6 if moving else 0.01), 4) for _ in range(3)],
            'lux': round(rng.lognormvariate(4, 1.5), 2), 'pressure': round(pressure, 3), 'temp': round(temp, 2),
            'battery': round(battery, 3), 'plugged': float(rng.random() < 0.3), 'charging': float(rng.random() < 0.2),
            'current': float(rng.randint(-1_200_000, 600_000)), 'voltage': float(rng.randint(3600, 4400)),
            'rssi': float(rng.randint(-90, -35)), 'wifi': float(rng.random() < 0.9),
            'headset': rng.random() < 0.2, 'screen': rng.choice(SCREENS),
            'events': [[rng.choice(EVENT_TYPES), rng.choice(PACKAGES)] for _ in range(rng.choice([0, 3, 40, 150]))],
        }
        for key in ('accel', 'gyro', 'lux', 'pressure', 'temp', 'battery', 'current', 'voltage', 'rssi', 'events'):
            if rng.random() < 0.05:
                sample[key] = None  # a source that produced nothing this tick
        frames.append(sample)
    return frames


def run_java(frames, directory):
    classes = Path(directory) / 'classes'
    classes.mkdir()
    paths = [str(EDGE / name) for name in SOURCES] + [str(ROOT / 'tests/java_parity/MiniJson.java'),
                                                       str(ROOT / 'tests/java_parity/NativeSupplyHarness.java')]
    compiled = subprocess.run(['javac', '-cp', str(JAR), '-d', str(classes)] + paths, capture_output=True, text=True)
    if compiled.returncode != 0:
        raise RuntimeError(compiled.stderr)
    path = Path(directory) / 'native.jsonl'
    parity.write_sequence(frames, path)
    result = subprocess.run(['java', '-cp', f'{classes}{os.pathsep}{JAR}', 'com.inceptless.traceweave.edge.NativeSupplyHarness', str(path)],
                            capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        raise RuntimeError(result.stderr)
    return result.stdout.splitlines()


def needs_java(test):
    def wrapper(self):
        if not shutil.which('javac') or not shutil.which('java') or not JAR.is_file():
            self.skipTest('Java toolchain and Android API jar required')
        return test(self)
    return wrapper


class TestNativeReferenceSupply(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = []
        if not shutil.which('javac') or not JAR.is_file():
            return
        with tempfile.TemporaryDirectory() as directory:
            for seed in (11, 12, 13):
                frames = native_samples(seed)
                lines = run_java(frames, Path(directory) / f's{seed}') if (Path(directory) / f's{seed}').mkdir() is None else []
                cls.rows.append((frames, lines))

    @needs_java
    def test_values_match_python_computed_from_the_same_observation(self):
        names = None
        total = 0
        problems = []
        for frames, lines in self.rows:
            normalizer = MultimodalNormalizer()
            for frame, line in zip(frames, lines):
                if line.startswith('#RESET'):
                    normalizer.reset_state()
                    continue
                record = json.loads(line.replace('NaN', 'null'))
                if not record['ran']:
                    continue  # values are held between runs; the run itself is what Python is compared with
                mapping, _ = normalizer.extract_raw_features(record['obs'])
                for name, value in record['values'].items():
                    total += 1
                    want = mapping[name]
                    if abs(want - value) > 1e-9 * max(1.0, abs(want)) and abs(want - value) > 0.0011:
                        problems.append(f'{name}: python {want!r} java {value!r}')
        self.assertEqual(problems[:10], [])
        self.assertGreater(total, 8000)

    @needs_java
    def test_the_extractor_runs_at_the_thirty_second_frame_cadence_and_values_are_held_between_runs(self):
        frames = [{'__reset': True}]
        base = native_samples(5, count=1)[1]
        for second in range(95):
            sample = dict(base, wall=base['wall'] + second * 1000, nanos=base['nanos'] + second * 1_000_000_000)
            frames.append(sample)
        with tempfile.TemporaryDirectory() as directory:
            lines = run_java(frames, directory)
        records = [json.loads(l.replace('NaN', 'null')) for l in lines if not l.startswith('#')]
        ran_at = [i for i, r in enumerate(records) if r['ran']]
        self.assertEqual(ran_at, [0, 30, 60, 90])
        for index, record in enumerate(records):
            if not record['ran']:
                previous = max(i for i in ran_at if i < index)
                held = {k: v for k, v in record['values'].items() if k in records[previous]['values']}
                self.assertEqual(held, {k: records[previous]['values'][k] for k in held}, index)
        self.assertEqual(len(records[29]['values']), len(records[0]['values']))  # still readable just before the next run

    @needs_java
    def test_held_values_expire_if_the_service_stops_sampling(self):
        frames = [{'__reset': True}] + [dict(native_samples(6, count=1)[1], wall=BASE_EPOCH_MS, nanos=10_000_000_000)]
        later = dict(frames[1], wall=BASE_EPOCH_MS + 40_000, nanos=50_000_000_000)
        frames.append(later)
        with tempfile.TemporaryDirectory() as directory:
            lines = run_java(frames, directory)
        second = json.loads(lines[-1].replace('NaN', 'null'))
        self.assertTrue(second['ran'])  # a 40 s gap is a new run, so nothing stale is served

    @needs_java
    def test_every_ported_output_is_supplied_on_ordinary_samples(self):
        seen = set()
        for _, lines in self.rows:
            for line in lines:
                if not line.startswith('#'):
                    seen |= set(json.loads(line.replace('NaN', 'null'))['values'])
        expected = set(parity_names())
        self.assertEqual(sorted(expected - seen), [])
        self.assertEqual(len(expected), 51)

    @needs_java
    def test_kinds_follow_the_generated_table_and_are_never_observed(self):
        kinds = {}
        for _, lines in self.rows:
            for line in lines:
                if not line.startswith('#'):
                    kinds.update(json.loads(line.replace('NaN', 'null'))['kinds'])
        self.assertEqual(set(kinds.values()), {'proxy', 'derived'})
        table = (EDGE / 'ReferenceFeatureKinds.java').read_text()
        proxies = set(re.findall(r'"([a-z0-9_]+)"', table.split('Arrays.asList(')[1].split('));')[0]))
        for name, kind in kinds.items():
            self.assertEqual(kind, 'proxy' if name in proxies else 'derived', name)

    @needs_java
    def test_observation_contains_only_declared_fields_and_no_package_names(self):
        declared = set(re.findall(r'"([A-Za-z0-9_.]+)"', (EDGE / 'ReferenceObservation.java').read_text().split('FIELDS = {')[1].split('};')[0]))
        seen = set()
        def walk(node, prefix=''):
            for key, value in node.items():
                path = f'{prefix}.{key}' if prefix else key
                if isinstance(value, dict):
                    walk(value, path)
                else:
                    seen.add(path)
        for _, lines in self.rows:
            for line in lines:
                if line.startswith('#'):
                    continue
                observation = json.loads(line.replace('NaN', 'null'))['obs']
                walk(observation)
                self.assertNotIn('app.one', line)
        self.assertLessEqual(seen, declared)
        self.assertGreaterEqual(len(seen), len(declared) - 4)  # a few fields depend on optional sources

    @needs_java
    def test_motion_fields_follow_the_python_acquisition_formulas(self):
        mismatches = 0
        checked = 0
        for frames, lines in self.rows:
            for frame, line in zip(frames, lines):
                if line.startswith('#') or not frame.get('accel') or not frame.get('gyro'):
                    continue
                record = json.loads(line.replace('NaN', 'null'))
                if not record['ran']:
                    continue  # the observation shown belongs to the previous run
                motion = record['obs'].get('motion', {})
                if 'motion_energy' not in motion:
                    continue
                # sensors deliver float32: give the Python formulas the same values the service receives
                ax, ay, az = (struct.unpack('f', struct.pack('f', v))[0] for v in frame['accel'])
                gx, gy, gz = (struct.unpack('f', struct.pack('f', v))[0] for v in frame['gyro'])
                accel_mag = math.sqrt(ax * ax + ay * ay + az * az)
                gyro_energy = gx * gx + gy * gy + gz * gz
                energy = round(abs(accel_mag - 9.80665) + math.sqrt(gyro_energy), 4)
                expected = {'accel_magnitude': round(accel_mag, 4), 'accel_rms': round(math.sqrt((ax * ax + ay * ay + az * az) / 3.0), 4),
                            'gyro_energy': round(gyro_energy, 6), 'motion_energy': energy,
                            'stationary_likelihood': round(max(0.0, min(1.0, 1.0 - energy / 2.0)), 3)}
                for key, value in expected.items():
                    checked += 1
                    if abs(motion[key] - value) > 1e-9:
                        mismatches += 1
        self.assertGreater(checked, 400)
        self.assertEqual(mismatches, 0)


def parity_names():
    names = []
    for group in ('ReferenceUsageFeatures', 'ReferenceSignalFeatures', 'ReferenceDynamicsFeatures'):
        block = re.search(r'NAMES\s*=\s*\{(.*?)\};', (EDGE / f'{group}.java').read_text(), re.S).group(1)
        names += re.findall(r'"([a-z0-9_]+)"', block)
    return names


if __name__ == '__main__':
    unittest.main()


class TestGeneratedTables(unittest.TestCase):
    def test_generated_java_tables_are_in_sync_with_the_analysis(self):
        result = subprocess.run(['python3', str(ROOT / 'scripts/generate_reference_feature_kinds.py'), '--check'], cwd=ROOT)
        self.assertEqual(result.returncode, 0, 'run scripts/generate_reference_feature_kinds.py')

    def test_statically_unobservable_names_are_not_ported_features(self):
        table = (EDGE / 'StaticallyUnobservableFeatures.java').read_text()
        names = set(re.findall(r'"([a-z0-9_]{8,})"', table.split('Arrays.asList(')[1].split('));')[0]))
        self.assertGreaterEqual(len(names), 10)
        self.assertEqual(names & set(parity_names()), set())
