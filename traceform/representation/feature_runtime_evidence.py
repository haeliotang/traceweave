"""Aggregate runtime evidence about the 128 continuous dimensions from stored frames.

Reads one SQLite hot-tier snapshot read-only and reports, for each observation
field named by the static dataflow analysis, how often it was present and whether
its value ever changed, plus per-dimension output statistics and the effective
dimension of the stored vectors. Only aggregates are returned: no raw rows,
field values or per-frame timestamps leave this function.

Presence and variation are evidence about inputs, not proof of measurement: a
field can be present and varying yet be a proxy, and a constant field is the
signature of a placeholder default.
"""
from __future__ import annotations

import collections
import json
import sqlite3
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

try:  # numpy is only needed for the spectrum summary
    import numpy as np
except ImportError:  # pragma: no cover - exercised on hosts without numpy
    np = None

MAX_DISTINCT = 2000
VARIANCE_LEVELS = ('0.9', '0.95', '0.99')
CONSTANT_STD = 1e-9
RANK_TOLERANCE = 1e-6
CONSTANT_MODAL_FRACTION = 0.999
CLIP_BOUND = 10.0  # the normalizer bounds robust-scaled values to [-10, 10]


def _resolve(observation: Any, path: str) -> Any:
    node = observation
    for part in path.split('.'):
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node


def _present(value: Any) -> bool:
    return value is not None and value != '' and value != {} and value != []


class _PathStats:
    __slots__ = ('present', 'distinct', 'capped')

    def __init__(self):
        self.present = 0
        self.distinct: Set[str] = set()
        self.capped = False

    def add(self, value: Any) -> None:
        if not _present(value):
            return
        self.present += 1
        if len(self.distinct) < MAX_DISTINCT:
            self.distinct.add(json.dumps(value, sort_keys=True))
        else:
            self.capped = True


def _inputs_for(feature: Dict[str, Any]) -> List[str]:
    paths = sorted(set(feature.get('observation_inputs', [])) | set(feature.get('history_observation_inputs', [])))
    return paths or sorted(feature.get('control_observation_inputs', []))


def _tracked_paths(dataflow: Dict[str, Any]) -> List[str]:
    tracked: Set[str] = set()
    for feature in dataflow['features']:
        tracked.update(_inputs_for(feature))
        tracked.update(feature.get('control_observation_inputs', []))
    return sorted(tracked)


def _output_summary(values: List[float]) -> Dict[str, Any]:
    count = len(values)
    mean = sum(values) / count
    variance = sum((value - mean) ** 2 for value in values) / count
    counts = collections.Counter(values)
    return {
        'mean': round(mean, 6),
        'std': round(variance ** 0.5, 6),
        'min': min(values),
        'max': max(values),
        'distinct_count': len(counts),
        'modal_fraction': round(counts.most_common(1)[0][1] / count, 6),
        'clip_fraction': round(sum(1 for value in values if abs(value) >= CLIP_BOUND - 1e-9) / count, 6),
    }


def _runtime_class(inputs: List[str], paths: Dict[str, Dict[str, Any]], output_constant: bool) -> Dict[str, Any]:
    never = sorted(p for p in inputs if paths[p]['present_fraction'] == 0.0)
    constant = sorted(p for p in inputs if paths[p]['present_fraction'] > 0.0 and not paths[p]['varies'])
    varying = sorted(p for p in inputs if paths[p]['present_fraction'] > 0.0 and paths[p]['varies'])
    if not inputs:
        state = 'none_listed'
    elif not varying:
        state = 'absent_or_constant'
    elif never or constant:
        state = 'partial'
    else:
        state = 'present_varying'
    if state == 'absent_or_constant':
        runtime = 'inputs_absent_or_constant'
    elif output_constant:
        runtime = 'constant_output'
    elif state == 'partial':
        runtime = 'partial_inputs'
    elif state == 'present_varying':
        runtime = 'all_listed_inputs_present_and_varying'
    else:
        runtime = 'no_listed_inputs'
    return {'runtime_class': runtime, 'inputs_state': state, 'never_present_inputs': never,
            'constant_present_inputs': constant, 'varying_present_inputs': varying}


