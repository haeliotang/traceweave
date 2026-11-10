# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import datetime
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_normalizer_candidate import synthetic_database

from scripts.evaluate_normalizer_candidate import FULL_DAY_FRAMES, holdout_days, load_hot, load_hot_with_info

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/evaluate_normalizer_candidate.py'


def subset(source, target, low, high):
    """A snapshot holding frames low..high-1 (by time order) of the source hot tier."""
    rows = sqlite3.connect(source).execute('select id, ts, raw, frame from hot order by ts').fetchall()[low:high]
    connection = sqlite3.connect(target)
    connection.execute('create table hot (id text primary key, ts real, raw text, frame text)')
    connection.executemany('insert into hot values (?,?,?,?)', rows)
    connection.commit()
    connection.close()


def stamp(day, offset):
    return datetime.datetime(2026, 10, day, tzinfo=datetime.timezone.utc).timestamp() + offset


class TestSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls._tmp.name)
        cls.full = cls.root / 'full.sqlite'
        cls.params = synthetic_database(cls.full, count=240)
        cls.first, cls.second = cls.root / 'a.sqlite', cls.root / 'b.sqlite'
        subset(cls.full, cls.first, 0, 150)
        subset(cls.full, cls.second, 100, 240)  # 50 frames overlap

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def test_overlapping_snapshots_merge_to_the_same_frames_as_the_whole_tier(self):
        merged, info = load_hot_with_info([self.first, self.second])
        whole = load_hot(self.full)
        self.assertEqual(merged, whole)
        self.assertEqual(info['source_files'], 2)
        self.assertEqual(info['overlapping_rows_dropped'], 50)

    def test_order_of_the_files_does_not_change_the_result(self):
        self.assertEqual(load_hot([self.second, self.first]), load_hot([self.first, self.second]))

    def test_the_same_id_with_different_content_is_refused(self):
        tampered = self.root / 'tampered.sqlite'
        subset(self.full, tampered, 100, 240)
        connection = sqlite3.connect(tampered)
        connection.execute("update hot set raw = raw || ' ' where id = 'id120'")
        connection.commit()
        connection.close()
        with self.assertRaises(SystemExit):
            load_hot([self.first, tampered])

    def test_a_single_path_still_works(self):
        self.assertEqual(len(load_hot(self.full)), 240)
        self.assertEqual(len(load_hot(str(self.full))), 240)

    def test_cli_with_split_snapshots_reproduces_the_single_file_report(self):
        outputs = []
        baseline = self.root / 'baseline.json'
        baseline.write_text(json.dumps(self.params.to_dict()))
        for name, files in (('one', [self.full]), ('two', [self.first, self.second])):
            out = self.root / f'{name}.json'
            args = [sys.executable, str(SCRIPT), '--baseline-normalizer-file', str(baseline), '--output-file', str(out)]
            for f in files:
                args += ['--sqlite-file', str(f)]
            subprocess.run(args, check=True, cwd=ROOT, capture_output=True)
            outputs.append(json.loads(out.read_text()))
        for key in ('split', 'replay_fidelity', 'saturation', 'forecast', 'candidate_normalizer_sha256'):
            self.assertEqual(outputs[0][key], outputs[1][key], key)
        self.assertEqual(outputs[1]['source']['overlapping_rows_dropped'], 50)

    def test_requiring_holdout_days_adds_a_check_that_short_data_fails(self):
        baseline = self.root / 'baseline.json'
        baseline.write_text(json.dumps(self.params.to_dict()))
        out = self.root / 'strict.json'
        subprocess.run([sys.executable, str(SCRIPT), '--sqlite-file', str(self.full), '--baseline-normalizer-file', str(baseline),
                        '--output-file', str(out), '--min-holdout-days', '2'], check=True, cwd=ROOT, capture_output=True)
        report = json.loads(out.read_text())
        self.assertFalse(report['acceptance']['checks']['independent_holdout_days'])
        self.assertFalse(report['acceptance']['accepted'])
        self.assertEqual(report['independent_holdout_days']['count'], 0)

    def test_without_the_option_there_is_no_day_check(self):
        baseline = self.root / 'baseline.json'
        baseline.write_text(json.dumps(self.params.to_dict()))
        out = self.root / 'plain.json'
        subprocess.run([sys.executable, str(SCRIPT), '--sqlite-file', str(self.full), '--baseline-normalizer-file', str(baseline),
                        '--output-file', str(out)], check=True, cwd=ROOT, capture_output=True)
        self.assertNotIn('independent_holdout_days', json.loads(out.read_text())['acceptance']['checks'])


class TestHoldoutDays(unittest.TestCase):
    def window(self, days, frames=FULL_DAY_FRAMES + 100):
        times = []
        for day in days:
            times += [stamp(day, i * 30) for i in range(frames)]
        return times

    def test_full_days_after_the_split_day_count(self):
        times = self.window([1, 2, 3, 4])
        split = FULL_DAY_FRAMES + 100  # training is exactly day 1
        self.assertEqual(holdout_days(times, split), ['2026-10-02', '2026-10-03', '2026-10-04'])

    def test_the_day_containing_the_split_never_counts(self):
        times = self.window([1, 2, 3])
        split = (FULL_DAY_FRAMES + 100) + 500  # training ends mid day 2
        self.assertEqual(holdout_days(times, split), ['2026-10-03'])

    def test_partial_days_are_excluded(self):
        times = self.window([1, 2]) + self.window([3], frames=FULL_DAY_FRAMES - 1)
        self.assertEqual(holdout_days(times, FULL_DAY_FRAMES + 100), ['2026-10-02'])

    def test_no_holdout_means_no_days(self):
        times = self.window([1])
        self.assertEqual(holdout_days(times, len(times)), [])


if __name__ == '__main__':
    unittest.main()
