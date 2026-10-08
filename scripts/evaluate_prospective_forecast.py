#!/usr/bin/env python3
"""Verify a frozen private window, then score only with its archived code."""

import argparse
from collections import Counter
from contextlib import closing
import datetime
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tarfile
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.audit_continuous_records import audit
from traceform.evolution.model_registry import atomic_json
from traceform.forecasting.evaluation import timestamp


def read_rows(path, start=None, end=None):
    with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)) as database:
        database.execute('BEGIN')
        query = 'SELECT id,ts,frame FROM hot'
        parameters = ()
        if start is not None:
            query += ' WHERE ts >= ? AND ts < ?'
            parameters = (start.timestamp(), end.timestamp())
        return database.execute(query + ' ORDER BY ts,id', parameters).fetchall()


def frame_digest(rows):
    digest = hashlib.sha256()
    for _, _, payload in rows:
        encoded = payload.encode('utf-8')
        digest.update(len(encoded).to_bytes(8, 'big'))
        digest.update(encoded)
    return digest.hexdigest()


def readiness(frozen, databases, now):
    if now.tzinfo is None:
        raise ValueError('timezone-aware evaluation time required')
    plan_bytes = (frozen / 'plan.json').read_bytes()
    plan = json.loads(plan_bytes)
    expected = {'continuous': {'bandwidth': 1, 'max_anchors': 16, 'dimension': 128,
                              'primary_metric': 'paired_mse_reduction_over_persistence',
                              'secondary': 'interval_strata_and_full_covariance_rates'},
                'discrete': {'models': ['persist', 'first_order', 'dwell', 'periodic'],
                             'timezone': 'Asia/Kathmandu',
                             'metrics': ['top1_accuracy', 'log_loss', 'brier', 'ece_10_bins']},
                'bootstrap': {'draws': 1000, 'seed': 42,
                              'scope': 'descriptive_day_blocks_not_proven_independent_days'}}
    if (plan.get('protocol') != 'traceform.prospective-forecast.v1'
            or any(plan.get(key) != value for key, value in expected.items())
            or plan.get('full_utc_days_required') != 4 or plan.get('minimum_frames_per_utc_day') != 2300
            or plan.get('max_gap_seconds') != 300):
        raise ValueError('unsupported frozen protocol parameters')
    archive = frozen / 'code.tar.gz'
    with archive.open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != plan['code_archive_sha256']:
            raise ValueError('frozen code archive digest mismatch')
    train_rows = read_rows(frozen / 'training.sqlite')
    if len(train_rows) != plan['training_records'] or frame_digest(train_rows) != plan['training_frame_sha256']:
        raise ValueError('frozen training digest or count mismatch')
    train = [json.loads(row[2]) for row in train_rows]
    if not train or train[-1]['timestamp_utc'] != plan['training_last_timestamp_utc']:
        raise ValueError('frozen training boundary mismatch')
    start = datetime.datetime.fromisoformat(plan['holdout_start_utc'])
    end = datetime.datetime.fromisoformat(plan['holdout_end_utc'])
    frozen_time = datetime.datetime.fromisoformat(plan['frozen_timestamp_utc'])
    if (frozen_time.tzinfo is None or start.utcoffset() != datetime.timedelta(0) or end.utcoffset() != datetime.timedelta(0)
            or start.time() != datetime.time() or end - start != datetime.timedelta(days=4)
            or start.date() != frozen_time.astimezone(datetime.timezone.utc).date() + datetime.timedelta(days=1)
            or timestamp(train[-1]) > frozen_time or timestamp(train[-1]) >= start):
        raise ValueError('invalid predeclared training or holdout boundary')
    for ident, instant, payload in train_rows:
        if instant != timestamp(json.loads(payload)).timestamp():
            raise ValueError('training row timestamp mismatch')
    bindings = plan['coordinate_bindings']
    if (set(bindings) != {'model_version', 'model_sha256', 'normalizer_sha256'} or len(train) < 64
            or any(any(record.get(key) != value for key, value in bindings.items())
            or record.get('source_mode') != 'live_follow' for record in train)
            or audit(train)['eligible_records'] != len(train)
            or any(timestamp(b) <= timestamp(a) for a, b in zip(train, train[1:]))):
        raise ValueError('invalid frozen training coordinates')
    seen = {}
    for database in databases:
        for row in read_rows(database, start, end):
            ident, instant, payload = row
            if ident in seen and seen[ident] != row:
                raise ValueError('conflicting retained frame identity')
            seen[ident] = row
    holdout_rows = sorted(seen.values(), key=lambda row: (row[1], row[0]))
    holdout = [json.loads(row[2]) for row in holdout_rows]
    reasons = []
    if any(row[1] != timestamp(record).timestamp() for row, record in zip(holdout_rows, holdout)):
        reasons.append('holdout_row_timestamp_mismatch')
    if any(any(record.get(key) != value for key, value in bindings.items()) for record in holdout):
        reasons.append('coordinate_binding_changed')
    if any(record.get('source_mode') != 'live_follow' for record in holdout):
        reasons.append('non_live_holdout')
    if audit(holdout)['eligible_records'] != len(holdout):
        reasons.append('incomplete_holdout_coordinates')
    if any(timestamp(b) <= timestamp(a) for a, b in zip(holdout, holdout[1:])):
        reasons.append('duplicate_or_nonincreasing_timestamp')
    counts = Counter(timestamp(record).astimezone(datetime.timezone.utc).date().isoformat() for record in holdout)
    days = [(start + datetime.timedelta(days=index)).date().isoformat() for index in range(4)]
    complete = now >= end
    if complete and any(counts[day] < 2300 for day in days):
        reasons.append('missing_predeclared_day_coverage')
    report = {'protocol': plan['protocol'], 'status': 'blocked' if reasons else 'ready' if complete else 'pending',
              'blockers': reasons, 'checked_timestamp_utc': now.isoformat(),
              'plan_sha256': hashlib.sha256(plan_bytes).hexdigest(),
              'code_archive_sha256': plan['code_archive_sha256'],
              'training_frame_sha256': plan['training_frame_sha256'],
              'holdout_frame_sha256': frame_digest(holdout_rows),
              'holdout_start_utc': plan['holdout_start_utc'], 'holdout_end_utc': plan['holdout_end_utc'],
              'training_records': len(train), 'holdout_records': len(holdout),
              'frames_per_predeclared_utc_day': {day: counts[day] for day in days},
              'scientific_acceptance': 'not_assessed', 'automatic_activation': False,
              'scope': 'single_participant_fixed_window_next_observation_descriptive_evaluation'}
    return report, train, holdout, plan


