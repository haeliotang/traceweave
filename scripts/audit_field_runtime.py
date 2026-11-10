#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Read retained device snapshots and report private runtime/gate aggregates.

Retained coverage is not proof of complete uptime or sensor measurement validity.
No trajectory rows are exported. Device-derived reports remain private.
"""

import argparse
from collections import Counter
from contextlib import closing
import datetime
import hashlib
import json
from pathlib import Path
import sqlite3
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from traceform.intervention.closed_loop import TRIGGER_EVENT_TYPES


def audit(databases, events_file, trials_file, dwell_threshold=60):
    seen, daily, hot = {}, {}, {}
    digest = hashlib.sha256()
    availability = Counter()
    for path in databases:
        with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)) as database:
            database.execute('BEGIN')
            for ident, instant, raw, payload in database.execute('SELECT id,ts,raw,frame FROM hot ORDER BY ts,id'):
                fingerprint = hashlib.sha256(payload.encode()).digest()
                if ident in seen:
                    if seen[ident] != fingerprint:
                        raise ValueError('conflicting retained frame identity')
                frame = json.loads(payload)
                if path == databases[-1]:
                    hot[frame['timestamp_utc']] = frame
                if ident in seen:
                    continue
                seen[ident] = fingerprint
                encoded = payload.encode()
                digest.update(len(encoded).to_bytes(8, 'big'))
                digest.update(encoded)
                day = datetime.datetime.fromtimestamp(instant, datetime.timezone.utc).date().isoformat()
                row = daily.setdefault(day, {'timestamps': [], 'versions': set(), 'discontinuities': 0,
                                             'max_dwell': 0, 'above_dwell_threshold': 0})
                row['timestamps'].append(instant)
                row['versions'].add(frame.get('model_version'))
                row['discontinuities'] += int(frame.get('continuous_from_previous') is False)
                row['max_dwell'] = max(row['max_dwell'], frame.get('dwell_steps', 0))
                row['above_dwell_threshold'] += int(frame.get('dwell_steps', 0) >= dwell_threshold)
                for modality, status in json.loads(raw).get('availability', {}).items():
                    availability[(modality, status)] += 1
    for row in daily.values():
        instants = sorted(row.pop('timestamps'))
        gaps = [b - a for a, b in zip(instants, instants[1:])]
        row.update(frames=len(instants), span_seconds=instants[-1] - instants[0],
                   max_gap_seconds=max(gaps, default=None), gaps_over_300=sum(gap > 300 for gap in gaps),
                   coordinate_versions=len(row.pop('versions')))
    events, matched, blocks = Counter(), Counter(), Counter()
    gate_status, gate_reasons, gate_days = Counter(), Counter(), {}
    invalid_lines = 0
    event_paths = sorted(events_file.parent.glob(events_file.name + '.segment.*'))
    if events_file.exists():
        event_paths.append(events_file)
    for path in event_paths:
        with path.open() as source:
            for line in source:
                try:
                    event = json.loads(line)
                except ValueError:
                    invalid_lines += 1
                    continue
                kind = event.get('event_type', 'untyped')
                events[kind] += 1
                if kind == 'INTERVENTION_GATE':
                    gate_status[event.get('status', 'missing')] += 1
                    gate_reasons[event.get('reason', 'missing')] += 1
                    instant = datetime.datetime.fromisoformat(event['recorded_timestamp_utc'].replace('Z', '+00:00'))
                    day = instant.astimezone(datetime.timezone.utc).date().isoformat()
                    reasons = gate_days.setdefault(day, Counter())
                    reasons[event.get('reason', 'missing')] += 1
                if kind in TRIGGER_EVENT_TYPES and event.get('timestamp_utc') in hot:
                    matched[kind] += 1
                    frame = hot[event['timestamp_utc']]
                    reason = 'dwell_below_threshold' if frame.get('dwell_steps', 0) < dwell_threshold else 'requires_counterfactual_and_policy_review'
                    blocks[reason] += 1
    with closing(sqlite3.connect(trials_file.resolve().as_uri() + '?mode=ro', uri=True)) as database:
        trials = dict(database.execute('SELECT status,count(*) FROM trials GROUP BY status'))
        failed_delivery = Counter(json.loads(receipt).get('reason', 'missing') for (receipt,) in
                                  database.execute("SELECT receipt FROM trials WHERE status='delivery_failed'"))
    return {'scope': 'retained_field_coverage_and_matched_gate_audit_not_complete_uptime_or_causal_evidence',
            'source_frame_sha256': digest.hexdigest(), 'database_count': len(databases),
            'unique_retained_frames': len(seen), 'utc_days': daily, 'hot_frames': len(hot),
            'availability_status_counts': {modality: {status: count for (name, status), count in availability.items() if name == modality}
                                           for modality, _ in availability},
            'event_counts': dict(events), 'invalid_event_lines': invalid_lines,
            'matched_trigger_counts': dict(matched), 'matched_trigger_blocks': dict(blocks),
            'recorded_natural_gate_status_counts': dict(gate_status), 'recorded_natural_gate_reasons': dict(gate_reasons),
            'recorded_natural_gate_utc_days': {day: dict(reasons) for day, reasons in gate_days.items()},
            'natural_failed_delivery_reasons': dict(failed_delivery),
            'dwell_threshold': dwell_threshold, 'natural_trial_status_counts': trials,
            'unmatched_event_scope': 'missing_hot_frame_cannot_establish_a_gate_reason'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', type=Path, action='append', required=True, help='Snapshots first, live hot database last')
    parser.add_argument('--events-file', type=Path, required=True)
    parser.add_argument('--trials-file', type=Path, required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.database, args.events_file, args.trials_file)
    args.output_file.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'unique_retained_frames': report['unique_retained_frames'],
                      'matched_trigger_blocks': report['matched_trigger_blocks']}))


if __name__ == '__main__':
    main()
