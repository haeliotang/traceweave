from contextlib import closing
import datetime
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest import mock

from scripts.verify_controlled_field_feedback import latest_frame


class TestControlledFieldFeedback(unittest.TestCase):
    def test_only_fresh_live_frames_can_start_a_real_diagnostic(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'hot.sqlite'
            now = 1791468000.0
            with closing(sqlite3.connect(path)) as database:
                database.execute('CREATE TABLE hot (ts REAL, frame TEXT)')
                for source_mode, age, accepted in (('historical_replay', 1, False),
                                                   ('live_follow', 91, False),
                                                   ('live_follow', -1, False),
                                                   ('live_follow', 30, True)):
                    frame = {'source_mode': source_mode,
                             'timestamp_utc': datetime.datetime.fromtimestamp(now - age, datetime.timezone.utc).isoformat()}
                    database.execute('DELETE FROM hot')
                    database.execute('INSERT INTO hot VALUES (?,?)', (now - age, json.dumps(frame)))
                    database.commit()
                    with mock.patch('scripts.verify_controlled_field_feedback.time.time', return_value=now):
                        if accepted:
                            self.assertEqual(latest_frame(path), frame)
                        else:
                            with self.assertRaisesRegex(ValueError, 'fresh live-follow'):
                                latest_frame(path)
            with closing(sqlite3.connect(path)) as database:
                self.assertEqual(database.execute('SELECT count(*) FROM hot').fetchone()[0], 1)
