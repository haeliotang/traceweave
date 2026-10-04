import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.replay_intervention_gate import HISTORY, load_frames, segment_before

ROOT = Path(__file__).resolve().parents[1]


def frame(i, **extra):
    base = {'model_version': 'm', 'boot_id': 'b', 'continuity_epoch': 1, 'source_mode': 'live_follow',
            'continuous_from_previous': i > 0, 'timestamp_utc': f'2026-10-01T00:{i // 60:02d}:{i % 60:02d}Z', 'dwell_steps': 0}
    base.update(extra)
    return base


class TestSegment(unittest.TestCase):
    def test_segment_stops_at_the_last_discontinuity(self):
        frames = [frame(i) for i in range(30)]
        frames[10]['continuous_from_previous'] = False
        self.assertEqual(len(segment_before(frames, 29)), 20)

    def test_a_change_of_boot_or_epoch_or_model_or_source_starts_a_new_segment(self):
        for field, value in (('boot_id', 'x'), ('continuity_epoch', 2), ('model_version', 'n'), ('source_mode', 'historical_replay')):
            frames = [frame(i) for i in range(30)]
            for i in range(15, 30):
                frames[i][field] = value
            self.assertEqual(len(segment_before(frames, 29)), 15, field)

    def test_history_window_is_bounded_like_the_daemon(self):
        frames = [frame(i % 3000) for i in range(HISTORY + 500)]
        self.assertEqual(len(segment_before(frames, len(frames) - 1)), HISTORY)


class TestCli(unittest.TestCase):
    def test_gate_one_counts_tipping_events_by_dwell_and_writes_only_aggregates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database = root / 'hot.sqlite'
            connection = sqlite3.connect(database)
            connection.execute('create table hot (id text primary key, ts real, raw text, frame text)')
            frames = [frame(i, dwell_steps=5 if i < 25 else 70, state_token='A', frame_interval_seconds=30.0) for i in range(40)]
            for i, f in enumerate(frames):
                connection.execute('insert into hot values (?,?,?,?)', (f'id{i}', float(i), '{}', json.dumps(f)))
            connection.commit()
            connection.close()
            events = root / 'events.jsonl'
            events.write_text('\n'.join(json.dumps({'event_type': 'CSD_TIPPING', 'timestamp_utc': frames[i]['timestamp_utc']})
                                        for i in (3, 4, 30)) + '\nnot json\n')
            out = root / 'report.json'
            subprocess.run([sys.executable, str(ROOT / 'scripts/replay_intervention_gate.py'), '--sqlite-file', str(database),
                            '--events-file', str(events), '--output-file', str(out), '--sample', '0', '--timezone', 'UTC'],
                           check=True, cwd=ROOT, capture_output=True)
            report = json.loads(out.read_text())
            self.assertEqual(report['tipping_events'], 3)
            self.assertEqual(report['gate1_events_at_or_above_threshold'], 1)
            self.assertEqual(report['gate1_dwell_at_tipping_histogram'], {'0': 2, '60': 1})
            self.assertEqual(report['gate2_frames_at_or_above_threshold'], 15)
            self.assertNotIn('2026-10-01', json.dumps({k: v for k, v in report.items() if k != 'gate1_replayed_exactly'}))

    def test_load_frames_orders_by_time(self):
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / 'h.sqlite'
            connection = sqlite3.connect(database)
            connection.execute('create table hot (id text primary key, ts real, raw text, frame text)')
            for ident, ts in (('b', 2.0), ('a', 1.0)):
                connection.execute('insert into hot values (?,?,?,?)', (ident, ts, '{}', json.dumps({'k': ident})))
            connection.commit()
            connection.close()
            self.assertEqual([f['k'] for f in load_frames(database)], ['a', 'b'])


if __name__ == '__main__':
    unittest.main()
