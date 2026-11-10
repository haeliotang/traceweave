# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import unittest
from contextlib import closing
import datetime
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile

from traceform.evolution.model_registry import ModelRegistry

from traceform.forecasting.continuous_sandbox import predict_latest_segment
from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode
from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.provenance import content_digest


class TestContinuousSandbox(unittest.TestCase):
    def test_worker_reads_only_trigger_snapshot_and_writes_bounded_report(self):
        records, bundle = self.fixture()
        project = Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            registry = ModelRegistry(root / 'registry')
            version = registry.register(bundle['manifold'], bundle['normalizer'], {'accepted': True})
            database_path = root / 'hot.sqlite'
            with closing(sqlite3.connect(database_path)) as database:
                database.execute('CREATE TABLE hot (ts REAL, frame TEXT)')
                for record in records + [dict(records[-1], timestamp_utc='2026-10-01T00:00:04Z', model_version='future')]:
                    instant = datetime.datetime.fromisoformat(record['timestamp_utc'].replace('Z', '+00:00'))
                    database.execute('INSERT INTO hot VALUES (?, ?)', (instant.timestamp(), json.dumps(record)))
                database.commit()
            output = root / 'prediction.json'
            command = [sys.executable, str(project / 'scripts/predict_continuous_edge.py'),
                       '--database', str(database_path), '--model-registry', str(registry.root),
                       '--registry-version', version, '--through-timestamp', records[-1]['timestamp_utc'],
                       '--output-file', str(output), '--rollouts', '2', '--horizon-steps', '3']
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(output.read_text())
            self.assertEqual(report['record_count'], 4)
            self.assertEqual(report['initial_timestamp_utc'], records[-1]['timestamp_utc'])
            self.assertEqual(report['registry_version'], version)
            self.assertLess(output.stat().st_size, 10000)
            self.assertNotIn('continuous_vector', output.read_text())
            command[command.index('--through-timestamp') + 1] = '2026-10-01T00:00:05Z'
            failed = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(failed.returncode, 1)
            self.assertEqual(json.loads(output.read_text())['event_type'], 'CONTINUOUS_SANDBOX_FAILED')

    def fixture(self):
        manifold = DynamicalEnergyManifold()
        manifold.nodes = [ManifoldNode(0, (0, 0), 'A', [0] * 128, [1] * 128),
                          ManifoldNode(1, (0, 1), 'B', [5] * 128, [1] * 128)]
        normalizer = MultimodalNormalizer()
        normalizer.fit_discovery_window([{}] * 3)
        bundle = {'manifold': manifold.to_dict(), 'normalizer': normalizer.to_dict()}
        model_hash, normalizer_hash = content_digest(bundle['manifold']), content_digest(bundle['normalizer'])
        version = content_digest({'manifold': model_hash, 'normalizer': normalizer_hash})
        records = [{'timestamp_utc': f'2026-10-01T00:00:0{index}Z', 'state_token': 'A',
                    'continuous_vector': [index] * 128, 'continuous_velocity': [1] * 128,
                    'model_version': version, 'model_sha256': model_hash,
                    'normalizer_sha256': normalizer_hash, 'boot_id': 'fixture'} for index in range(4)]
        return records, bundle

    def test_latest_segment_predicts_in_bound_phase_coordinates(self):
        records, bundle = self.fixture()
        result = predict_latest_segment(records, bundle, horizon_steps=3, num_rollouts=2)
        self.assertEqual(result['record_count'], 4)
        self.assertEqual(result['fit_pair_count'], 3)
        self.assertEqual(result['branch']['terminal_state_distribution'], {'B': 1})
        self.assertEqual(result['branch']['dynamics_evidence']['quantization'], 'phase_energy')
        self.assertEqual(result['initial_timestamp_utc'], records[-1]['timestamp_utc'])
        self.assertNotIn('continuous_vector', result)

    def test_foreign_bundle_and_broken_latest_segment_are_rejected(self):
        records, bundle = self.fixture()
        bundle['manifold']['nodes'][0]['center_x'][0] = 9
        with self.assertRaisesRegex(ValueError, 'binding'):
            predict_latest_segment(records, bundle, num_rollouts=1)
        records, bundle = self.fixture()
        records[-1]['continuous_from_previous'] = False
        with self.assertRaisesRegex(ValueError, 'two continuous pairs'):
            predict_latest_segment(records, bundle, num_rollouts=1)
