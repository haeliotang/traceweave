# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Python-versus-Java parity harness for ported feature extraction.

A deterministic observation sequence is fed to the Python reference
(``MultimodalNormalizer.extract_raw_features``) and to a ported Java class, and the
two outputs are compared frame by frame. Sequences cover absent fields, direct
override fields, odd timestamps and gaps, continuity resets and hostile leaf values,
because a port that only matches on tidy input is not a faithful port.

A Java group class lives in ``android/src/com/inceptless/traceweave/edge`` and exposes a
package-visible ``static final String[] NAMES``, ``void reset()`` and
``double[] extract(Map<String,Object> obs)``.
"""
from __future__ import annotations

import copy
import datetime
import json
import math
import random
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence

from traceform.representation.feature_dataflow import analyze_source
from traceform.representation.normalizer import MultimodalNormalizer

ROOT = Path(__file__).resolve().parents[1]
JAVA_SOURCE = ROOT / 'android/src/com/inceptless/traceweave/edge'
PARITY_JAVA = ROOT / 'tests/java_parity'
HARNESS_CLASS = 'com.inceptless.traceweave.edge.ParityHarness'

_DATAFLOW = analyze_source((ROOT / 'research/traceform/representation/normalizer.py').read_text())

# Fields the native service can obtain from its own sources (used by the "native" profile).
NATIVE_PATHS = {
    'timestamp_utc', 'motion.accel_magnitude', 'motion.accel_rms', 'motion.motion_energy',
    'motion.stationary_likelihood', 'motion.accel_raw', 'motion.gyro_raw', 'motion.gyro_energy',
    'environment.ambient_light_lux', 'environment.pressure_hpa', 'environment.device_temp_c',
    'device_relation.battery_percentage', 'device_relation.current_ua', 'device_relation.voltage_mv',
    'device_relation.plugged', 'device_relation.status', 'device_relation.screen_state',
    'device_relation.wired_headset', 'device_relation.bluetooth_a2dp',
    'connectivity.wifi_rssi', 'connectivity.wifi_connected', 'connectivity.wifi_frequency_mhz',
    'digital_context.app_switch_rate', 'digital_context.unlock_burst_count', 'digital_context.screen_on_ratio',
}

LIST3 = {'motion.accel_raw', 'motion.gyro_raw', 'motion.mag_raw', 'motion.orientation_degrees'}
BOOLEANS = {'connectivity.wifi_connected', 'device_relation.wired_headset', 'device_relation.bluetooth_a2dp'}
STRINGS = {
    'device_relation.plugged': ['UNPLUGGED', 'USB', 'AC', 'WIRELESS'],
    'device_relation.status': ['DISCHARGING', 'CHARGING', 'FULL', 'NOT_CHARGING'],
    'device_relation.screen_state': ['ON', 'DOZING', 'OFF', 'UNKNOWN'],
    'audio_context.status': ['AVAILABLE', 'NOT_SAMPLED', 'UNAVAILABLE'],
    'connectivity.cellular_cell_fingerprint': ['cell-a', 'cell-b', 'cell-c', 'NO_CELL'],
    'connectivity.wifi_fingerprint': ['wf-a', 'wf-b'],
    'connectivity.wifi_environment_fingerprint': ['we-a', 'we-b'],
    'place.spatial_cell': ['tv58w8n', 'tv58w8p', 'NO_GEO'],
}
RANGES = {
    'motion.accel_magnitude': (9.2, 10.6), 'motion.accel_rms': (0.0, 6.0), 'motion.motion_energy': (0.0, 3.0),
    'motion.stationary_likelihood': (0.0, 1.0), 'motion.gyro_energy': (0.0, 0.6),
    'environment.ambient_light_lux': (0.0, 3000.0), 'environment.pressure_hpa': (905.0, 925.0),
    'environment.device_temp_c': (24.0, 42.0), 'environment.proximity_cm': (0.0, 5.0),
    'device_relation.battery_percentage': (5, 100), 'device_relation.current_ua': (-1500000, 1500000),
    'device_relation.voltage_mv': (3500, 4450), 'connectivity.wifi_rssi': (-95, -30),
    'connectivity.wifi_frequency_mhz': (2412, 5805), 'connectivity.wifi_ap_count': (0, 40),
    'digital_context.screen_on_ratio': (0.0, 1.0), 'digital_context.app_switch_rate': (0, 50),
    'digital_context.unlock_burst_count': (0, 20), 'place.latitude': (27.60, 27.80),
    'place.longitude': (85.20, 85.50),
}


def observation_paths() -> List[str]:
    """Every observation field the extractor can read, leaves only."""
    paths = set()
    for feature in _DATAFLOW['features']:
        paths.update(feature['observation_inputs'])
        paths.update(feature['history_observation_inputs'])
        paths.update(feature['control_observation_inputs'])
    paths.discard('timestamp_utc')
    leaves = {p for p in paths if not any(other.startswith(p + '.') for other in paths)}
    return sorted(leaves)


def _numeric(rng: random.Random, path: str, walk: Dict[str, float]):
    low, high = RANGES.get(path, (0.0, 1.0))
    integer = isinstance(low, int) and isinstance(high, int)
    if path in ('environment.pressure_hpa', 'environment.device_temp_c', 'device_relation.battery_percentage'):
        span = (high - low) * 0.02
        value = walk.get(path, rng.uniform(low, high)) + rng.uniform(-span, span)
        value = min(high, max(low, value))
        walk[path] = value
    else:
        value = rng.uniform(low, high)
    return int(round(value)) if integer else round(value, rng.choice([2, 3, 4, 6]))


def _leaf(rng: random.Random, path: str, walk: Dict[str, float]):
    if path in LIST3:
        scale = 9.8 if path == 'motion.accel_raw' else 1.0
        return [round(rng.gauss(0, scale * 0.3), 4), round(rng.gauss(0, scale * 0.3), 4),
                round(rng.gauss(scale if path == 'motion.accel_raw' else 0, scale * 0.3), 4)]
    if path in BOOLEANS:
        return rng.random() < 0.5
    if path in STRINGS:
        return rng.choice(STRINGS[path])
    return _numeric(rng, path, walk)


def _hostile(rng: random.Random, value):
    choice = rng.choice(['none', 'nan', 'inf', 'numeric_string', 'text', 'true', 'zero', 'negative', 'huge'])
    if isinstance(value, list):
        return rng.choice([None, [], [value[0]], value + [1.0], 'oops'])
    return {'none': None, 'nan': float('nan'), 'inf': float('inf'), 'numeric_string': '3.5', 'text': 'abc',
            'true': True, 'zero': 0, 'negative': -7.5, 'huge': 1e12}[choice]


def _set(frame: dict, path: str, value) -> None:
    node = frame
    parts = path.split('.')
    for part in parts[:-1]:
        node = node.setdefault(part, {})
    node[parts[-1]] = value


def generate_sequence(seed: int, frames: int = 300, profile: str = 'full',
                      paths: Optional[Sequence[str]] = None) -> List[dict]:
    """Deterministic frames. Profiles: native, full, sparse, hostile."""
    rng = random.Random(seed)
    chosen = list(paths) if paths is not None else observation_paths()
    walk: Dict[str, float] = {}
    clock = datetime.datetime(2026, 10, 2, 0, 0, 0, tzinfo=datetime.timezone.utc) + datetime.timedelta(
        seconds=rng.randint(0, 6 * 86400))
    result: List[dict] = [{'__reset': True}]
    gaps = [30.0] * 12 + [29.4, 31.1, 1.0, 4.0, 5.0, 7.0, 120.0, 299.0, 301.0, 599.0, 601.0, 3599.0, 3601.0]
    for _ in range(frames):
        clock += datetime.timedelta(seconds=rng.choice(gaps) + rng.random() * 0.9)
        if rng.random() < 0.01:
            result.append({'__reset': True})
        frame: dict = {}
        stamp = clock.isoformat(timespec='microseconds').replace('+00:00', 'Z')
        roll = rng.random()
        if roll < 0.02:
            pass  # timestamp missing
        elif roll < 0.03:
            frame['timestamp_utc'] = 'not-a-time'
        elif roll < 0.035:
            frame['timestamp_utc'] = (clock - datetime.timedelta(seconds=rng.choice([1, 40, 4000]))).isoformat(
                timespec='microseconds').replace('+00:00', 'Z')  # time moving backwards
        else:
            frame['timestamp_utc'] = stamp
        for path in chosen:
            if profile == 'native':
                present = path in NATIVE_PATHS
            elif profile == 'sparse':
                present = rng.random() < 0.5
            else:
                known = path in RANGES or path in LIST3 or path in BOOLEANS or path in STRINGS
                present = rng.random() < (0.95 if known else 0.2)
            if not present:
                continue
            value = _leaf(rng, path, walk)
            if profile == 'hostile' and rng.random() < 0.06:
                value = _hostile(rng, value)
            _set(frame, path, value)
        result.append(frame)
    return result


def write_sequence(frames: Iterable[dict], path: Path) -> None:
    with path.open('w') as handle:
        for frame in frames:
            handle.write(json.dumps(frame, allow_nan=True) + '\n')


def run_python(frames: Sequence[dict], names: Sequence[str]) -> List[Optional[List[float]]]:
    """Reference outputs; None marks a reset or a frame on which Python itself raised."""
    normalizer = MultimodalNormalizer()
    outputs: List[Optional[List[float]]] = []
    for frame in frames:
        if '__reset' in frame:
            normalizer.reset_state()
            outputs.append(None)
            continue
        try:
            mapping, _ = normalizer.extract_raw_features(copy.deepcopy(frame))
            outputs.append([float(mapping[name]) for name in names])
        except Exception:
            normalizer.reset_state()
            outputs.append(None)
    return outputs


def align_after_python_errors(frames: Sequence[dict], expected: Sequence[Optional[List[float]]]):
    """Mirror Python's reaction to a frame on which it raised.

    run_python resets the reference normalizer after such a frame, so the Java class receives an
    explicit reset marker at the same point; the expected rows stay aligned with the Java output.
    """
    java_frames: List[dict] = []
    aligned: List[Optional[List[float]]] = []
    for frame, row in zip(frames, expected):
        java_frames.append(frame)
        aligned.append(row)
        if row is None and '__reset' not in frame:
            java_frames.append({'__reset': True})
            aligned.append(None)
    return java_frames, aligned


def java_available() -> bool:
    return bool(shutil.which('javac') and shutil.which('java'))


def run_java(class_name: str, java_files: Sequence[str], sequence: Path, workdir: Path):
    """Compile the ported sources with the harness and run them on a sequence file."""
    sources = [JAVA_SOURCE / 'PyRuntime.java'] + [JAVA_SOURCE / name for name in java_files] + [
        PARITY_JAVA / 'MiniJson.java', PARITY_JAVA / 'ParityHarness.java']
    classes = workdir / 'classes'
    if not classes.exists():
        classes.mkdir()
        compiled = subprocess.run(['javac', '-d', str(classes)] + [str(s) for s in sources],
                                  capture_output=True, text=True)
        if compiled.returncode != 0:
            raise RuntimeError('javac failed:\n' + compiled.stderr)
    result = subprocess.run(['java', '-cp', str(classes), HARNESS_CLASS, str(sequence),
                             f'com.inceptless.traceweave.edge.{class_name}'], capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        raise RuntimeError('java failed:\n' + result.stderr)
    lines = result.stdout.splitlines()
    names = lines[0][len('#NAMES '):].split(',')
    rows: List[Optional[List[float]]] = []
    for line in lines[1:]:
        if line.startswith('#RESET'):
            rows.append(None)
        elif line.startswith('#ERROR'):
            rows.append('error')  # type: ignore[arg-type]
        else:
            rows.append([float(token) for token in line.split(',')])
    return names, rows


def compare(names: Sequence[str], frames: Sequence[dict], expected, actual,
            quantum: Optional[Dict[str, float]] = None, flip_budget: float = 0.003) -> Dict[str, object]:
    """Frame-by-frame comparison.

    Values must match to 1e-9 (relative to magnitude). A feature listed in ``quantum`` may instead
    differ by one rounding unit in at most ``flip_budget`` of its values: Python and Java libm can
    disagree in the last bit, which flips an exact decimal tie after round().
    """
    quantum = quantum or {}
    problems: List[str] = []
    flips = {name: 0 for name in names}
    compared = {name: 0 for name in names}
    java_only_errors = 0
    if len(expected) != len(actual):
        return {'problems': [f'frame count differs: python {len(expected)} java {len(actual)}'], 'flips': flips,
                'compared': compared, 'java_only_errors': 0}
    for index, (want, got) in enumerate(zip(expected, actual)):
        if got == 'error':
            if want is not None:
                java_only_errors += 1
                problems.append(f'frame {index}: Java raised where Python did not')
            continue
        if want is None or got is None:
            if (want is None) != (got is None) and want is not None:
                problems.append(f'frame {index}: Java returned no row for a Python value')
            continue
        for name, left, right in zip(names, want, got):
            compared[name] += 1
            if left == right or (math.isnan(left) and math.isnan(right)):
                continue
            scale = max(1.0, abs(left))
            if abs(left - right) <= 1e-9 * scale:
                continue
            step = quantum.get(name)
            if step is not None and abs(left - right) <= step * 1.0000001:
                flips[name] += 1
                continue
            if len(problems) < 25:
                problems.append(f'frame {index} {name}: python {left!r} java {right!r}')
            else:
                problems.append('...')
                break
    for name in names:
        if compared[name] and flips[name] > flip_budget * compared[name]:
            problems.append(f'{name}: {flips[name]} rounding flips in {compared[name]} values exceeds budget')
    return {'problems': [p for p in problems if p != '...'][:25], 'flips': flips, 'compared': compared,
            'java_only_errors': java_only_errors}


def check_group(class_name: str, java_files: Sequence[str], names: Sequence[str], *,
                seeds: Sequence[int] = (1, 2, 3), profiles: Sequence[str] = ('full', 'native', 'sparse', 'hostile'),
                frames: int = 300, quantum: Optional[Dict[str, float]] = None) -> Dict[str, object]:
    """Run every seed/profile combination and return the merged comparison report."""
    report = {'problems': [], 'compared': {name: 0 for name in names}, 'flips': {name: 0 for name in names},
              'java_only_errors': 0, 'runs': 0}
    with tempfile.TemporaryDirectory() as directory:
        workdir = Path(directory)
        for profile in profiles:
            for seed in seeds:
                sequence = generate_sequence(seed, frames, profile)
                sequence, expected = align_after_python_errors(sequence, run_python(sequence, names))
                path = workdir / f'{profile}-{seed}.jsonl'
                write_sequence(sequence, path)
                java_names, actual = run_java(class_name, java_files, path, workdir)
                if list(java_names) != list(names):
                    raise AssertionError(f'NAMES differ: {java_names} vs {list(names)}')
                result = compare(names, sequence, expected, actual, quantum)
                report['runs'] += 1
                report['java_only_errors'] += result['java_only_errors']
                for name in names:
                    report['compared'][name] += result['compared'][name]
                    report['flips'][name] += result['flips'][name]
                report['problems'].extend(f'[{profile}/{seed}] {p}' for p in result['problems'])
    return report


def exercise(names: Sequence[str], *, seeds: Sequence[int] = (1, 2, 3),
             profiles: Sequence[str] = ('full', 'native', 'sparse', 'hostile'), frames: int = 300) -> Dict[str, int]:
    """Distinct Python values each feature takes over the parity sequences.

    A port that only matches because the sequences leave a feature at its default proves nothing;
    tests assert this count is well above one for every ported feature.
    """
    seen: Dict[str, set] = {name: set() for name in names}
    for profile in profiles:
        for seed in seeds:
            sequence = generate_sequence(seed, frames, profile)
            for row in run_python(sequence, names):
                if row is not None:
                    for name, value in zip(names, row):
                        seen[name].add(value)
    return {name: len(values) for name, values in seen.items()}
