#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Evaluate an offline projection on a diagnostic window before the frozen holdout.

No manifold is fitted, state tokens compared, forecast scored or registry modified.
Raw records and the candidate parameter file must remain device-private.
"""

import argparse
import collections
from contextlib import closing
import datetime
import hashlib
import json
from pathlib import Path
import sqlite3
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from traceform.representation.missingness_candidate import MissingnessProjectionCandidate
from traceform.representation.provenance import content_digest


def evaluate(database, bundle, window, frozen_plan):
    start = datetime.datetime.fromisoformat(window['window_start_utc'])
    end = datetime.datetime.fromisoformat(window['window_end_utc'])
    holdout = datetime.datetime.fromisoformat(frozen_plan['holdout_start_utc'])
    if (any(value.tzinfo is None for value in (start, end, holdout))
            or not start < end <= holdout):
        raise ValueError('diagnostic window must finish before frozen holdout')
    candidate = MissingnessProjectionCandidate(bundle['normalizer'])
    baseline_digest = content_digest(candidate.normalizer.to_dict())
    with closing(sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True)) as db:
        db.execute('BEGIN')
        rows = db.execute('SELECT id,ts,raw,frame FROM hot WHERE ts>=? AND ts<? ORDER BY ts,id',
                          (start.timestamp(), end.timestamp())).fetchall()
    if len(rows) < 2:
        raise ValueError('at least two diagnostic frames required')
    validity = collections.Counter()
    baseline_energy = candidate_energy = 0.0
    affected = set(candidate.GNSS).union(candidate.THERMAL)
    indices = [candidate.names.index(name) for name in affected]
    previous = None
    unchanged = 0
    pairs = 0
    maximum_thermal_energy = 0.0
    baseline_gnss_clipped = candidate_gnss_clipped = 0
    digest = hashlib.sha256()
    for ident, instant, raw, payload in rows:
        observation, frame = json.loads(raw), json.loads(payload)
        if frame.get('normalizer_sha256') != baseline_digest:
            raise ValueError('baseline normalizer does not match stored frame binding')
        if instant != datetime.datetime.fromisoformat(frame['timestamp_utc'].replace('Z', '+00:00')).timestamp():
            raise ValueError('frame timestamp mismatch')
        if observation['timestamp_utc'] != frame['timestamp_utc']:
            raise ValueError('raw/frame timestamp mismatch')
        result = candidate.project(observation, frame['continuous_vector'], frame.get('continuous_from_previous') is True)
        vector = result['vector']
        untouched = all(vector[index] == frame['continuous_vector'][index]
                        for index, name in enumerate(candidate.names) if name not in affected)
        unchanged += untouched
        validity.update(name for name, valid in result['validity'].items() if valid)
        thermal_energy = sum(vector[candidate.names.index(name)] ** 2 for name in candidate.THERMAL)
        maximum_thermal_energy = max(maximum_thermal_energy, thermal_energy)
        for name in candidate.GNSS:
            index = candidate.names.index(name)
            baseline_gnss_clipped += abs(frame['continuous_vector'][index]) >= 9.999
            candidate_gnss_clipped += abs(vector[index]) >= 9.999
        if previous is not None:
            before_time, before, projected = previous
            if frame.get('continuous_from_previous') is True and 0 < instant - before_time <= 300:
                pairs += 1
                baseline_energy += sum((frame['continuous_vector'][index] - before[index]) ** 2 for index in indices)
                candidate_energy += sum((vector[index] - projected[index]) ** 2 for index in indices)
        previous = (instant, frame['continuous_vector'], vector)
        encoded = json.dumps([ident, instant, raw, payload], separators=(',', ':')).encode()
        digest.update(len(encoded).to_bytes(8, 'big')); digest.update(encoded)
    report = {'scope': 'same_diagnostic_window_engineering_check_not_independent_validation',
              'status': 'offline_candidate_not_accepted', 'frames': len(rows), 'eligible_pairs': pairs,
              'window': {key: window[key] for key in ('window_start_utc', 'window_end_utc')},
              'frozen_holdout_excluded': True,
              'baseline_normalizer_sha256': baseline_digest, 'candidate_sha256': candidate.digest,
              'source_rows_sha256': digest.hexdigest(), 'unchanged_other_123_dimensions_frames': unchanged,
              'direct_or_derived_validity_counts': {name: validity[name] for name in affected},
              'five_coordinate_squared_step_sum': {'baseline': baseline_energy, 'candidate': candidate_energy},
              'GNSS_clipped_coordinate_count': {'baseline': baseline_gnss_clipped, 'candidate': candidate_gnss_clipped},
              'maximum_combined_thermal_coordinate_energy': maximum_thermal_energy,
              'engineering_checks': {'other_coordinates_preserved': unchanged == len(rows),
                                     'thermal_shared_energy_bound': maximum_thermal_energy <= 1 + 1e-12},
              'scientific_acceptance': 'not_assessed', 'activated': False,
              'limitations': ['Neutral missing coordinates are not measurements.',
                              'Validity masks must accompany any downstream analysis.',
                              'Availability transitions remain distinct from observed dynamics.',
                              'Other derived/proxy dimensions remain unchanged.',
                              'Independent annotated data and a newly fitted manifold are required.']}
    return report, candidate.to_dict()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sqlite-file', type=Path, required=True)
    parser.add_argument('--baseline-bundle-file', type=Path, required=True)
    parser.add_argument('--diagnostic-window-file', type=Path, required=True)
    parser.add_argument('--frozen-plan-file', type=Path, required=True)
    parser.add_argument('--candidate-file', type=Path, required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    inputs = {path.resolve() for path in (args.sqlite_file, args.baseline_bundle_file,
                                         args.diagnostic_window_file, args.frozen_plan_file)}
    outputs = (args.candidate_file.resolve(), args.output_file.resolve())
    frozen = args.frozen_plan_file.resolve().parent
    if (len(set(outputs)) != 2 or any(path in inputs or frozen in path.parents for path in outputs)):
        raise ValueError('write separate candidate/report files outside inputs and frozen directory')
    report, candidate = evaluate(args.sqlite_file, json.loads(args.baseline_bundle_file.read_text()),
                                 json.loads(args.diagnostic_window_file.read_text()),
                                 json.loads(args.frozen_plan_file.read_text()))
    for path, document in ((args.candidate_file, candidate), (args.output_file, report)):
        path.write_text(json.dumps(document, indent=2, sort_keys=True, allow_nan=False) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
