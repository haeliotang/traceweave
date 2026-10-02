import unittest
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
from scripts.audit_continuous_records import audit, audit_sqlite


class TestContinuousRecordReadiness(unittest.TestCase):
    def test_sqlite_reads_ordered_frames_without_mutating_or_exporting_raw(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'edge.sqlite'
            rows = self.records()
            with sqlite3.connect(path) as connection:
                connection.execute('CREATE TABLE hot (id TEXT PRIMARY KEY, ts REAL, raw TEXT, frame TEXT)')
                for i in reversed(range(3)):
                    connection.execute('INSERT INTO hot VALUES (?,?,?,?)',
                                       (str(i), i, 'private raw sentinel', json.dumps(rows[i])))
            before = path.read_bytes()
            result = audit_sqlite(path)
            self.assertEqual(result['eligible_adjacent_pairs'], 2)
            self.assertTrue(result['ready_for_fit'])
            self.assertEqual(path.read_bytes(), before)
            self.assertNotIn('private raw sentinel', json.dumps(result))
            digest = hashlib.sha256()
            for row in rows:
                payload = json.dumps(row).encode()
                digest.update(len(payload).to_bytes(8, 'big'))
                digest.update(payload)
            self.assertEqual(result['ordered_frame_sha256'], digest.hexdigest())
            missing = Path(directory) / 'missing.sqlite'
            with self.assertRaises(sqlite3.OperationalError):
                audit_sqlite(missing)
            self.assertFalse(missing.exists())

    def records(self):
        return [{'timestamp_utc': f'2026-10-02T00:00:0{i}Z', 'continuous_vector': [float(i)] * 128,
                 'model_version': 'v1', 'model_sha256': '1' * 64, 'normalizer_sha256': '2' * 64,
                 'state_token': 'A', 'boot_id': 'boot', 'continuity_epoch': 1, 'source_mode': 'synthetic'} for i in range(3)]

    def test_accepts_bound_records_and_rejects_legacy_shape(self):
        self.assertTrue(audit(self.records())['ready_for_fit'])
        legacy = [{'timestamp_utc': row['timestamp_utc'], 'normalized_features': [0] * 26} for row in self.records()]
        result = audit(legacy)
        self.assertFalse(result['ready_for_fit'])
        self.assertEqual(result['legacy_normalized_dimension_counts'], {'26': 3})
        self.assertEqual(result['eligible_adjacent_pairs'], 0)

    def test_missing_provenance_and_invalid_frames_cannot_bridge(self):
        for field in ('model_sha256', 'normalizer_sha256', 'boot_id', 'model_version'):
            rows = self.records()
            del rows[1][field]
            self.assertEqual(audit(rows)['eligible_adjacent_pairs'], 0)
        for value in (float('nan'), True, '1'):
            rows = self.records()
            rows[1]['continuous_vector'][0] = value
            self.assertEqual(audit(rows)['eligible_adjacent_pairs'], 0)
        rows = self.records()
        rows[1]['continuous_from_previous'] = False
        self.assertEqual(audit(rows)['eligible_adjacent_pairs'], 1)
        rows = self.records()
        rows[1]['normalizer_sha256'] = '3' * 64
        self.assertEqual(audit(rows)['eligible_adjacent_pairs'], 0)

    def test_multiple_bound_segments_require_separate_fits(self):
        rows = self.records()
        later = self.records()
        for i, row in enumerate(later):
            row['timestamp_utc'] = f'2026-10-02T00:00:1{i}Z'
            row['model_version'] = 'v2'
        result = audit(rows + later)
        self.assertEqual(result['coordinate_binding_count'], 2)
        self.assertFalse(result['ready_for_fit'])
