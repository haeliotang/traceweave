#!/usr/bin/env python3
"""Strictly later continuous holdout diagnostics; never fit on holdout records."""
import argparse
import datetime
import hashlib
import json
import math
import random
import sqlite3
import statistics
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.audit_continuous_records import audit
from traceform.dynamics.continuous import ContinuousDynamics
from traceform.forecasting.evaluation import adjacent_examples, timestamp


def validate(train, holdout, bandwidth=1, dimension=128):
    for rows in (train, holdout):
        readiness = audit(rows, dimension)
        if not readiness['ready_for_fit'] or readiness['eligible_records'] != len(rows):
            raise ValueError('complete dimension-matched bound continuous records required')
        times = [timestamp(row) for row in rows]
        if any(b <= a for a, b in zip(times, times[1:])):
            raise ValueError('strictly increasing timestamps required')
    if timestamp(holdout[0]) <= timestamp(train[-1]):
        raise ValueError('holdout must be strictly later than all training records')
    fields = ('model_version', 'model_sha256', 'normalizer_sha256')
    if any(train[0][field] != holdout[0][field] for field in fields):
        raise ValueError('training and holdout coordinate bindings must match')
    model = ContinuousDynamics(bandwidth).fit(train)
    residual = [[0.0] * dimension for _ in range(dimension)]
    predicted = [[0.0] * dimension for _ in range(dimension)]
    squared = baseline = exposure = 0.0
    count = 0
    daily, intervals, strata = {}, [], {}
    for first, second, _ in adjacent_examples(holdout, 300):
        dt = (timestamp(second) - timestamp(first)).total_seconds()
        drift, covariance = model.coefficients(first['continuous_vector'])
        delta = [b - a for a, b in zip(first['continuous_vector'], second['continuous_vector'])]
        errors = [value - slope * dt for value, slope in zip(delta, drift)]
        pair_error = sum(v * v for v in errors)
        pair_baseline = sum(v * v for v in delta)
        squared += pair_error
        baseline += pair_baseline
        day = timestamp(second).astimezone(datetime.timezone.utc).date().isoformat()
        group = daily.setdefault(day, [0.0, 0])
        group[0] += (pair_baseline - pair_error) / dimension
        group[1] += 1
        intervals.append(dt)
        name = 'up_to_1s' if dt <= 1 else '1_to_5s' if dt <= 5 else '5_to_30s' if dt <= 30 else 'over_30s'
        group = strata.setdefault(name, [0.0, 0.0, 0])
        group[0] += pair_error; group[1] += pair_baseline; group[2] += 1
        exposure += dt
        count += 1
        for row in range(dimension):
            for column in range(dimension):
                residual[row][column] += errors[row] * errors[column]
                predicted[row][column] += covariance[row][column] * dt
    if count < 2:
        raise ValueError('at least two eligible holdout pairs required')
    samples = []
    days = sorted(daily)
    if len(days) >= 2:
        rng = random.Random(42)
        for _ in range(1000):
            selected = [daily[rng.choice(days)] for _ in days]
            samples.append(sum(group[0] for group in selected) / sum(group[1] for group in selected))
        samples.sort()
    intervals.sort()
    observed = [[v / exposure for v in row] for row in residual]
    expected = [[v / exposure for v in row] for row in predicted]
    difference = math.sqrt(sum((a - b) ** 2 for left, right in zip(observed, expected) for a, b in zip(left, right)))
    norm = math.sqrt(sum(v * v for row in expected for v in row))
    return {'dimension': dimension, 'training_pair_count': model.fit_pair_count, 'holdout_pair_count': count,
            'holdout_exposure_seconds': exposure, 'drift_increment_mse': squared / (count * dimension),
            'persistence_increment_mse': baseline / (count * dimension),
            'paired_mse_reduction_over_persistence': (baseline - squared) / (count * dimension),
            'holdout_utc_day_count': len(days),
            'paired_day_bootstrap_95_interval': [samples[25], samples[974]] if samples else None,
            'day_bootstrap_scope': 'descriptive_UTC_day_blocks_not_proven_independent_days_or_model_selection',
            'sampling_interval_seconds': {'min': intervals[0], 'median': statistics.median(intervals),
                                         'p95': intervals[math.ceil(0.95 * len(intervals)) - 1], 'max': intervals[-1]},
            'interval_strata': {name: {'pair_count': values[2],
                                      'drift_increment_mse': values[0] / (values[2] * dimension),
                                      'persistence_increment_mse': values[1] / (values[2] * dimension)}
                                for name, values in sorted(strata.items())},
            'residual_covariance_rate': observed, 'predicted_covariance_rate': expected,
            'covariance_frobenius_error': difference, 'relative_covariance_error': difference / norm if norm else None,
            'scientific_acceptance': 'not_assessed',
            'limitations': ['finite_lag_conditional_moment_diagnostics_not_true_drift_identification',
                            'overlapping_adjacent_pairs_not_independent_samples',
                            'day_bootstrap_unavailable_below_two_days_and_not_proof_of_day_independence',
                            'no_fractional_memory_or_conservative_potential_acceptance',
                            'no_independent_subject_generalization_or_causal_effect'],
            'coordinate_bindings': {field: train[0][field] for field in fields}}


