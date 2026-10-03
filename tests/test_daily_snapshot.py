import datetime
import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from traceform.streaming.daily_snapshot import snapshot_if_due

ROOT = Path(__file__).resolve().parents[1]
UTC = datetime.timezone.utc


def make_database(path, rows=50, wal=False):
    connection = sqlite3.connect(path)
    if wal:
        connection.execute('PRAGMA journal_mode=WAL')
    connection.execute('create table hot (id text primary key, ts real, raw text, frame text)')
    connection.execute('create table warm (bucket integer primary key, payload text)')
    for i in range(rows):
        connection.execute('insert into hot values (?,?,?,?)', (f'i{i}', 1000.0 + i, '{"x": %d}' % i,
                                                              json.dumps({'timestamp_utc': f'2026-10-0{1 + i % 3}T00:00:00Z'})))
    connection.execute("insert into warm values (1, 'w')")
    connection.commit()
    connection.close()


class Case(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.db = self.root / 'edge.sqlite'
        self.archive = self.root / 'archive'
        make_database(self.db)

    def at(self, day, hour=3):
        return datetime.datetime(2026, 10, day, hour, 0, tzinfo=UTC)

    def run_once(self, day, hour=3, **kwargs):
        return snapshot_if_due(self.db, self.archive, self.at(day, hour), UTC, **kwargs)

    def files(self):
        return sorted(p.name for p in self.archive.glob('edge_storage_snapshot_*.sqlite'))


class TestSnapshot(Case):
    def test_first_call_writes_a_verified_complete_copy_and_a_manifest_line(self):
        report = self.run_once(3)
        self.assertEqual(report['status'], 'created')
        self.assertEqual(self.files(), ['edge_storage_snapshot_20261003T030000.sqlite'])
        copy = sqlite3.connect(self.archive / self.files()[0])
        self.assertEqual(copy.execute('select count(*) from hot').fetchone()[0], 50)
        self.assertEqual(copy.execute('select count(*) from warm').fetchone()[0], 1)
        line = json.loads((self.archive / 'MANIFEST.jsonl').read_text().splitlines()[-1])
        self.assertEqual(line['hot_rows'], 50)
        self.assertEqual(line['sha256'], hashlib.sha256((self.archive / self.files()[0]).read_bytes()).hexdigest())
        self.assertEqual(line['scope'], 'local_only_device_derived_raw_snapshot_never_exported')

    def test_one_snapshot_per_local_day(self):
        self.run_once(3, 3)
        again = self.run_once(3, 22)
        self.assertEqual(again['status'], 'already_taken_today')
        self.assertEqual(len(self.files()), 1)
        self.assertEqual(self.run_once(4, 1)['status'], 'created')
        self.assertEqual(len(self.files()), 2)

    def test_local_day_follows_the_given_zone_not_utc(self):
        zone = datetime.timezone(datetime.timedelta(hours=5, minutes=45))
        snapshot_if_due(self.db, self.archive, datetime.datetime(2026, 10, 3, 20, 0, tzinfo=UTC), zone)  # 01:45 on the 4th
        later = snapshot_if_due(self.db, self.archive, datetime.datetime(2026, 10, 4, 1, 0, tzinfo=UTC), zone)  # 06:45 on the 4th
        self.assertEqual(later['status'], 'already_taken_today')

    def test_snapshot_is_one_self_contained_file_even_from_a_wal_mode_store(self):
        wal_db = self.root / 'wal.sqlite'
        make_database(wal_db, wal=True)
        live = sqlite3.connect(wal_db)  # a writer is attached, as the daemon is
        live.execute("insert into hot values ('late', 5000.0, '{}', '{\"timestamp_utc\": \"2026-10-03T00:00:00Z\"}')")
        live.commit()
        report = snapshot_if_due(wal_db, self.archive, self.at(3), UTC)
        live.close()
        self.assertEqual(report['status'], 'created')
        self.assertEqual(report['hot_rows'], 51)
        self.assertEqual(sorted(p.name for p in self.archive.iterdir()),
                         ['MANIFEST.jsonl', 'edge_storage_snapshot_20261003T030000.sqlite'])
        copy = sqlite3.connect(self.archive / self.files()[0])
        self.assertEqual(copy.execute('PRAGMA journal_mode').fetchone()[0], 'delete')
        copy.close()
        self.assertEqual(sorted(p.name for p in self.archive.iterdir()),
                         ['MANIFEST.jsonl', 'edge_storage_snapshot_20261003T030000.sqlite'])

    def test_stale_sidecars_of_an_earlier_temporary_file_cannot_leak_into_a_new_snapshot(self):
        self.archive.mkdir()
        (self.archive / '.snapshot.tmp-wal').write_bytes(b'stale write-ahead log')
        (self.archive / '.snapshot.tmp-shm').write_bytes(b'stale shared memory')
        report = self.run_once(3)
        self.assertEqual(report['status'], 'created')
        self.assertEqual(sorted(p.name for p in self.archive.iterdir()),
                         ['MANIFEST.jsonl', 'edge_storage_snapshot_20261003T030000.sqlite'])
        copy = sqlite3.connect(self.archive / self.files()[0])
        self.assertEqual(copy.execute('PRAGMA integrity_check').fetchone()[0], 'ok')

    def test_source_database_is_not_modified(self):
        before = hashlib.sha256(self.db.read_bytes()).hexdigest()
        self.run_once(3)
        self.assertEqual(hashlib.sha256(self.db.read_bytes()).hexdigest(), before)

    def test_snapshots_older_than_the_retention_are_pruned_and_other_files_kept(self):
        self.archive.mkdir()
        for day in (1, 2, 3):
            (self.archive / f'edge_storage_snapshot_202609{25 + day}T030000.sqlite').write_bytes(b'old')
        (self.archive / 'notes.txt').write_text('keep me')
        (self.archive / 'edge_storage_snapshot_garbage.sqlite').write_bytes(b'not matching the pattern')
        report = self.run_once(10, keep_days=14)
        self.assertEqual(report['pruned'], [])
        report = self.run_once(11, keep_days=14)
        self.assertEqual(report['pruned'], ['edge_storage_snapshot_20260926T030000.sqlite'])
        report = self.run_once(30, keep_days=14)
        self.assertEqual(len(report['pruned']), 4)
        self.assertEqual(self.files(), ['edge_storage_snapshot_20261030T030000.sqlite', 'edge_storage_snapshot_garbage.sqlite'])
        self.assertTrue((self.archive / 'notes.txt').exists())

    def test_the_newest_snapshot_is_never_pruned_even_when_no_new_one_can_be_taken(self):
        self.run_once(3)
        report = self.run_once(20, keep_days=1, free_bytes=lambda path: 10)
        self.assertEqual(report['status'], 'skipped_low_disk_space')
        self.assertEqual(report['pruned'], [])
        self.assertEqual(self.files(), ['edge_storage_snapshot_20261003T030000.sqlite'])
        report = self.run_once(21, keep_days=1)
        self.assertEqual(report['status'], 'created')
        self.assertEqual(self.files(), ['edge_storage_snapshot_20261021T030000.sqlite'])

    def test_low_disk_space_skips_without_touching_anything(self):
        report = self.run_once(3, free_bytes=lambda path: 10)
        self.assertEqual(report['status'], 'skipped_low_disk_space')
        self.assertEqual(self.files(), [])

    def test_a_failure_leaves_no_partial_file_and_does_not_count_as_taken(self):
        broken = self.root / 'missing.sqlite'
        with self.assertRaises(Exception):
            snapshot_if_due(broken, self.archive, self.at(3), UTC)
        self.assertEqual(sorted(p.name for p in self.archive.glob('*')) if self.archive.exists() else [], [])
        self.assertEqual(self.run_once(3)['status'], 'created')

    def test_a_copy_that_fails_verification_is_discarded(self):
        report = self.run_once(3, verify=lambda path, rows: False)
        self.assertEqual(report['status'], 'failed_verification')
        self.assertEqual(self.files(), [])
        self.assertFalse((self.archive / 'MANIFEST.jsonl').exists())

    def test_empty_hot_tier_is_not_snapshotted(self):
        empty = self.root / 'empty.sqlite'
        make_database(empty, rows=0)
        report = snapshot_if_due(empty, self.archive, self.at(3), UTC)
        self.assertEqual(report['status'], 'skipped_empty_hot_tier')


class TestWiring(Case):
    def test_maintenance_cli_takes_the_daily_snapshot_when_asked(self):
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/maintain_edge_runtime.py'),
                                 '--database', str(self.db), '--registry', str(self.root / 'registry'),
                                 '--snapshot-dir', str(self.archive)], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(report['daily_snapshot']['status'], 'created')
        self.assertEqual(len(self.files()), 1)
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/maintain_edge_runtime.py'),
                                 '--database', str(self.db), '--registry', str(self.root / 'registry'),
                                 '--snapshot-dir', str(self.archive)], capture_output=True, text=True, timeout=60)
        self.assertEqual(json.loads(result.stdout.strip().splitlines()[-1])['daily_snapshot']['status'], 'already_taken_today')

    def test_without_the_option_nothing_is_archived(self):
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/maintain_edge_runtime.py'),
                                 '--database', str(self.db), '--registry', str(self.root / 'registry')],
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('daily_snapshot', json.loads(result.stdout.strip().splitlines()[-1]))
        self.assertFalse(self.archive.exists())

    def test_device_wrapper_passes_the_archive_directory(self):
        text = (ROOT / 'scripts/edge_maintenance_job.sh').read_text()
        self.assertIn('--snapshot-dir /data/data/com.termux/files/home/traceform_archive', text)


if __name__ == '__main__':
    unittest.main()
