#!/usr/bin/env python3
"""Freeze private training coordinates and a future four-UTC-day forecast plan."""

import argparse
from contextlib import closing
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.audit_continuous_records import audit
from traceform.evolution.model_registry import atomic_json
from traceform.forecasting.evaluation import timestamp


def freeze(database, code_archive, output_directory, now):
    if now.tzinfo is None:
        raise ValueError('timezone-aware freeze time required')
    with closing(sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True)) as source:
        source.execute('BEGIN')
        rows = list(reversed(source.execute('SELECT id,ts,frame FROM hot WHERE ts <= ? ORDER BY ts DESC LIMIT 2048',
                                           (now.timestamp(),)).fetchall()))
    records = [json.loads(row[2]) for row in rows]
    readiness = audit(records, 128)
    if len(records) < 64 or readiness['eligible_records'] != len(records) or not readiness['ready_for_fit']:
        raise ValueError('at least 64 complete bound 128D training frames required')
    if any(timestamp(b) <= timestamp(a) for a, b in zip(records, records[1:])):
        raise ValueError('strictly chronological training required')
    if not 0 <= (now - timestamp(records[-1])).total_seconds() <= 90:
        raise ValueError('fresh training boundary required')
    if any(record.get('source_mode') != 'live_follow' for record in records):
        raise ValueError('live-follow training required')
    tomorrow = now.astimezone(datetime.timezone.utc).date() + datetime.timedelta(days=1)
    start = datetime.datetime.combine(tomorrow, datetime.time(), datetime.timezone.utc)
    digest = hashlib.sha256()
    for _, _, payload in rows:
        encoded = payload.encode()
        digest.update(len(encoded).to_bytes(8, 'big'))
        digest.update(encoded)
    plan = {'protocol': 'traceform.prospective-forecast.v1', 'frozen_timestamp_utc': now.isoformat(),
            'training_records': len(records), 'training_frame_sha256': digest.hexdigest(),
            'training_last_timestamp_utc': records[-1]['timestamp_utc'],
            'coordinate_bindings': {key: records[0][key] for key in ('model_version', 'model_sha256', 'normalizer_sha256')},
            'holdout_start_utc': start.isoformat(), 'holdout_end_utc': (start + datetime.timedelta(days=4)).isoformat(),
            'full_utc_days_required': 4, 'minimum_frames_per_utc_day': 2300, 'max_gap_seconds': 300,
            'continuous': {'bandwidth': 1, 'max_anchors': 16, 'dimension': 128,
                           'primary_metric': 'paired_mse_reduction_over_persistence',
                           'secondary': 'interval_strata_and_full_covariance_rates'},
            'discrete': {'models': ['persist', 'first_order', 'dwell', 'periodic'], 'timezone': 'Asia/Kathmandu',
                         'metrics': ['top1_accuracy', 'log_loss', 'brier', 'ece_10_bins']},
            'bootstrap': {'draws': 1000, 'seed': 42, 'scope': 'descriptive_day_blocks_not_proven_independent_days'},
            'acceptance': 'report_all_results_no_automatic_model_activation_or_scientific_acceptance',
            'binding_change': 'block_evaluation_do_not_reproject_or_pick_a_replacement_window',
            'missing_day': 'block_evaluation_new_protocol_required_for_another_window',
            'code_archive_sha256': hashlib.sha256(code_archive.read_bytes()).hexdigest(),
            'scope': 'single_participant_next_observation_prediction_not_fixed_time_causality_or_memory_identification'}
    output_directory.mkdir(parents=True, exist_ok=False)
    with closing(sqlite3.connect(output_directory / 'training.sqlite')) as target:
        target.execute('CREATE TABLE hot (id TEXT PRIMARY KEY, ts REAL, raw TEXT, frame TEXT)')
        target.executemany('INSERT INTO hot VALUES (?,?,?,?)', [(ident, instant, '{}', payload) for ident, instant, payload in rows])
        target.commit()
    shutil.copy2(code_archive, output_directory / 'code.tar.gz')
    atomic_json(output_directory / 'plan.json', plan)
    return plan


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', type=Path, required=True)
    parser.add_argument('--code-archive', type=Path, required=True)
    parser.add_argument('--output-directory', type=Path, required=True)
    args = parser.parse_args()
    plan = freeze(args.database, args.code_archive, args.output_directory, datetime.datetime.now(datetime.timezone.utc))
    print(json.dumps({key: plan[key] for key in ('protocol', 'training_records', 'holdout_start_utc', 'holdout_end_utc')}))


if __name__ == '__main__':
    main()
