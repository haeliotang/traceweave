#!/usr/bin/env python3
"""Strictly later continuous holdout diagnostics; never fit on holdout records."""
import argparse
import hashlib
import json
import math
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
    for first, second, _ in adjacent_examples(holdout, 300):
        dt = (timestamp(second) - timestamp(first)).total_seconds()
        drift, covariance = model.coefficients(first['continuous_vector'])
        delta = [b - a for a, b in zip(first['continuous_vector'], second['continuous_vector'])]
        errors = [value - slope * dt for value, slope in zip(delta, drift)]
        squared += sum(v * v for v in errors)
        baseline += sum(v * v for v in delta)
        exposure += dt
        count += 1
        for row in range(dimension):
            for column in range(dimension):
                residual[row][column] += errors[row] * errors[column]
                predicted[row][column] += covariance[row][column] * dt
    if count < 2:
        raise ValueError('at least two independent holdout pairs required')
    observed = [[v / exposure for v in row] for row in residual]
    expected = [[v / exposure for v in row] for row in predicted]
    difference = math.sqrt(sum((a - b) ** 2 for left, right in zip(observed, expected) for a, b in zip(left, right)))
    norm = math.sqrt(sum(v * v for row in expected for v in row))
    return {'dimension': dimension, 'training_pair_count': model.fit_pair_count, 'holdout_pair_count': count,
            'holdout_exposure_seconds': exposure, 'drift_increment_mse': squared / (count * dimension),
            'persistence_increment_mse': baseline / (count * dimension),
            'residual_covariance_rate': observed, 'predicted_covariance_rate': expected,
            'covariance_frobenius_error': difference, 'relative_covariance_error': difference / norm if norm else None,
            'scientific_acceptance': 'not_assessed',
            'limitations': ['finite_lag_conditional_moment_diagnostics_not_true_drift_identification',
                            'no_fractional_memory_or_conservative_potential_acceptance',
                            'no_independent_subject_generalization_or_causal_effect'],
            'coordinate_bindings': {field: train[0][field] for field in fields}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--train-file', type=Path, required=True)
    parser.add_argument('--holdout-file', type=Path, required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    payloads = [p.read_bytes() for p in (args.train_file, args.holdout_file)]
    partitions = [[json.loads(line) for line in payload.splitlines() if line.strip()] for payload in payloads]
    result = validate(*partitions)
    result['source_sha256'] = {name: hashlib.sha256(payload).hexdigest() for name, payload in zip(('train', 'holdout'), payloads)}
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(result, indent=2, allow_nan=False) + '\n')
    print(json.dumps({'holdout_pair_count': result['holdout_pair_count'], 'scientific_acceptance': result['scientific_acceptance']}))


if __name__ == '__main__':
    main()
