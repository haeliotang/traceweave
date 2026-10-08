from contextlib import closing
import datetime
import io
import json
from pathlib import Path
import sqlite3
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from scripts.evaluate_prospective_forecast import extract_archive, main, readiness, score_archived
from scripts.freeze_prospective_forecast import freeze


class TestProspectiveEvaluation(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.now = datetime.datetime(2026, 10, 8, 14, 20, tzinfo=datetime.timezone.utc)
        self.source = self.root / 'source.sqlite'
        with closing(sqlite3.connect(self.source)) as database, database:
            database.execute('CREATE TABLE hot (id TEXT PRIMARY KEY,ts REAL,frame TEXT)')
            for index in range(64):
                instant = self.now - datetime.timedelta(seconds=(64 - index) * 30)
                database.execute('INSERT INTO hot VALUES (?,?,?)',
                                 (str(index), instant.timestamp(), json.dumps(self.frame(instant, index))))
        self.archive = self.root / 'code.tar.gz'
        # Child execution must import these frozen modules, never the working checkout.
        with tarfile.open(self.archive, 'w:gz') as archive:
            modules = {'scripts/validate_continuous_holdout.py': '''def validate(train,holdout,**kw):
    assert kw==dict(bandwidth=1,dimension=128,include_covariance=True)
    assert len(train)==64 and len(holdout)==9200
    return {'archived_continuous':True}
''', 'traceform/forecasting/evaluation.py': '''def evaluate_forecasts(records,split,**kw):
    assert kw==dict(timezone_name='Asia/Kathmandu',max_gap_seconds=300)
    assert len(records)==9264
    return {'archived_discrete':True}
'''}
            for name, text in modules.items():
                payload = text.encode()
                info = tarfile.TarInfo(name); info.size = len(payload)
                archive.addfile(info, io.BytesIO(payload))
        self.frozen = self.root / 'frozen'
        self.plan = freeze(self.source, self.archive, self.frozen, self.now)
        self.start = datetime.datetime.fromisoformat(self.plan['holdout_start_utc'])
        self.end = datetime.datetime.fromisoformat(self.plan['holdout_end_utc'])

    def frame(self, instant, index):
        return {'timestamp_utc': instant.isoformat(), 'continuous_vector': [float(index % 2)] * 128,
                'state_token': 'A' if index % 2 else 'B', 'model_version': 'fixture',
                'model_sha256': '1' * 64, 'normalizer_sha256': '2' * 64,
                'boot_id': 'boot', 'continuity_epoch': 1, 'source_mode': 'live_follow',
                'continuous_from_previous': True}

    def holdout(self):
        with closing(sqlite3.connect(self.source)) as database, database:
            for index in range(9200):
                instant = self.start + datetime.timedelta(seconds=index * 86400 / 2300)
                database.execute('INSERT INTO hot VALUES (?,?,?)',
                                 ('h' + str(index), instant.timestamp(), json.dumps(self.frame(instant, index))))

    def test_pending_and_missing_declared_days_never_score(self):
        report, _, holdout, _ = readiness(self.frozen, [self.source], self.now)
        self.assertEqual(report['status'], 'pending')
        self.assertFalse(holdout)
        report, _, _, _ = readiness(self.frozen, [self.source], self.end)
        self.assertEqual(report['status'], 'blocked')
        self.assertEqual(report['blockers'], ['missing_predeclared_day_coverage'])
        self.assertNotIn('scores', report)

    def test_exact_window_dedup_and_archived_execution_preserve_sources(self):
        self.holdout()
        before = self.source.read_bytes()
        frozen_before = {path.name: path.read_bytes() for path in self.frozen.iterdir()}
        report, train, holdout, plan = readiness(self.frozen, [self.source, self.source], self.end)
        self.assertEqual(report['status'], 'ready')
        self.assertEqual(report['holdout_records'], 9200)
        self.assertTrue(all(count == 2300 for count in report['frames_per_predeclared_utc_day'].values()))
        scores = score_archived(self.frozen, train, holdout, plan)
        self.assertTrue(scores['continuous']['archived_continuous'])
        self.assertTrue(scores['discrete']['archived_discrete'])
        self.assertEqual(self.source.read_bytes(), before)
        self.assertEqual({path.name: path.read_bytes() for path in self.frozen.iterdir()}, frozen_before)
        closing_time = self.end
        class ClosingTime(datetime.datetime):
            @classmethod
            def now(cls, tz=None):
                return closing_time
        receipt = self.root / 'failure.json'
        arguments = ['evaluate', '--frozen-directory', str(self.frozen), '--database', str(self.source),
                     '--output-file', str(receipt)]
        failure = subprocess.CalledProcessError(1, ['archived-worker'], stderr=b'controlled covariance failure')
        with patch('sys.argv', arguments), patch('scripts.evaluate_prospective_forecast.datetime.datetime', ClosingTime), \
                patch('scripts.evaluate_prospective_forecast.score_archived', side_effect=failure):
            self.assertEqual(main(), 1)
        failed = json.loads(receipt.read_text())
        self.assertEqual(failed['status'], 'execution_failed')
        self.assertEqual(failed['worker_stderr'], 'controlled covariance failure')
        self.assertNotIn('scores', failed)

    def test_training_and_archive_tampering_rejected(self):
        path = self.frozen / 'training.sqlite'
        with closing(sqlite3.connect(path)) as database, database:
            database.execute("UPDATE hot SET frame='{}' WHERE id='63'")
        with self.assertRaisesRegex(ValueError, 'training digest'):
            readiness(self.frozen, [self.source], self.now)
        (self.frozen / 'code.tar.gz').write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'archive digest'):
            readiness(self.frozen, [self.source], self.now)

    def test_coordinate_switch_blocks_even_before_closing(self):
        instant = self.start + datetime.timedelta(seconds=30)
        frame = dict(self.frame(instant, 0), model_version='other')
        with closing(sqlite3.connect(self.source)) as database, database:
            database.execute('INSERT INTO hot VALUES (?,?,?)', ('changed', instant.timestamp(), json.dumps(frame)))
        report, _, _, _ = readiness(self.frozen, [self.source], self.start + datetime.timedelta(hours=1))
        self.assertEqual(report['status'], 'blocked')
        self.assertIn('coordinate_binding_changed', report['blockers'])

    def test_conflicting_identity_is_not_silently_deduplicated(self):
        instant = self.start + datetime.timedelta(seconds=30)
        with closing(sqlite3.connect(self.source)) as database, database:
            database.execute('INSERT INTO hot VALUES (?,?,?)', ('h', instant.timestamp(), json.dumps(self.frame(instant, 0))))
        other = self.root / 'other.sqlite'; other.write_bytes(self.source.read_bytes())
        with closing(sqlite3.connect(other)) as database, database:
            database.execute('UPDATE hot SET frame=? WHERE id=?', (json.dumps(self.frame(instant, 1)), 'h'))
        with self.assertRaisesRegex(ValueError, 'conflicting retained'):
            readiness(self.frozen, [self.source, other], self.end)

    def test_distinct_ids_at_same_timestamp_and_incomplete_vectors_block(self):
        instant = self.start + datetime.timedelta(seconds=30)
        with closing(sqlite3.connect(self.source)) as database, database:
            for ident in ('one', 'two'):
                frame = dict(self.frame(instant, 0), continuous_vector=[0.0])
                database.execute('INSERT INTO hot VALUES (?,?,?)', (ident, instant.timestamp(), json.dumps(frame)))
        report, _, _, _ = readiness(self.frozen, [self.source], self.end)
        self.assertIn('duplicate_or_nonincreasing_timestamp', report['blockers'])
        self.assertIn('incomplete_holdout_coordinates', report['blockers'])

    def test_archive_links_and_parent_paths_are_rejected_before_extraction(self):
        for name, kind in (('scripts/../../outside.py', tarfile.REGTYPE), ('scripts/link', tarfile.SYMTYPE)):
            with self.subTest(name=name):
                archive_path = self.root / 'unsafe.tar'
                with tarfile.open(archive_path, 'w') as archive:
                    member = tarfile.TarInfo(name); member.type = kind; member.linkname = '/tmp/outside'
                    archive.addfile(member)
                with tarfile.open(archive_path) as archive, self.assertRaisesRegex(ValueError, 'unsafe frozen'):
                    extract_archive(archive, self.root / 'extract')

    def test_archived_bytecode_is_excluded_from_source_execution(self):
        archive_path = self.root / 'cached.tar'
        with tarfile.open(archive_path, 'w') as archive:
            for name in ('scripts/frozen.py', 'scripts/__pycache__/frozen.cpython-314.pyc'):
                payload = b'archived source or stale cache'
                member = tarfile.TarInfo(name); member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))
        destination = self.root / 'extract'
        with tarfile.open(archive_path) as archive:
            extract_archive(archive, destination)
        self.assertTrue((destination / 'scripts/frozen.py').exists())
        self.assertFalse((destination / 'scripts/__pycache__').exists())

    def test_frozen_macos_appledouble_sidecars_are_ignored_without_execution(self):
        archive_path = self.root / 'macos.tar'
        with tarfile.open(archive_path, 'w') as archive:
            for name in ('._scripts', '._traceform', 'scripts/._frozen.py'):
                payload = b'\x00\x05\x16\x07' + b'ignored AppleDouble metadata'
                member = tarfile.TarInfo(name); member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))
        destination = self.root / 'extract'
        with tarfile.open(archive_path) as archive:
            extract_archive(archive, destination)
        self.assertFalse(destination.exists())
        with tarfile.open(archive_path, 'w') as archive:
            payload = b'not AppleDouble metadata'
            member = tarfile.TarInfo('._scripts'); member.size = len(payload)
            archive.addfile(member, io.BytesIO(payload))
        with tarfile.open(archive_path) as archive, self.assertRaisesRegex(ValueError, 'invalid AppleDouble'):
            extract_archive(archive, destination)