def validate_sqlite(path, split_time, bandwidth=1, dimension=128):
    """Partition one read-only hot snapshot without exporting trajectory rows."""
    if split_time.tzinfo is None or split_time.utcoffset() is None:
        raise ValueError('timezone-aware split required')
    train, holdout = [], []
    digests = {name: hashlib.sha256() for name in ('train', 'holdout')}
    connection = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)
    try:
        connection.execute('PRAGMA query_only=ON')
        connection.execute('BEGIN')
        for (payload,) in connection.execute('SELECT frame FROM hot ORDER BY ts, id'):
            row = json.loads(payload)
            name = 'train' if timestamp(row) < split_time else 'holdout'
            (train if name == 'train' else holdout).append(row)
            encoded = payload.encode('utf-8')
            digests[name].update(len(encoded).to_bytes(8, 'big'))
            digests[name].update(encoded)
    finally:
        connection.close()
    result = validate(train, holdout, bandwidth, dimension)
    result.update(training_record_count=len(train), holdout_record_count=len(holdout),
                  source_sha256={name: digest.hexdigest() for name, digest in digests.items()},
                  source_scope='sqlite_hot_single_read_transaction_length_prefixed_utf8_partitions')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--train-file', type=Path)
    parser.add_argument('--holdout-file', type=Path)
    parser.add_argument('--sqlite-file', type=Path)
    parser.add_argument('--split-time', help='Timezone-aware ISO timestamp; earlier rows train, later rows hold out')
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    if args.sqlite_file:
        if not args.split_time or args.train_file or args.holdout_file:
            parser.error('--sqlite-file requires --split-time and excludes JSONL input files')
        split_time = datetime.datetime.fromisoformat(args.split_time.replace('Z', '+00:00'))
        result = validate_sqlite(args.sqlite_file, split_time)
    else:
        if not args.train_file or not args.holdout_file or args.split_time:
            parser.error('provide both JSONL input files, or --sqlite-file with --split-time')
        payloads = [p.read_bytes() for p in (args.train_file, args.holdout_file)]
        partitions = [[json.loads(line) for line in payload.splitlines() if line.strip()] for payload in payloads]
        result = validate(*partitions)
        result['source_sha256'] = {name: hashlib.sha256(payload).hexdigest() for name, payload in zip(('train', 'holdout'), payloads)}
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(result, indent=2, allow_nan=False) + '\n')
    print(json.dumps({'holdout_pair_count': result['holdout_pair_count'], 'scientific_acceptance': result['scientific_acceptance']}))


if __name__ == '__main__':
    main()
