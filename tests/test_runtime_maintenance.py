import datetime
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class TestRuntimeMaintenance(unittest.TestCase):
    def test_weekly_potential_uses_only_latest_coordinate_segment(self):
        from traceform.evolution.potential_maintenance import persist_latest_potential
        from traceform.streaming.tiered_storage import TieredStorage
        with tempfile.TemporaryDirectory() as directory:
            store = TieredStorage(Path(directory) / 'storage.sqlite')
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            for index in range(7):
                namespace = 'old' if index < 3 else 'new'
                frame = dict(timestamp_utc=(start + datetime.timedelta(seconds=index)).isoformat(),
                             state_token='A', model_version=namespace, cold_state_namespace=namespace,
                             normalizer_sha256=('1' if index < 3 else '2') * 64,
                             continuous_vector=[float(index)])
                store.append({}, frame)
            report = persist_latest_potential(store)
            self.assertTrue(report['saved'])
            self.assertEqual(report['namespace'], 'new')
            self.assertEqual(report['record_count'], 4)
            self.assertEqual(report['fit_pair_count'], 3)
            saved = json.loads(store.db.execute('SELECT payload FROM cold_potentials').fetchone()[0])
            self.assertEqual(saved['source_sha256'], report['source_sha256'])
            frame.update(timestamp_utc=(start + datetime.timedelta(seconds=1000)).isoformat())
            store.append({}, frame)
            skipped = persist_latest_potential(store)
            self.assertFalse(skipped['saved'])
            self.assertEqual(skipped['record_count'], 1)
            self.assertEqual(store.db.execute('SELECT COUNT(*) FROM cold_potentials').fetchone()[0], 1)
            store.close()

    def test_idle_physical_records_expire_without_touching_frozen_archive(self):
        project = Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            physical = root / 'physical'
            physical.mkdir()
            archive = root / 'frozen'
            archive.mkdir()
            sentinel = archive / 'physical_observations.jsonl'
            sentinel.write_text('frozen evidence')
            now = datetime.datetime.now(datetime.timezone.utc)
            old = {'timestamp_utc': (now - datetime.timedelta(hours=49)).isoformat(), 'id': 'expired'}
            fresh = {'timestamp_utc': now.isoformat(), 'id': 'retained'}
            path = physical / 'physical_observations.jsonl'
            path.write_text(json.dumps(old) + '\n' + json.dumps(fresh) + '\n')
            result = subprocess.run([sys.executable, str(project / 'scripts/maintain_edge_runtime.py'),
                '--database', str(root / 'storage.sqlite'), '--registry', str(root / 'registry'),
                '--physical-ledger-dir', str(physical)], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual([json.loads(line) for line in path.read_text().splitlines()], [fresh])
            self.assertEqual(sentinel.read_text(), 'frozen evidence')

    @unittest.skipIf(Path("/data/data/com.termux/files/usr/bin/python3").exists(), "host-only Android tool interception; device verifies real maintenance")
    def test_device_wrapper_binds_real_termux_physical_directory(self):
        import os
        project = Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            getprop = root / 'getprop'
            getprop.write_text('#!/bin/sh\necho UTC\n')
            getprop.chmod(0o755)
            python = root / 'python3'
            python.write_text('#!' + sys.executable + '\nimport json, sys\nprint(json.dumps(sys.argv[1:]))\n')
            python.chmod(0o755)
            result = subprocess.run(['bash', str(project / 'scripts/edge_maintenance_job.sh')],
                env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH']),
                capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
            arguments = json.loads(result.stdout)
            self.assertEqual(arguments[arguments.index('--physical-ledger-dir') + 1],
                             '/data/data/com.termux/files/home/.pixel_sensorium')
