# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from contextlib import closing
import datetime
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from scripts.freeze_prospective_forecast import freeze


class TestProspectiveFreeze(unittest.TestCase):
    def test_future_window_and_exclusive_snapshot_keep_only_training_coordinates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            now = datetime.datetime(2026, 10, 8, 14, 20, tzinfo=datetime.timezone.utc)
            source = root / 'source.sqlite'
            with closing(sqlite3.connect(source)) as database:
                database.execute('CREATE TABLE hot (id TEXT,ts REAL,frame TEXT)')
                for index in range(64):
                    instant = now - datetime.timedelta(seconds=(64 - index) * 30)
                    frame = {'timestamp_utc': instant.isoformat(), 'continuous_vector': [float(index)] * 128,
                             'state_token': 'A', 'model_version': 'fixture', 'model_sha256': '1' * 64,
                             'normalizer_sha256': '2' * 64, 'boot_id': 'boot', 'continuity_epoch': 1,
                             'source_mode': 'live_follow', 'continuous_from_previous': index > 0}
                    database.execute('INSERT INTO hot VALUES (?,?,?)', (str(index), instant.timestamp(), json.dumps(frame)))
                database.commit()
            archive = root / 'code.tar.gz'
            archive.write_bytes(b'controlled archive fixture')
            before = source.read_bytes()
            target = root / 'frozen'
            plan = freeze(source, archive, target, now)
            self.assertEqual(plan['holdout_start_utc'], '2026-10-09T00:00:00+00:00')
            self.assertEqual(plan['holdout_end_utc'], '2026-10-13T00:00:00+00:00')
            self.assertEqual(plan['training_records'], 64)
            self.assertEqual(source.read_bytes(), before)
            with closing(sqlite3.connect(target / 'training.sqlite')) as database:
                self.assertEqual(database.execute("SELECT count(*) FROM hot WHERE raw='{}'").fetchone()[0], 64)
            with self.assertRaises(FileExistsError):
                freeze(source, archive, target, now)
            with closing(sqlite3.connect(source)) as database:
                changed = dict(frame, normalizer_sha256='3' * 64)
                database.execute('UPDATE hot SET frame=? WHERE id=?', (json.dumps(changed), '63'))
                database.commit()
            with self.assertRaisesRegex(ValueError, 'complete bound'):
                freeze(source, archive, root / 'mixed', now)
            self.assertFalse((root / 'mixed').exists())
