#!/usr/bin/env python3
"""One authorized 100ms pulse and a real ten-minute private feedback window.

Uses a separate diagnostic ledger. It does not propose a natural intervention,
change the production policy, or establish a causal effect. Never shorten the
window with historical frames. Keep device-derived reports on the device.
"""

import argparse
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from traceform.evolution.model_registry import atomic_json
from traceform.forecasting.evaluation import timestamp
from traceform.intervention.closed_loop import ClosedLoopIntervention, MicroActuator


def latest_frame(database):
    with closing(sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True)) as source:
        row = source.execute('SELECT frame FROM hot ORDER BY ts DESC LIMIT 1').fetchone()
    if row is None:
        raise ValueError('live frame required')
    frame = json.loads(row[0])
    if frame.get('source_mode') != 'live_follow' or not 0 <= time.time() - timestamp(frame).timestamp() <= 90:
        raise ValueError('fresh live-follow frame required')
    return frame


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', type=Path, required=True)
    parser.add_argument('--silent-file', type=Path, required=True)
    parser.add_argument('--output-directory', type=Path, required=True)
    parser.add_argument('--execute-pulse', action='store_true', required=True)
    args = parser.parse_args()
    if args.silent_file.exists():
        raise ValueError('silent marker prohibits controlled execution')
    frame = latest_frame(args.database)
    args.output_directory.mkdir(parents=True, exist_ok=False)
    actuator = MicroActuator(silent_file=args.silent_file)
    loop = ClosedLoopIntervention(args.output_directory / 'diagnostic_trials.sqlite', actuator, mode='active', actions=('pulse',))
    identifier = uuid.uuid4().hex
    report = {'scope': 'controlled_execution_and_observational_feedback_not_natural_trigger_or_causal_effect',
              'action': 'pulse', 'pulse_duration_ms': 100, 'required_post_delivery_seconds': 600,
              'production_policy_modified': False, 'trial_id': identifier, 'observed_frames': 0}
    output = args.output_directory / 'report.json'
    try:
        loop.observe(frame)
        receipt = actuator.execute('pulse')
        report['execution_receipt'] = receipt
        if not receipt['delivered']:
            report['status'] = 'delivery_failed'
            atomic_json(output, report)
            print(json.dumps(report), flush=True)
            return 1
        # Start this diagnostic window after delivery acknowledgment, rather
        # than at the earlier source-frame timestamp used by natural proposals.
        created = receipt['finished_epoch']
        started = time.monotonic()
        with loop.db:
            loop.db.execute('INSERT INTO trials VALUES (?,?,?,?,?,?,?,?,NULL)',
                            (identifier, 'controlled_diagnostic', 'pulse', 1.0, created,
                             json.dumps(frame), 'observing', json.dumps(receipt)))
        last = timestamp(frame).timestamp()
        report.update(status='observing', window_started_epoch=created)
        atomic_json(output, report)
        print(json.dumps({'status': 'observing', 'receipt': receipt}), flush=True)
        while time.monotonic() - started <= 900:
            if args.silent_file.exists():
                report.update(status='censored', reason='silent_marker')
                break
            if abs((time.time() - created) - (time.monotonic() - started)) > 5:
                report.update(status='censored', reason='wall_clock_changed')
                break
            with closing(sqlite3.connect(args.database.resolve().as_uri() + '?mode=ro', uri=True)) as source:
                frames = [json.loads(row[0]) for row in source.execute(
                    'SELECT frame FROM hot WHERE ts > ? ORDER BY ts,id', (last,))]
            for current in frames:
                last = timestamp(current).timestamp()
                outcomes = loop.observe(current)
                report['observed_frames'] += 1
                if outcomes:
                    report['outcome'] = outcomes[0]
                    report['status'] = outcomes[0]['status']
                    report['observed_post_delivery_seconds'] = last - created
                    report['elapsed_monotonic_seconds'] = time.monotonic() - started
                    if report['status'] == 'evaluated' and report['elapsed_monotonic_seconds'] < 600:
                        report.update(status='censored', reason='insufficient_real_elapsed_time')
                    atomic_json(output, report)
                    print(json.dumps(report), flush=True)
                    return 0 if report['status'] == 'evaluated' else 1
            atomic_json(output, report)
            time.sleep(5)
        else:
            report.update(status='censored', reason='900_second_diagnostic_timeout')
        with loop.db:
            loop.db.execute("UPDATE trials SET status='censored',outcome=? WHERE id=?", (json.dumps(report), identifier))
        atomic_json(output, report)
        print(json.dumps(report), flush=True)
        return 1
    finally:
        loop.close()


if __name__ == '__main__':
    sys.exit(main())
