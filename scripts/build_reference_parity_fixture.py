#!/usr/bin/env python3
"""Build the on-device parity fixture for SyntheticReferenceParityReceiver.

engine_frames: observations with the raw values the Python extractor computes (reference).
samples:       native-shaped samples with the values the host JVM produces through the supplier.
The device run then shows whether its own runtime reproduces both. Synthetic data only.
"""
import argparse
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'tests'))
import parity_support as parity
import test_native_reference_supply as supply
import test_reference_features_dynamics as dynamics
import test_reference_features_signal as signal
import test_reference_features_usage as usage

NAMES = list(usage.NAMES) + list(signal.NAMES) + list(dynamics.NAMES)


def engine_frames(profiles=('full', 'sparse', 'native'), seeds=(21, 22), frames=120):
    result = []
    for profile in profiles:
        for seed in seeds:
            sequence = parity.generate_sequence(seed, frames, profile)
            expected = parity.run_python(sequence, NAMES)
            sequence, expected = parity.align_after_python_errors(sequence, expected)
            for frame, row in zip(sequence, expected):
                if '__reset' in frame:
                    result.append({'reset': True})
                else:
                    item = {'obs': frame}
                    if row is not None:
                        item['expected'] = dict(zip(NAMES, row))
                    result.append(item)
            result.append({'reset': True})
    return result


def supplier_samples(seeds=(31, 32)):
    result = []
    with tempfile.TemporaryDirectory() as directory:
        for seed in seeds:
            frames = supply.native_samples(seed, count=150)
            sub = Path(directory) / str(seed)
            sub.mkdir()
            lines = supply.run_java(frames, sub)
            for frame, line in zip(frames, lines):
                if line.startswith('#RESET'):
                    result.append({'reset': True})
                    continue
                item = dict(frame)
                item['expected'] = json.loads(line.replace('NaN', 'null'))['values']
                result.append(item)
            result.append({'reset': True})
    return result


def build():
    quantum = {}
    for table in (usage.QUANTUM, signal.QUANTUM, dynamics.QUANTUM):
        quantum.update(table)
    body = {'quantum': quantum, 'engine_frames': engine_frames(), 'samples': supplier_samples()}
    text = json.dumps(body, sort_keys=True, allow_nan=False)
    body['fixture_id'] = hashlib.sha256(text.encode()).hexdigest()
    return body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    body = build()
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(body, sort_keys=True, allow_nan=False))
    print(json.dumps({'fixture_id': body['fixture_id'], 'engine_frames': len(body['engine_frames']),
                      'samples': len(body['samples']), 'bytes': args.output_file.stat().st_size}))


if __name__ == '__main__':
    main()
