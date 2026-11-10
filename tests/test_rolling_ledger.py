# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import datetime
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from traceform.streaming.rolling_ledger import RollingLedger, RotationAwareTail


class TestRollingLedger(unittest.TestCase):
    def test_reader_drains_multiple_rotations_without_loss(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            writer = RollingLedger(path, max_bytes=10)
            writer.append({'id': 0})
            with RotationAwareTail(path) as reader:
                for index in range(1, 5):
                    writer.append({'id': index})
                self.assertEqual([reader.read_record()['id'] for _ in range(5)], list(range(5)))
                self.assertIsNone(reader.read_record())

    def test_partial_line_is_not_consumed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            path.write_text('{"id":')
            with RotationAwareTail(path) as reader:
                self.assertIsNone(reader.read_record())
                with path.open('a') as output:
                    output.write('1}\n')
                self.assertEqual(reader.read_record(), {'id': 1})

    def test_retention_and_missing_rotation_signal(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            now = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            writer = RollingLedger(path, max_bytes=10)
            writer.append({'id': 0}, now)
            with RotationAwareTail(path, follow=True) as reader:
                writer.append({'id': 1}, now + datetime.timedelta(days=3))
                writer.compact(now + datetime.timedelta(days=3))
                self.assertEqual(reader.read_record(), {'id': 1})
                self.assertTrue(reader.discontinuous)
                self.assertEqual(len(list(path.parent.glob(path.name + '.segment.*'))), 0)

    def test_record_limit_and_timezone_are_validated(self):
        with tempfile.TemporaryDirectory() as directory:
            writer = RollingLedger(Path(directory) / 'x', max_bytes=8)
            with self.assertRaises(ValueError):
                writer.append({'oversized': 'abcdefgh'})
            with self.assertRaises(ValueError):
                writer.append({}, datetime.datetime(2026, 10, 1))

    def test_writer_recovers_incomplete_crash_tail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            path.write_text('{"id":')
            with RotationAwareTail(path) as reader:
                RollingLedger(path).append({'id': 1})
                self.assertEqual(reader.read_record(), {'id': 1})
                self.assertTrue(reader.discontinuous)

    def test_generation_remains_unique_when_wall_clock_repeats(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            writer = RollingLedger(path, max_bytes=10)
            writer.append({'id': 0})
            with RotationAwareTail(path) as reader, patch('traceform.streaming.rolling_ledger.time.time_ns', return_value=1):
                for index in range(1, 4):
                    writer.append({'id': index})
                self.assertEqual([reader.read_record()['id'] for _ in range(4)], [0, 1, 2, 3])

    def test_event_retention_is_separate_from_raw_and_expires_idle_tail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.jsonl'
            now = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            writer = RollingLedger(path, max_bytes=10, retention_seconds=30 * 86400)
            writer.append({'id': 0}, now)
            writer.append({'id': 1}, now + datetime.timedelta(days=3))
            writer.compact(now + datetime.timedelta(days=4))
            self.assertEqual(len(list(path.parent.glob(path.name + '.segment.*'))), 1)
            writer.compact(now + datetime.timedelta(days=34))
            self.assertEqual(path.read_text(), '')
            self.assertEqual(list(path.parent.glob(path.name + '.segment.*')), [])

    def test_exact_retention_clips_only_expired_prefix_and_preserves_reader_cursor(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            writer = RollingLedger(path, timestamp_field='timestamp_utc')
            old = {'id': 0, 'timestamp_utc': start.isoformat()}
            retained = {'id': 1, 'timestamp_utc': (start + datetime.timedelta(minutes=30)).isoformat()}
            writer.append(old, start)
            writer.append(retained, start + datetime.timedelta(minutes=30))
            with RotationAwareTail(path) as reader:
                self.assertEqual(reader.read_record(), old)
                self.assertEqual(reader.read_record(), retained)
                cutoff = start + datetime.timedelta(hours=48, minutes=15)
                writer.compact(cutoff)
                self.assertEqual(path.read_text().splitlines(), [json.dumps(retained, separators=(',', ':'))])
                next_record = {'id': 2, 'timestamp_utc': cutoff.isoformat()}
                writer.append(next_record, cutoff)
                self.assertEqual(reader.read_record(), next_record)
                self.assertTrue(reader.discontinuous)

    def test_exact_retention_does_not_read_unlinked_expired_tail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            writer = RollingLedger(path, timestamp_field='timestamp_utc')
            writer.append({'id': 0, 'timestamp_utc': start.isoformat()}, start)
            writer.append({'id': 1, 'timestamp_utc': (start + datetime.timedelta(minutes=10)).isoformat()}, start + datetime.timedelta(minutes=10))
            with RotationAwareTail(path) as reader:
                later = start + datetime.timedelta(days=3)
                writer.append({'id': 2, 'timestamp_utc': later.isoformat()}, later)
                self.assertEqual(reader.read_record()['id'], 2)
                self.assertTrue(reader.discontinuous)

    def test_exact_retention_replacement_failure_keeps_committed_source(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            writer = RollingLedger(path, timestamp_field='timestamp_utc')
            writer.append({'timestamp_utc': start.isoformat()}, start)
            writer.append({'timestamp_utc': (start + datetime.timedelta(minutes=30)).isoformat()}, start + datetime.timedelta(minutes=30))
            original = path.read_bytes()
            with patch('traceform.streaming.rolling_ledger.os.replace', side_effect=OSError('replacement failed')):
                with self.assertRaises(OSError):
                    writer.compact(start + datetime.timedelta(hours=48, minutes=15))
            self.assertEqual(path.read_bytes(), original)
            self.assertFalse(path.with_name('.' + path.name + '.retention.tmp').exists())
            writer.compact(start + datetime.timedelta(hours=48, minutes=15))
            self.assertEqual(len(path.read_text().splitlines()), 1)

    def test_exact_retention_keeps_cutoff_and_rejects_unknown_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            now = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            writer = RollingLedger(path, timestamp_field='timestamp_utc')
            writer.append({'timestamp_utc': now.isoformat()}, now)
            writer.compact(now + datetime.timedelta(hours=48))
            self.assertEqual(len(path.read_text().splitlines()), 1)
            with self.assertRaises(KeyError):
                writer.append({'id': 'missing'}, now)
            with self.assertRaises(ValueError):
                writer.append({'timestamp_utc': '2026-10-01T00:00:00'}, now)
