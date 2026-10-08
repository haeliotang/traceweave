from contextlib import closing
import datetime
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from scripts.audit_field_runtime import audit


class TestFieldRuntimeAudit(unittest.TestCase):
    def test_snapshot_overlap_rotated_events_and_missing_hot_matches_are_explicit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            frames = [{'timestamp_utc': (start + datetime.timedelta(seconds=seconds)).isoformat(),
                       'model_version': 'fixture', 'continuous_from_previous': index != 2,
                       'dwell_steps': dwell} for index, (seconds, dwell) in enumerate(((0, 1), (30, 2), (400, 60)))]
            databases = [root / name for name in ('snapshot.sqlite', 'live.sqlite')]
            for path, indices in zip(databases, ((0, 1), (1, 2))):
                with closing(sqlite3.connect(path)) as database:
                    database.execute('CREATE TABLE hot (id TEXT,ts REAL,raw TEXT,frame TEXT)')
                    for index in indices:
                        raw = {'availability': {'motion': 'AVAILABLE'}, 'private_value': 'never_export'}
                        database.execute('INSERT INTO hot VALUES (?,?,?,?)',
                                         (str(index), start.timestamp() + (0, 30, 400)[index], json.dumps(raw), json.dumps(frames[index])))
                    database.commit()
            events = root / 'events.jsonl'
            (root / 'events.jsonl.segment.001').write_text(json.dumps({'event_type': 'CSD_TIPPING', 'timestamp_utc': frames[1]['timestamp_utc']}) + '\n')
            events.write_text(json.dumps({'event_type': 'PROLONGED_DWELL', 'timestamp_utc': frames[2]['timestamp_utc']}) + '\n' +
                              json.dumps({'event_type': 'CSD_TIPPING', 'timestamp_utc': frames[0]['timestamp_utc']}) + '\n')
            trials = root / 'trials.sqlite'
            with closing(sqlite3.connect(trials)) as database:
                database.execute('CREATE TABLE trials (status TEXT)')
                database.commit()
            report = audit(databases, events, trials)
            self.assertEqual(report['unique_retained_frames'], 3)
            self.assertEqual(report['event_counts']['CSD_TIPPING'], 2)
            self.assertEqual(report['matched_trigger_blocks'], {'dwell_below_threshold': 1, 'requires_counterfactual_and_policy_review': 1})
            self.assertEqual(report['utc_days']['2026-10-01']['gaps_over_300'], 1)
            self.assertEqual(report['utc_days']['2026-10-01']['discontinuities'], 1)
            self.assertEqual(report['availability_status_counts']['motion']['AVAILABLE'], 3)
            self.assertNotIn('never_export', json.dumps(report))
            with closing(sqlite3.connect(databases[-1])) as database:
                database.execute("UPDATE hot SET frame='{}' WHERE id='1'")
                database.commit()
            with self.assertRaisesRegex(ValueError, 'conflicting retained'):
                audit(databases, events, trials)
