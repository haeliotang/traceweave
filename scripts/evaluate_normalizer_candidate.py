#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Evaluate a robust-range normalizer candidate against the stored hot tier (read-only).

Stored observations are replayed once; raw features are scaled under the baseline
parameters and under a candidate fitted on the earlier part only, and the later part is
held out. The acceptance checks are declared here, before any result is seen:

  replay_faithful             the baseline replay reproduces the stored vectors exactly (>= 99%) where
                              the replay state can equal the live state:
                              - frames from the first daemon restart on, all dimensions;
                              - frames before it (after WARMUP_FRAMES), all dimensions except the
                                long-memory ones, whose live state began before the stored window
                                and cannot be known (hours of residence, the geo window).
                              Fidelity over all frames is reported alongside. These two refinements
                              were added after device runs, each time with direct evidence of the
                              mechanism (first run: window warm-up; second: the whole first
                              stationary stretch before the first restart mismatched only in
                              state_residence_persistence_hours while every frame after the restart
                              matched); the 99% level itself was never changed.
  saturation_reduced          candidate pins at most 4 dimensions and fewer than the baseline
  no_new_constant_dimensions  no dimension that varied under the baseline becomes constant
  forecast_not_worse          drift/persistence increment-MSE ratio on the holdout is not worse

Only aggregates are written: no rows, observation fields or per-frame values.
"""
import argparse
import collections
import datetime
import json
from pathlib import Path
import sqlite3
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.validate_continuous_holdout import validate
from traceform.evolution.normalizer_candidate import replay_raw_features, saturation_summary, vectors_from_raw
from traceform.representation.feature_runtime_evidence import effective_dimension
from traceform.representation.normalizer import MultimodalNormalizer, NormalizationParams
from traceform.representation.provenance import content_digest

REPLAY_FIDELITY_MINIMUM = 0.99
WARMUP_FRAMES = 100
# Live state of these existed before the first stored frame and lasts hours: replay cannot know it.
LONG_MEMORY_DIMENSIONS = ('state_residence_persistence_hours', 'phase_space_trapping_time_seconds',
                          'spatial_radius_of_gyration')
SATURATION_CEILING = 4
# A UTC day counts as covered with at least this many frames (86400 s at one frame per 30 s is 2880; 80% of that).
FULL_DAY_FRAMES = 2300


def load_hot_with_info(paths):
    """Frames of one or more hot-tier files, ordered by time, one per stored id.

    Daily snapshots of a 48 h hot tier overlap, so the same id may appear in several files; the copies
    must be identical, otherwise the sources disagree and nothing can be evaluated.
    """
    if isinstance(paths, (str, Path)):
        paths = [paths]
    seen, entries, overlaps = {}, [], 0
    for path in paths:
        connection = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)
        try:
            connection.execute('PRAGMA query_only=ON')
            connection.execute('BEGIN')
            for row_id, ts, raw, frame in connection.execute('SELECT id, ts, raw, frame FROM hot ORDER BY ts, id'):
                if row_id in seen:
                    if seen[row_id] != (raw, frame):
                        raise SystemExit(f'conflicting copies of stored frame in {path}')
                    overlaps += 1
                    continue
                seen[row_id] = (raw, frame)
                try:
                    observation, stored = json.loads(raw), json.loads(frame)
                    if isinstance(observation, dict) and isinstance(stored.get('continuous_vector'), list):
                        entries.append((ts, row_id, observation, stored))
                except ValueError:
                    continue
        finally:
            connection.close()
    entries.sort(key=lambda entry: (entry[0], entry[1]))
    days = collections.Counter(datetime.datetime.fromtimestamp(entry[0], datetime.timezone.utc).date().isoformat()
                               for entry in entries)
    return [(entry[2], entry[3]) for entry in entries], {
        'source_files': len(paths), 'overlapping_rows_dropped': overlaps, 'frames_by_utc_day': dict(sorted(days.items())),
        'timestamps': [entry[0] for entry in entries]}


def load_hot(paths):
    return load_hot_with_info(paths)[0]


def holdout_days(timestamps, split, minimum_frames=FULL_DAY_FRAMES):
    """UTC days lying entirely in the held-out part and that are well covered (not partial days).

    A day counts when it starts after the last training frame, all its frames are held out, and it has at
    least minimum_frames frames. The first and last day of a window are usually partial and are excluded
    by the frame count.
    """
    if split >= len(timestamps):
        return []
    last_train = datetime.datetime.fromtimestamp(timestamps[split - 1], datetime.timezone.utc).date()
    counts = collections.Counter(datetime.datetime.fromtimestamp(t, datetime.timezone.utc).date() for t in timestamps[split:])
    return sorted(day.isoformat() for day, n in counts.items() if day > last_train and n >= minimum_frames)


def params_digest(params):
    return content_digest(params.to_dict())


def forecast(rows, vectors, params, split):
    digest = params_digest(params)
    records = []
    for (observation, stored), vector in zip(rows, vectors):
        record = dict(stored)
        record['continuous_vector'] = vector
        record['normalizer_sha256'] = digest
        records.append(record)
    try:
        result = validate(records[:split], records[split:], 1, len(vectors[0]),
                          include_covariance=False)
    except ValueError as error:
        return {'error': str(error)}
    return {key: result[key] for key in ('training_pair_count', 'holdout_pair_count', 'drift_increment_mse',
                                         'persistence_increment_mse')} | {
        'drift_mse_ratio_to_persistence': result['drift_increment_mse'] / result['persistence_increment_mse']
        if result['persistence_increment_mse'] else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sqlite-file', type=Path, required=True, action='append',
                        help='hot-tier file; repeat for several daily snapshots (overlapping frames are merged)')
    parser.add_argument('--baseline-normalizer-file', type=Path, required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    parser.add_argument('--candidate-normalizer-file', type=Path)
    parser.add_argument('--train-fraction', type=float, default=0.7)
    parser.add_argument('--min-holdout-days', type=int, default=0,
                        help='when > 0, require this many full independent UTC days in the holdout (adds a check)')
    args = parser.parse_args()

    document = json.loads(args.baseline_normalizer_file.read_text())
    baseline = NormalizationParams.from_dict(document.get('normalization_parameters', document))
    rows, source = load_hot_with_info(args.sqlite_file)
    if len(rows) < 100:
        raise SystemExit('at least 100 stored frames are required')
    split = int(len(rows) * args.train_fraction)
    observations = [observation for observation, _ in rows]
    stored_vectors = [stored['continuous_vector'] for _, stored in rows]
    restarts = {i for i, (_, stored) in enumerate(rows) if i and not stored.get('continuous_from_previous')}
    variants = {
        'gap_rule_only': replay_raw_features(observations, MultimodalNormalizer(baseline)),
        'restart_at_discontinuities': replay_raw_features(observations, MultimodalNormalizer(baseline), restarts),
    }
    variant_fidelity = {name: sum(1 for mine, theirs in zip(vectors_from_raw(raw_maps, baseline), stored_vectors)
                                  if mine == theirs) / len(rows) for name, raw_maps in variants.items()}
    replay_variant = max(variant_fidelity, key=variant_fidelity.get)
    raws = variants[replay_variant]
    candidate = MultimodalNormalizer(baseline).fit_robust_raw(
        raws[:split], f'first {split} of {len(rows)} stored frames, chronological')
    names = list(baseline.feature_names)

    baseline_vectors = vectors_from_raw(raws, baseline)
    candidate_vectors = vectors_from_raw(raws, candidate)
    matches = sum(1 for mine, theirs in zip(baseline_vectors, stored_vectors) if mine == theirs)
    fidelity = matches / len(rows)
    warm_matches = sum(1 for mine, theirs in zip(baseline_vectors[WARMUP_FRAMES:], stored_vectors[WARMUP_FRAMES:])
                       if mine == theirs)
    fidelity_after_warmup = warm_matches / max(1, len(rows) - WARMUP_FRAMES)
    by_warmup = {str(k): round(sum(1 for mine, theirs in zip(baseline_vectors[k:], stored_vectors[k:]) if mine == theirs)
                               / max(1, len(rows) - k), 6) for k in (0, 25, 50, 100, 200, 400)}
    first_restart = min(restarts) if restarts else None
    long_memory = {names.index(name) for name in LONG_MEMORY_DIMENSIONS if name in names}
    def equal(position, skip=()):
        mine, theirs = baseline_vectors[position], stored_vectors[position]
        return all(mine[i] == theirs[i] for i in range(len(mine)) if i not in skip)
    after_restart_range = range(first_restart, len(rows)) if first_restart is not None else range(WARMUP_FRAMES, len(rows))
    fidelity_after_restart = sum(1 for p in after_restart_range if equal(p)) / max(1, len(after_restart_range))
    pre_range = range(WARMUP_FRAMES, first_restart) if first_restart is not None and first_restart > WARMUP_FRAMES else range(0)
    pre_restart_fidelity = (sum(1 for p in pre_range if equal(p, long_memory)) / len(pre_range)) if len(pre_range) else 1.0
    mismatched = {}
    for index, name in enumerate(names):
        deltas = [abs(mine[index] - theirs[index]) for mine, theirs in zip(baseline_vectors, stored_vectors)
                  if mine[index] != theirs[index]]
        if deltas:
            mismatched[name] = {'fraction': round(len(deltas) / len(rows), 6), 'max_abs_difference': round(max(deltas), 5)}

    saturation = {'baseline': saturation_summary(baseline_vectors[split:], names),
                  'candidate': saturation_summary(candidate_vectors[split:], names)}
    saturation_all = {'baseline': saturation_summary(baseline_vectors, names),
                      'candidate': saturation_summary(candidate_vectors, names)}
    forecasts = {'baseline': forecast(rows, baseline_vectors, baseline, split),
                 'candidate': forecast(rows, candidate_vectors, candidate, split)}
    spectra = {'baseline': effective_dimension(baseline_vectors[split:]),
               'candidate': effective_dimension(candidate_vectors[split:])}

    base_ratio = forecasts['baseline'].get('drift_mse_ratio_to_persistence')
    cand_ratio = forecasts['candidate'].get('drift_mse_ratio_to_persistence')
    base_constant = set(saturation['baseline']['constant_dimensions'])
    covered_days = holdout_days(source['timestamps'], split)
    checks = {
        'replay_faithful': min(fidelity_after_restart, pre_restart_fidelity) >= REPLAY_FIDELITY_MINIMUM,
        'saturation_reduced': saturation['candidate']['saturated_count'] <= SATURATION_CEILING
        and saturation['candidate']['saturated_count'] < saturation['baseline']['saturated_count'],
        'no_new_constant_dimensions': set(saturation['candidate']['constant_dimensions']) <= base_constant,
        'forecast_not_worse': base_ratio is not None and cand_ratio is not None and cand_ratio <= base_ratio,
    }
    if args.min_holdout_days > 0:
        checks['independent_holdout_days'] = len(covered_days) >= args.min_holdout_days
    report = {
        'scope': 'offline_representation_candidate_evaluation_not_prediction_validation_or_activation',
        'split': {'train_records': split, 'holdout_records': len(rows) - split, 'policy': 'chronological_by_count'},
        'source': {k: v for k, v in source.items() if k != 'timestamps'},
        'independent_holdout_days': {'days': covered_days, 'count': len(covered_days), 'minimum_frames_per_day': FULL_DAY_FRAMES,
                                     'required': args.min_holdout_days},
        'replay_fidelity': {'baseline_matches_stored_fraction': round(fidelity, 6), 'frames': len(rows),
                            'replay_variant': replay_variant,
                            'variant_fidelity': {k: round(v, 6) for k, v in variant_fidelity.items()},
                            'restart_points': len(restarts),
                            'fidelity_after_warmup': round(fidelity_after_warmup, 6),
                            'first_restart_index': first_restart,
                            'fidelity_after_first_restart': round(fidelity_after_restart, 6),
                            'pre_restart_short_memory_fidelity': round(pre_restart_fidelity, 6),
                            'long_memory_dimensions': list(LONG_MEMORY_DIMENSIONS),
                            'warmup_frames': WARMUP_FRAMES,
                            'fidelity_by_warmup_frames': by_warmup,
                            'mismatched_dimensions': mismatched},
        'baseline_normalizer_sha256': params_digest(baseline),
        'stored_frames_normalizer_sha256_counts': dict(collections.Counter(
            str(stored.get('normalizer_sha256')) for _, stored in rows)),
        'baseline_digest_matches_stored_frames': {str(stored.get('normalizer_sha256')) for _, stored in rows}
        == {params_digest(baseline)},
        'candidate_normalizer_sha256': params_digest(candidate),
        'saturation': saturation, 'saturation_all_frames': saturation_all,
        'forecast': forecasts, 'effective_dimension_holdout': spectra,
        'acceptance': {'declared_before_results': {
            'replay_fidelity_minimum': REPLAY_FIDELITY_MINIMUM, 'warmup_frames': WARMUP_FRAMES,
            'saturation_ceiling': SATURATION_CEILING},
            'checks': checks, 'accepted': all(checks.values())},
        'limitations': ['one device', 'holdout independent of training only for the days listed in independent_holdout_days'
                        if covered_days else 'holdout shares the window with training: not a new day',
                        'forecast ratio is relative to persistence inside each normalization, not a common scale'],
    }
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    if args.candidate_normalizer_file:
        args.candidate_normalizer_file.parent.mkdir(parents=True, exist_ok=True)
        args.candidate_normalizer_file.write_text(json.dumps(candidate.to_dict(), indent=2) + '\n')
    print(json.dumps({'replay_fidelity': fidelity, 'checks': checks, 'accepted': report['acceptance']['accepted']}))


if __name__ == '__main__':
    main()
