# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import datetime
from pathlib import Path
import tempfile
import unittest

from traceform.streaming.tiered_storage import TieredStorage


class TestTieredStorage(unittest.TestCase):
    def test_cold_potential_is_source_bound_and_survives_hot_expiry(self):
        import gzip
        import json
        from traceform.dynamics.continuous import ContinuousDynamics, evaluate_graph_potential, graph_potential_curvature
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = TieredStorage(root / 'edge.sqlite')
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            records = []
            for index in range(4):
                frame = self.frame(start + datetime.timedelta(seconds=index))
                frame.update(continuous_vector=[float(index)], normalizer_sha256='1' * 64)
                store.append({}, frame)
                records.append(frame)
            model = ContinuousDynamics(bandwidth=0.7).fit(records)
            potential = model.infer_potential()
            reference = model.potential_at([0.4], potential)
            self.assertEqual(potential['bandwidth'], 0.7)
            store.save_cold_potential('v1', potential, '2' * 64)
            for key in ('bandwidth', 'mobility'):
                for invalid in (None, 0, -1, float('nan'), float('inf')):
                    with self.assertRaisesRegex(ValueError, 'bounded finite'):
                        store.save_cold_potential('v1', dict(potential, **{key: invalid}), '2' * 64)
            for invalid in (1e-300, 1e300):
                with self.assertRaisesRegex(ValueError, 'bounded finite'):
                    store.save_cold_potential('v1', dict(potential, bandwidth=invalid), '2' * 64)
            wrong = dict(potential, coordinate_provenance={'normalizer_sha256': '3' * 64})
            with self.assertRaisesRegex(ValueError, 'normalizer'):
                store.save_cold_potential('v1', wrong, '2' * 64)
            store.compact(start + datetime.timedelta(days=31))
            store.close()
            store = TieredStorage(root / 'edge.sqlite')
            store.export_cold_bundle(root / 'cold.gz', [])
            bundle = json.loads(gzip.decompress((root / 'cold.gz').read_bytes()))
            retained = bundle['state_spaces'][0]['drift_projected_potential']
            self.assertEqual(retained['potential'], potential)
            self.assertEqual(retained['source_sha256'], '2' * 64)
            self.assertEqual(graph_potential_curvature([0.4], retained['potential']),
                             graph_potential_curvature([0.4], potential))
            reconstructed = evaluate_graph_potential([0.4], retained['potential'])
            for key in ('energy', 'gradient', 'conservative_drift'):
                self.assertEqual(reconstructed[key], reference[key])
            legacy = dict(retained['potential'])
            del legacy['bandwidth']
            with self.assertRaisesRegex(ValueError, 'archived potential'):
                evaluate_graph_potential([0.4], legacy)
            self.assertEqual(store.counts()['hot'], 0)
            store.close()

    def frame(self, instant, token="A", continuous=True):
        return {"timestamp_utc": instant.isoformat(), "state_token": token, "model_version": "v1",
                "continuous_from_previous": continuous, "energy_distance": 2.0}

    def test_retention_restart_and_replay_watermark(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "edge.sqlite"
            store = TieredStorage(path)
            start = datetime.datetime(2026, 8, 1, tzinfo=datetime.timezone.utc)
            first = self.frame(start)
            second = self.frame(start + datetime.timedelta(seconds=1), "B")
            self.assertTrue(store.append({}, first))
            self.assertTrue(store.append({}, second))
            self.assertFalse(store.append({}, second))
            self.assertEqual(store.counts(), {"hot": 2, "warm": 2, "cold": 1})
            store.compact(start + datetime.timedelta(days=31))
            self.assertEqual(store.counts(), {"hot": 0, "warm": 0, "cold": 1})
            store.close()
            store = TieredStorage(path)
            self.assertFalse(store.append({}, first))
            self.assertEqual(store.counts()["cold"], 1)
            store.close()

    def test_atomic_failure_and_discontinuity(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TieredStorage(Path(directory) / "edge.sqlite")
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            store.append({}, self.frame(start))
            with self.assertRaises(ValueError):
                store.append({"bad": float("nan")}, self.frame(start + datetime.timedelta(seconds=1)))
            self.assertEqual(store.counts()["hot"], 1)
            store.append({}, self.frame(start + datetime.timedelta(seconds=2), "B", False))
            self.assertEqual(store.counts()["cold"], 0)
            self.assertEqual(sum(row["frame_count"] for row in store.warm_records()), 2)
            store.close()

    def test_cold_accumulates_explicit_lineage_without_cross_version_edges(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TieredStorage(Path(directory) / 'edge.sqlite')
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            for version in range(10):
                for index, token in enumerate(['A', 'B']):
                    frame = self.frame(start + datetime.timedelta(seconds=version * 2 + index), token)
                    frame.update(model_version=f'v{version}', cold_state_namespace='lineage')
                    store.append({}, frame)
            self.assertEqual(store.db.execute('SELECT model,source,target,transitions FROM cold').fetchall(),
                             [('lineage', 'A', 'B', 10)])
            self.assertEqual(len({row['model_version'] for row in store.hot_records()}), 10)
            store.close()

    def test_cold_limits_reject_growth_without_partial_hot_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TieredStorage(Path(directory) / 'edge.sqlite')
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            for index in range(4):
                frame = self.frame(start + datetime.timedelta(seconds=index))
                frame['model_version'] = f'v{index}'
                store.append({}, frame)
            frame = self.frame(start + datetime.timedelta(seconds=4))
            frame['model_version'] = 'v4'
            with self.assertRaisesRegex(ValueError, 'namespace'):
                store.append({}, frame)
            self.assertEqual(store.counts()['hot'], 4)
            store.close()

    def test_cold_state_limit_rolls_back_and_namespace_changes_break_edges(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TieredStorage(Path(directory) / 'edge.sqlite')
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            for index in range(16):
                store.append({}, self.frame(start + datetime.timedelta(seconds=index), str(index)))
            with self.assertRaisesRegex(ValueError, 'state limit'):
                store.append({}, self.frame(start + datetime.timedelta(seconds=16), 'extra'))
            self.assertEqual(store.counts()['hot'], 16)
            frame = self.frame(start + datetime.timedelta(seconds=17), 'B')
            frame['cold_state_namespace'] = 'new-lineage'
            before = store.counts()['cold']
            store.append({}, frame)
            self.assertEqual(store.counts()['cold'], before)
            store.close()

    def test_cold_export_is_lossless_and_preserves_previous_file_on_size_failure(self):
        import gzip
        import json
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = TieredStorage(root / 'edge.sqlite')
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            store.append({}, self.frame(start))
            store.append({}, self.frame(start + datetime.timedelta(seconds=1), 'B'))
            path = root / 'cold.json.gz'
            receipt = store.export_cold_bundle(path, [])
            bundle = json.loads(gzip.decompress(path.read_bytes()))
            self.assertEqual(bundle['state_spaces'][0]['tokens'], ['A', 'B'])
            self.assertEqual(bundle['state_spaces'][0]['transition_matrix'], [[0, 1], [0, 0]])
            self.assertLess(receipt['bytes'], 500000)
            original = path.read_bytes()
            import random
            generator = random.Random(1)
            model = {'payload': ''.join(generator.choice('abcdef0123456789') for _ in range(1100000))}
            with self.assertRaisesRegex(ValueError, '500000'):
                store.export_cold_bundle(path, [model])
            self.assertEqual(path.read_bytes(), original)
            store.close()

    def test_full_capacity_cold_bundle_preserves_four_128d_structural_models(self):
        import gzip
        import json
        import random
        from traceform.dynamics.continuous import ContinuousDynamics
        from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode
        from traceform.representation.normalizer import MultimodalNormalizer
        from traceform.representation.provenance import content_digest
        rng = random.Random(20261001)
        normalizer = MultimodalNormalizer()
        normalizer.fit_discovery_window([{}] * 3)
        parameters = normalizer.to_dict()
        normalizer_digest = content_digest(parameters)
        bundles, expected_potentials, expected_sources, expected_counts = [], {}, {}, {}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = TieredStorage(root / 'edge.sqlite')
            try:
                for version in range(4):
                    model = DynamicalEnergyManifold()
                    model.nodes = [ManifoldNode(index, divmod(index, 4), f'M{index + 1:02d}',
                        [rng.uniform(-10, 10) for _ in range(128)],
                        [rng.uniform(-1, 1) for _ in range(128)]) for index in range(16)]
                    manifold = model.to_dict()
                    namespace = content_digest(manifold)
                    for node in model.nodes:
                        with store.db:
                            store._register_cold_state(namespace, node.state_token, normalizer_digest)
                    counts = [[rng.randrange(1, 10 ** 12) for _ in range(16)] for _ in range(16)]
                    with store.db:
                        for source, row in enumerate(counts):
                            for target, count in enumerate(row):
                                store.db.execute('INSERT INTO cold VALUES (?,?,?,?)',
                                    (namespace, model.nodes[source].state_token, model.nodes[target].state_token, count))
                    expected_counts[namespace] = counts
                    dynamics = ContinuousDynamics(bandwidth=0.7)
                    dynamics.dimension, dynamics.model_version = 128, namespace
                    dynamics.coordinate_provenance = {'normalizer_sha256': normalizer_digest}
                    dynamics.anchors = [{'center': node.center_x, 'drift': node.center_v} for node in model.nodes]
                    potential = dynamics.infer_potential()
                    source_digest = content_digest(dynamics.to_dict())
                    store.save_cold_potential(namespace, potential, source_digest)
                    expected_sources[namespace] = source_digest
                    expected_potentials[namespace] = potential
                    graphs = {}
                    for phase in ('training', 'holdout', 'full_stream'):
                        graphs[phase] = {}
                        for label in ('baseline', 'candidate'):
                            rows = [[rng.random() for _ in range(16)] for _ in range(16)]
                            graphs[phase][label] = {'transition_matrix': {
                                model.nodes[index].state_token: {model.nodes[target].state_token: value / sum(row)
                                    for target, value in enumerate(row)} for index, row in enumerate(rows)}}
                    bundle = {'manifold': manifold, 'normalizer': parameters,
                              'validation': {'accepted': True, 'synthetic': True, 'incremental_transition_structure': graphs}}
                    bundle['version'] = content_digest(bundle)
                    bundles.append(bundle)
                receipt = store.export_cold_bundle(root / 'cold.gz', bundles)
                self.cold_capacity_bytes = receipt['bytes']
                self.assertLess(receipt['bytes'], 500000)
                decoded = json.loads(gzip.decompress((root / 'cold.gz').read_bytes()))
                self.assertEqual(decoded['model_bundles'], bundles)
                self.assertEqual(len(decoded['state_spaces']), 4)
                for space in decoded['state_spaces']:
                    self.assertEqual(len(space['tokens']), 16)
                    self.assertEqual(space['drift_projected_potential']['potential'], expected_potentials[space['namespace']])
                    self.assertEqual(space['normalizer_sha256'], normalizer_digest)
                    self.assertEqual(space['transition_matrix'], expected_counts[space['namespace']])
                    self.assertEqual(space['drift_projected_potential']['source_sha256'], expected_sources[space['namespace']])
            finally:
                store.close()

    def test_explicit_lineage_cannot_mix_normalizer_digests(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TieredStorage(Path(directory) / 'edge.sqlite')
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            frame = self.frame(start)
            frame.update(cold_state_namespace='lineage', normalizer_sha256='1' * 64)
            store.append({}, frame)
            second = self.frame(start + datetime.timedelta(seconds=1), 'B')
            second.update(cold_state_namespace='lineage', model_version='v2', normalizer_sha256='2' * 64)
            with self.assertRaisesRegex(ValueError, 'normalizer'):
                store.append({}, second)
            self.assertEqual(store.counts()['hot'], 1)
            store.close()

    def test_oversized_legacy_namespace_is_rejected_without_deleting_counts(self):
        import sqlite3
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'edge.sqlite'
            legacy = sqlite3.connect(path)
            legacy.execute('CREATE TABLE cold (model TEXT, source TEXT, target TEXT, transitions INTEGER, PRIMARY KEY(model,source,target))')
            legacy.execute('INSERT INTO cold VALUES (?,?,?,?)', ('x' * 65, 'A', 'B', 7))
            legacy.commit()
            legacy.close()
            with self.assertRaisesRegex(ValueError, 'namespace'):
                TieredStorage(path)
            preserved = sqlite3.connect(path)
            self.assertEqual(preserved.execute('SELECT transitions FROM cold').fetchone()[0], 7)
            preserved.close()

    def test_warm_trajectory_keeps_order_and_latest_sample_with_bucket_breaks(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TieredStorage(Path(directory) / 'edge.sqlite')
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            for second, token, continuous in [(0, 'A', False), (10, 'B', True), (31, 'C', True), (40, 'D', False), (61, 'A', True)]:
                store.append({}, self.frame(start + datetime.timedelta(seconds=second), token, continuous))
            rows = list(store.warm_trajectory_records())
            self.assertEqual([row['state_token'] for row in rows], ['B', 'D', 'A'])
            self.assertEqual([row['bucket_frame_count'] for row in rows], [2, 2, 1])
            self.assertEqual([row['continuous_from_previous'] for row in rows], [False, False, True])
            self.assertEqual(rows[0]['timestamp_utc'], (start + datetime.timedelta(seconds=10)).isoformat())
            store.compact(start + datetime.timedelta(days=3))
            self.assertEqual(store.counts()['hot'], 0)
            self.assertEqual(len(list(store.warm_trajectory_records())), 3)
            store.compact(start + datetime.timedelta(days=31))
            self.assertEqual(list(store.warm_trajectory_records()), [])
            store.close()

    def test_warm_migration_only_recovers_retained_hot_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'edge.sqlite'
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            store = TieredStorage(path)
            store.append({}, self.frame(start))
            store.append({}, self.frame(start + datetime.timedelta(seconds=10), 'B'))
            with store.db:
                store.db.execute('DELETE FROM warm_trajectory')
                store.db.execute("DELETE FROM metadata WHERE key='warm_trajectory_migration'")
                store.db.execute("INSERT INTO warm VALUES (?, 'v1', 'C', 1, 1)", (int(start.timestamp()) - 86400,))
            store.close()
            for _ in range(2):
                restored = TieredStorage(path)
                rows = list(restored.warm_trajectory_records())
                self.assertEqual(len(rows), 1)
                self.assertEqual(rows[0]['state_token'], 'B')
                self.assertEqual(rows[0]['bucket_frame_count'], 2)
                restored.close()

    def test_warm_model_boot_source_and_missing_bucket_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TieredStorage(Path(directory) / 'edge.sqlite')
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            for second, token, model, boot, mode in [
                (0, 'A', 'v1', 'one', 'live'), (10, 'B', 'v2', 'two', 'live'),
                (35, 'C', 'v2', 'two', 'live'), (70, 'A', 'v2', 'three', 'live'),
                (100, 'D', 'v2', 'three', 'replay'), (161, 'E', 'v2', 'three', 'replay')]:
                frame = self.frame(start + datetime.timedelta(seconds=second), token)
                frame.update(model_version=model, boot_id=boot, source_mode=mode)
                store.append({}, frame)
            rows = list(store.warm_trajectory_records())
            self.assertEqual([row['continuous_from_previous'] for row in rows], [False, True, False, False, False])
            self.assertEqual(rows[-1]['missing_buckets_before'], 1)
            self.assertEqual(rows[0]['model_version'], 'v2')
            store.close()

    def test_failed_warm_write_rolls_back_all_tiers_and_watermark(self):
        import sqlite3
        with tempfile.TemporaryDirectory() as directory:
            store = TieredStorage(Path(directory) / 'edge.sqlite')
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            store.append({}, self.frame(start))
            store.db.execute("CREATE TRIGGER reject_warm BEFORE INSERT ON warm_trajectory BEGIN SELECT RAISE(ABORT, 'warm unavailable'); END")
            second = self.frame(start + datetime.timedelta(seconds=31), 'B')
            with self.assertRaises(sqlite3.IntegrityError):
                store.append({}, second)
            self.assertEqual(store.counts(), {'hot': 1, 'warm': 1, 'cold': 0})
            self.assertEqual(len(list(store.warm_trajectory_records())), 1)
            store.db.execute('DROP TRIGGER reject_warm')
            self.assertTrue(store.append({}, second))
            self.assertEqual(len(list(store.warm_trajectory_records())), 2)
            store.close()

    def test_cli_exports_ordered_warm_samples_after_hot_expiry(self):
        import json
        import subprocess
        import sys
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'edge.sqlite'
            store = TieredStorage(path)
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            for second, token in [(0, 'A'), (10, 'B'), (31, 'C')]:
                store.append({}, self.frame(start + datetime.timedelta(seconds=second), token))
            store.close()
            output = root / 'trajectory.jsonl'
            project = Path(__file__).resolve().parent.parent
            result = subprocess.run([sys.executable, str(project / 'scripts/compact_edge_storage.py'),
                '--database', str(path), '--now', (start + datetime.timedelta(days=3)).isoformat(),
                '--export-trajectory', str(output)], capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout)['counts']['hot'], 0)
            self.assertEqual([json.loads(line)['state_token'] for line in output.read_text().splitlines()], ['B', 'C'])
