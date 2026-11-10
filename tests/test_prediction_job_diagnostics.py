# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import datetime
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.predict_continuous_edge import main
from scripts.phone_edge_manifold_daemon import prediction_receipt
from scripts.audit_prediction_runtime import summarize


class TestPredictionDiagnostics(unittest.TestCase):
    def test_timeout_and_invalid_receipt_keep_exact_job_context(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'result.json'
            context = {'job_id': 'job', 'registry_version': 'v', 'through_timestamp': '2026-10-08T12:00:00Z',
                       'started_timestamp_utc': '2026-10-08T12:00:01+00:00'}
            job = Mock(); job.poll.return_value = None; job.wait.return_value = -9
            self.assertIsNone(prediction_receipt(job, output, context, 10, now=909))
            job.kill.assert_not_called()
            receipt = prediction_receipt(job, output, context, 10, now=910)
            self.assertEqual(receipt['reason'], '900_second_timeout')
            self.assertEqual(receipt['runtime_seconds'], 900)
            self.assertEqual(receipt['through_timestamp'], context['through_timestamp'])
            job.kill.assert_called_once(); job.wait.assert_called_once()
            job.poll.return_value = 0
            output.write_text(json.dumps(dict(context, job_id='stale', event_type='CONTINUOUS_SANDBOX_PREDICTION')))
            receipt = prediction_receipt(job, output, context, 10, now=20)
            self.assertEqual(receipt['reason'], 'missing_or_invalid_worker_receipt')
            output.write_text(json.dumps(dict(context, event_type='CONTINUOUS_SANDBOX_PREDICTION')))
            self.assertEqual(prediction_receipt(job, output, context, 10, now=20)['event_type'], 'CONTINUOUS_SANDBOX_PREDICTION')

    def test_daily_report_keeps_timeouts_and_undated_history_separate(self):
        start = {'event_type': 'CONTINUOUS_SANDBOX_STARTED', 'job_id': 'a', 'started_timestamp_utc': '2026-10-08T23:59:00Z'}
        success = {'event_type': 'CONTINUOUS_SANDBOX_PREDICTION', 'job_id': 'a',
                   'completed_timestamp_utc': '2026-10-09T00:00:00Z', 'runtime_seconds': 60}
        failure = {'event_type': 'CONTINUOUS_SANDBOX_FAILED', 'job_id': 'b',
                   'completed_timestamp_utc': '2026-10-09T01:00:00Z', 'runtime_seconds': 900,
                   'reason': '900_second_timeout'}
        report = summarize([start, start, success, success, failure, {'event_type': 'CONTINUOUS_SANDBOX_FAILED'}])
        self.assertEqual(report['utc_days']['2026-10-08']['started'], 1)
        self.assertEqual(report['utc_days']['2026-10-09']['succeeded'], 1)
        self.assertEqual(report['utc_days']['2026-10-09']['timed_out'], 1)
        self.assertEqual(report['utc_days']['2026-10-09']['runtime_seconds']['count'], 2)
        self.assertEqual(report['undated_historical_events'], {'CONTINUOUS_SANDBOX_FAILED': 1})
        self.assertEqual(report['retained_started_jobs_without_terminal_receipt'], 0)
        with self.assertRaisesRegex(ValueError, 'conflicting prediction'):
            summarize([success, dict(success, runtime_seconds=61)])

    def test_worker_failure_preserves_lifecycle_and_trigger_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / 'result.json'
            args = ['worker', '--database', str(root / 'missing.sqlite'), '--model-registry', str(root / 'registry'),
                    '--registry-version', 'fixture-version', '--through-timestamp', '2026-10-08T12:00:00Z',
                    '--output-file', str(output)]
            with patch('sys.argv', args):
                self.assertEqual(main(), 1)
            report = json.loads(output.read_text())
            self.assertGreaterEqual(report['runtime_seconds'], 0)
            self.assertLessEqual(datetime.datetime.fromisoformat(report['started_timestamp_utc']),
                                 datetime.datetime.fromisoformat(report['completed_timestamp_utc']))
            self.assertEqual(report['through_timestamp'], '2026-10-08T12:00:00Z')
            self.assertEqual(report['registry_version'], 'fixture-version')
            self.assertTrue(report['job_id'])
