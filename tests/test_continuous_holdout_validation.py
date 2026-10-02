import unittest
import datetime
import json
from pathlib import Path
import sqlite3
import tempfile
from scripts.validate_continuous_holdout import validate, validate_sqlite


class TestContinuousHoldoutValidation(unittest.TestCase):
    def test_sqlite_partition_uses_time_and_never_updates_source(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'edge.sqlite'
            rows = self.rows(0) + self.rows(4)
            with sqlite3.connect(path) as connection:
                connection.execute('CREATE TABLE hot (id TEXT, ts REAL, raw TEXT, frame TEXT)')
                for i in reversed(range(len(rows))):
                    connection.execute('INSERT INTO hot VALUES (?,?,?,?)',
                                       (str(i), i, 'private raw sentinel', json.dumps(rows[i])))
            before = path.read_bytes()
            split = datetime.datetime.fromisoformat('2026-10-02T00:00:04+00:00')
            result = validate_sqlite(path, split, dimension=1)
            self.assertEqual(result['training_record_count'], 4)
            self.assertEqual(result['holdout_record_count'], 4)
            self.assertEqual(result['training_pair_count'], 3)
            self.assertAlmostEqual(result['drift_increment_mse'], 0)
            self.assertEqual(path.read_bytes(), before)
            self.assertNotIn('private raw sentinel', json.dumps(result))
            with self.assertRaisesRegex(ValueError, 'timezone-aware'):
                validate_sqlite(path, split.replace(tzinfo=None), dimension=1)
            with self.assertRaisesRegex(ValueError, 'complete dimension'):
                validate_sqlite(path, split + datetime.timedelta(hours=1), dimension=1)

    def rows(self, start):
        return [{'timestamp_utc': f'2026-10-02T00:00:{i:02d}Z', 'continuous_vector': [2.0 * i],
                 'state_token': 'A', 'model_version': 'v1', 'model_sha256': '1' * 64,
                 'normalizer_sha256': '2' * 64, 'boot_id': 'b', 'continuity_epoch': 1,
                 'source_mode': 'synthetic'} for i in range(start, start + 4)]

    def test_known_drift_beats_persistence_without_holdout_fitting(self):
        result = validate(self.rows(0), self.rows(4), dimension=1)
        self.assertEqual(result['training_pair_count'], 3)
        self.assertEqual(result['holdout_pair_count'], 3)
        self.assertAlmostEqual(result['drift_increment_mse'], 0)
        self.assertEqual(result['persistence_increment_mse'], 4)
        self.assertEqual(result['relative_covariance_error'], None)
        self.assertEqual(result['scientific_acceptance'], 'not_assessed')

    def test_leakage_coordinate_changes_and_missing_frames_rejected(self):
        with self.assertRaisesRegex(ValueError, 'strictly later'):
            validate(self.rows(0), self.rows(2), dimension=1)
        holdout = self.rows(4)
        for row in holdout:
            row['normalizer_sha256'] = '3' * 64
        with self.assertRaisesRegex(ValueError, 'bindings must match'):
            validate(self.rows(0), holdout, dimension=1)
        holdout = self.rows(4)
        del holdout[1]['continuous_vector']
        with self.assertRaisesRegex(ValueError, 'complete dimension'):
            validate(self.rows(0), holdout, dimension=1)

    def test_full_covariance_keeps_cross_coordinate_residuals(self):
        train, holdout = self.rows(0), self.rows(4)
        for rows, values in ((train, [0, 1, 0, 1]), (holdout, [1, 2, 1, 2])):
            for row, value in zip(rows, values):
                row['continuous_vector'] = [float(value), float(value)]
        result = validate(train, holdout, bandwidth=1e9, dimension=2)
        self.assertAlmostEqual(result['residual_covariance_rate'][0][1], 8 / 9)
        self.assertAlmostEqual(result['predicted_covariance_rate'][0][1], 8 / 9)
        self.assertAlmostEqual(result['relative_covariance_error'], 0)
