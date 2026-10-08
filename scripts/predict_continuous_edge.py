#!/usr/bin/env python3
"""Run one private, timestamp-bound continuous sandbox job off the acquisition loop."""

import argparse
from contextlib import closing
import datetime
import json
from pathlib import Path
import sqlite3
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from traceform.evolution.model_registry import ModelRegistry, atomic_json
from traceform.forecasting.continuous_sandbox import predict_latest_segment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', type=Path, required=True)
    parser.add_argument('--model-registry', type=Path, required=True)
    parser.add_argument('--registry-version', required=True)
    parser.add_argument('--through-timestamp', required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    parser.add_argument('--rollouts', type=int, default=300)
    parser.add_argument('--horizon-steps', type=int, default=30)
    parser.add_argument('--job-id', default=None)
    args = parser.parse_args()
    started = time.monotonic()
    context = {'job_id': args.job_id or uuid.uuid4().hex, 'through_timestamp': args.through_timestamp,
               'registry_version': args.registry_version,
               'started_timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    try:
        through = datetime.datetime.fromisoformat(args.through_timestamp.replace('Z', '+00:00'))
        if through.tzinfo is None:
            raise ValueError('timezone-aware prediction boundary required')
        with closing(sqlite3.connect(args.database.resolve().as_uri() + '?mode=ro', uri=True)) as database:
            records = list(reversed([json.loads(row[0]) for row in database.execute(
                'SELECT frame FROM hot WHERE ts <= ? ORDER BY ts DESC LIMIT 64', (through.timestamp(),))]))
        bundle = ModelRegistry(args.model_registry).bundle(args.registry_version)
        result = predict_latest_segment(records, bundle, args.horizon_steps, args.rollouts)
        if result['initial_timestamp_utc'] != args.through_timestamp:
            raise ValueError('trigger frame is not retained at prediction boundary')
        result.update(context, runtime_seconds=time.monotonic() - started,
                      completed_timestamp_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
        atomic_json(args.output_file, result)
        return 0
    except Exception as error:
        atomic_json(args.output_file, {'event_type': 'CONTINUOUS_SANDBOX_FAILED',
                                      'error_type': type(error).__name__, 'reason': str(error),
                                      **context, 'runtime_seconds': time.monotonic() - started,
                                      'completed_timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()})
        return 1


if __name__ == '__main__':
    sys.exit(main())