def _effective_dimension(matrix) -> Dict[str, Any]:
    if np is None:
        return {'unavailable': 'numpy_missing'}
    data = np.asarray(matrix, dtype=float)
    std = data.std(axis=0)
    varying = std > CONSTANT_STD
    summary: Dict[str, Any] = {
        'sample_count': int(data.shape[0]),
        'dimension_count': int(data.shape[1]),
        'constant_dimension_count': int((~varying).sum()),
        'varying_dimension_count': int(varying.sum()),
        'scope': 'descriptive_spectrum_of_stored_vectors_adjacent_frames_are_autocorrelated_not_independent_samples',
    }
    if varying.sum() == 0 or data.shape[0] < 2:
        summary.update({'numerical_rank': 0, 'participation_ratio': 0.0, 'dimensions_for_variance': {}})
        return summary
    z = (data[:, varying] - data[:, varying].mean(axis=0)) / std[varying]
    correlation = (z.T @ z) / data.shape[0]
    eigenvalues = np.sort(np.linalg.eigvalsh(correlation))[::-1]
    eigenvalues = np.clip(eigenvalues, 0.0, None)
    total = float(eigenvalues.sum())
    cumulative = np.cumsum(eigenvalues) / total
    summary['numerical_rank'] = int((eigenvalues > RANK_TOLERANCE * eigenvalues[0]).sum())
    summary['participation_ratio'] = round(float(total ** 2 / (eigenvalues ** 2).sum()), 4)
    summary['dimensions_for_variance'] = {
        level: int(np.searchsorted(cumulative, float(level) - 1e-12) + 1) for level in VARIANCE_LEVELS}
    summary['leading_eigenvalue_fractions'] = [round(float(v / total), 4) for v in eigenvalues[:10]]
    return summary


def collect(sqlite_path, dataflow: Dict[str, Any]) -> Dict[str, Any]:
    features = dataflow['features']
    dimension = len(features)
    tracked = _tracked_paths(dataflow)
    stats = {path: _PathStats() for path in tracked}
    columns: List[List[float]] = [[] for _ in range(dimension)]
    normalizers: collections.Counter = collections.Counter()
    models: collections.Counter = collections.Counter()
    timestamps: List[str] = []
    analyzed = rejected = 0
    connection = sqlite3.connect(f'file:{Path(sqlite_path)}?mode=ro', uri=True)
    try:
        connection.execute('BEGIN')  # one read snapshot for the whole pass
        for raw, frame in connection.execute('SELECT raw, frame FROM hot ORDER BY ts'):
            try:
                observation = json.loads(raw)
                parsed = json.loads(frame)
                vector = parsed['continuous_vector']
                if not isinstance(observation, dict) or len(vector) != dimension:
                    raise ValueError('shape')
                vector = [float(value) for value in vector]
            except (ValueError, KeyError, TypeError):
                rejected += 1
                continue
            analyzed += 1
            for path in tracked:
                stats[path].add(_resolve(observation, path))
            for index, value in enumerate(vector):
                columns[index].append(value)
            normalizers[str(parsed.get('normalizer_sha256'))] += 1
            models[str(parsed.get('model_sha256'))] += 1
            stamp = observation.get('timestamp_utc')
            if isinstance(stamp, str):
                timestamps.append(stamp)
    finally:
        connection.close()
    if analyzed == 0:
        raise ValueError('no analyzable frames')
    paths = {path: {'present_fraction': round(item.present / analyzed, 6),
                    'distinct_count': len(item.distinct),
                    'varies': len(item.distinct) > 1,
                    'distinct_count_capped': item.capped} for path, item in stats.items()}
    reports = []
    for feature, values in zip(features, columns):
        output = _output_summary(values)
        output_constant = output['modal_fraction'] >= CONSTANT_MODAL_FRACTION
        inputs = _inputs_for(feature)
        record = {'index': feature['index'], 'feature': feature['feature'], 'input_class': feature['input_class'],
                  'inputs': {path: paths[path]['present_fraction'] for path in inputs},
                  'output': output, 'output_constant': output_constant}
        record.update(_runtime_class(inputs, paths, output_constant))
        reports.append(record)
    counts = collections.Counter(item['runtime_class'] for item in reports)
    return {
        'scope': 'aggregate_runtime_evidence_for_inputs_and_outputs_not_per_dimension_measurement_validation',
        'dataflow_source_sha256': dataflow.get('source_sha256'),
        'frames': {'analyzed': analyzed, 'rejected': rejected,
                   'first_timestamp_utc': min(timestamps) if timestamps else None,
                   'last_timestamp_utc': max(timestamps) if timestamps else None,
                   'normalizer_sha256_counts': dict(normalizers), 'model_sha256_counts': dict(models)},
        'runtime_class_counts': dict(sorted(counts.items())),
        'observation_paths': paths,
        'features': reports,
        'effective_dimension': _effective_dimension(list(zip(*columns))),
        'limitations': [
            'presence and variation of an input do not establish that it measured the quantity it is named for',
            'a constant input is treated as a placeholder default; a varying input may still be a proxy',
            'one hot-tier window is analyzed; other days, devices and subjects are not represented',
        ],
    }


effective_dimension = _effective_dimension
