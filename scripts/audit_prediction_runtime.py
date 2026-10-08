#!/usr/bin/env python3
"""Aggregate retained prediction lifecycles by UTC day without exporting trajectories."""

import argparse
from collections import Counter
import datetime
import json
import math
from pathlib import Path
import statistics
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from traceform.evolution.model_registry import atomic_json


def summarize(records):
    daily, seen, undated = {}, {}, Counter()
    for record in records:
        kind = record.get('event_type')
        if kind not in ('CONTINUOUS_SANDBOX_STARTED', 'CONTINUOUS_SANDBOX_PREDICTION', 'CONTINUOUS_SANDBOX_FAILED'):
            continue
        job = record.get('job_id')
        if job:
            key = (job, 'start' if kind == 'CONTINUOUS_SANDBOX_STARTED' else 'terminal')
            if key in seen:
                if seen[key] != record:
                    raise ValueError('conflicting prediction job receipt')
                continue
            seen[key] = record
        stamp = record.get('completed_timestamp_utc') or record.get('started_timestamp_utc')
        if not stamp:
            undated[kind] += 1
            continue
        instant = datetime.datetime.fromisoformat(stamp.replace('Z', '+00:00'))
        if instant.tzinfo is None:
            raise ValueError('timezone-aware lifecycle timestamp required')
        day = instant.astimezone(datetime.timezone.utc).date().isoformat()
        row = daily.setdefault(day, {'started': 0, 'succeeded': 0, 'failed': 0, 'timed_out': 0,
                                     'missing_runtime': 0, 'runtime_values': [], 'failure_reasons': Counter()})
        if kind == 'CONTINUOUS_SANDBOX_STARTED':
            row['started'] += 1
            continue
        if kind == 'CONTINUOUS_SANDBOX_PREDICTION':
            row['succeeded'] += 1
        else:
            row['failed'] += 1
            row['failure_reasons'][record.get('reason', 'unspecified')] += 1
            row['timed_out'] += int(record.get('reason') == '900_second_timeout')
        elapsed = record.get('runtime_seconds')
        if isinstance(elapsed, (int, float)) and not isinstance(elapsed, bool) and math.isfinite(elapsed) and elapsed >= 0:
            row['runtime_values'].append(elapsed)
        else:
            row['missing_runtime'] += 1
    for row in daily.values():
        values = sorted(row.pop('runtime_values'))
        row['runtime_seconds'] = ({'count': len(values), 'min': values[0], 'median': statistics.median(values),
                                   'p95': values[math.ceil(len(values) * 0.95) - 1], 'max': values[-1]}
                                  if values else None)
        row['failure_reasons'] = dict(row['failure_reasons'])
    started = {job for job, phase in seen if phase == 'start'}
    terminal = {job for job, phase in seen if phase == 'terminal'}
    return {'utc_days': daily, 'undated_historical_events': dict(undated),
            'retained_started_jobs_without_terminal_receipt': len(started - terminal),
            'scope': 'retained_event_receipts_not_complete_uptime_or_prediction_accuracy',
            'day_basis': 'terminal_completion_UTC_day_and_separate_start_UTC_day',
            'undated_scope': 'historical_missing_timestamps_are_not_assigned_to_a_day'}


def audit(events_file):
    paths = sorted(events_file.parent.glob(events_file.name + '.segment.*'))
    if events_file.exists():
        paths.append(events_file)
    records, invalid, missing = [], 0, 0
    for path in paths:
        try:
            with path.open() as source:
                for line in source:
                    try:
                        record = json.loads(line)
                        if not isinstance(record, dict):
                            raise ValueError('event object required')
                        if record.get('event_type', '').startswith('CONTINUOUS_SANDBOX_'):
                            records.append(record)
                    except ValueError:
                        invalid += 1
        except FileNotFoundError:
            missing += 1
    return dict(summarize(records), invalid_event_lines=invalid, segments_removed_during_read=missing)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--events-file', type=Path, required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    atomic_json(args.output_file, audit(args.events_file))


if __name__ == '__main__':
    main()