def score_archived(frozen, train, holdout, plan):
    with tempfile.TemporaryDirectory(prefix='traceform-prospective-') as temporary:
        root = Path(temporary)
        with (frozen / 'code.tar.gz').open('rb') as source:
            if hashlib.file_digest(source, 'sha256').hexdigest() != plan['code_archive_sha256']:
                raise ValueError('frozen code archive digest mismatch at execution')
            source.seek(0)
            with tarfile.open(fileobj=source, mode='r:gz') as archive:
                extract_archive(archive, root)
        inputs = root / 'evaluation_inputs.json'
        inputs.write_text(json.dumps({'train': train, 'holdout': holdout, 'plan': plan}))
        outputs = root / 'scores.json'
        worker = '''import datetime,json,sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
from scripts.validate_continuous_holdout import validate
from traceform.forecasting.evaluation import evaluate_forecasts
r=json.loads(Path(sys.argv[2]).read_text());p=r['plan']
scores={'continuous':validate(r['train'],r['holdout'],bandwidth=1,dimension=128,include_covariance=True),
'discrete':evaluate_forecasts(r['train']+r['holdout'],datetime.datetime.fromisoformat(p['holdout_start_utc']),timezone_name='Asia/Kathmandu',max_gap_seconds=300),
'bootstrap_scope':{'continuous':'UTC_days','discrete':'Asia/Kathmandu_calendar_days','interpretation':'descriptive_not_independent_days'}}
Path(sys.argv[3]).write_text(json.dumps(scores))
'''
        subprocess.run([sys.executable, '-I', '-c', worker, str(root), str(inputs), str(outputs)],
                       check=True, capture_output=True, timeout=3600, cwd=root)
        return json.loads(outputs.read_text())


def extract_archive(archive, root):
    source_members = []
    for member in archive.getmembers():
        parts = Path(member.name).parts
        if '..' in parts:
            raise ValueError('unsafe frozen archive member')
        sidecar = (len(parts) == 1 and parts[0] in ('._scripts', '._traceform')
                   or len(parts) > 1 and parts[0] in ('scripts', 'traceform') and parts[-1].startswith('._'))
        if sidecar and member.isfile():
            with archive.extractfile(member) as metadata:
                if metadata.read(4) != b'\x00\x05\x16\x07':
                    raise ValueError('invalid AppleDouble metadata member')
            continue
        if (not parts or parts[0] not in ('scripts', 'traceform') or '..' in parts
                or not (member.isfile() or member.isdir())):
            raise ValueError('unsafe frozen archive member')
        if '__pycache__' not in parts and not member.name.endswith(('.pyc', '.pyo')):
            source_members.append(member)
    archive.extractall(root, members=source_members, filter='data')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--frozen-directory', type=Path, required=True)
    parser.add_argument('--database', type=Path, action='append', required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    if (args.frozen_directory.resolve() in args.output_file.resolve().parents
            or args.output_file.resolve() in [path.resolve() for path in args.database]):
        raise ValueError('write evaluation receipt outside the immutable frozen directory')
    try:
        report, train, holdout, plan = readiness(args.frozen_directory, args.database,
                                                datetime.datetime.now(datetime.timezone.utc))
    except (ValueError, KeyError, OSError, sqlite3.Error) as error:
        atomic_json(args.output_file, {'status': 'blocked', 'blockers': ['integrity_validation_failed'],
                                      'error_type': type(error).__name__, 'reason': str(error),
                                      'scientific_acceptance': 'not_assessed', 'automatic_activation': False})
        return 1
    atomic_json(args.output_file, report)
    if report['status'] == 'ready':
        try:
            report['scores'] = score_archived(args.frozen_directory, train, holdout, plan)
            report['status'] = 'evaluated'
        except (subprocess.SubprocessError, ValueError, OSError, tarfile.TarError) as error:
            report.update(status='execution_failed', error_type=type(error).__name__, reason=str(error))
            if isinstance(error, subprocess.CalledProcessError) and error.stderr:
                report['worker_stderr'] = error.stderr.decode('utf-8', errors='replace')[:4096]
        report['completed_timestamp_utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        atomic_json(args.output_file, report)
    print(json.dumps({'status': report['status'], 'blockers': report['blockers']}))
    return int(report['status'] in ('blocked', 'execution_failed'))


if __name__ == '__main__':
    raise SystemExit(main())
