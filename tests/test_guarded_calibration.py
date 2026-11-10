# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import unittest

from traceform.evolution.guarded_calibration import calibrate_candidate, assess_topology, audit_observations


class TestGuardedCalibration(unittest.TestCase):
    def test_scoring_resets_history_at_boot_and_explicit_segment_boundaries(self):
        from unittest.mock import patch
        from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode
        from traceform.representation.normalizer import MultimodalNormalizer
        normalizer = MultimodalNormalizer()
        normalizer.fit_discovery_window([{}] * 3)
        model = DynamicalEnergyManifold(grid_height=1, grid_width=1)
        model.nodes = [ManifoldNode(0, (0, 0), 'A', [0], [0])]
        for boundary in ({'boot_id': 'new'}, {'continuous_from_previous': False},
                         {'session_id': 'new'}, {'normalizer_sha256': 'new'}, {'model_sha256': 'new'}):
            observations = [{'timestamp_utc': f'2026-10-01T00:00:0{index}Z', 'boot_id': 'old'}
                            for index in range(3)]
            observations[2].update(boundary)
            seen = []
            def quantize(instance, observation, normalizer, dt_seconds):
                seen.append(instance.last_velocity)
                instance.last_obs_vector, instance.last_velocity = [1], [2]
                return 'A', (0, 0), 0
            with patch.object(DynamicalEnergyManifold, 'quantize', quantize), \
                 patch.object(MultimodalNormalizer, 'reset_state') as reset:
                report = assess_topology(model.to_dict(), normalizer.to_dict(), observations)
            self.assertEqual(report["transition_structure"]["metrics"]["valid_transition_count"], 1)
            self.assertEqual(seen, [None, [2], None])
            self.assertEqual(reset.call_count, 1)

    def test_candidate_deserialization_does_not_mutate_baseline_coordinates(self):
        import copy
        from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode
        baseline = DynamicalEnergyManifold(grid_height=1, grid_width=1)
        baseline.nodes = [ManifoldNode(0, (0, 0), 'A', [1.0], [2.0])]
        source = baseline.to_dict()
        frozen = copy.deepcopy(source)
        reference = DynamicalEnergyManifold.from_dict(source)
        candidate = DynamicalEnergyManifold.from_dict(source)
        candidate.nodes[0].center_x[0] = 3.0
        candidate.nodes[0].center_v[0] = 4.0
        self.assertEqual(source, frozen)
        self.assertEqual(reference.to_dict(), frozen)

    def test_structure_swaps_reduce_training_strain_without_relabeling(self):
        from traceform.evolution.guarded_calibration import adapt_grid_structure
        from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode
        from traceform.representation.normalizer import MultimodalNormalizer, CONTINUOUS_FEATURE_NAMES
        normalizer = MultimodalNormalizer()
        observations = [{'timestamp_utc': f'2026-10-01T00:00:0{index}Z'} for index in range(3)]
        normalizer.fit_discovery_window(observations)
        dimension = len(CONTINUOUS_FEATURE_NAMES)
        baseline = DynamicalEnergyManifold(grid_height=1, grid_width=4)
        baseline.nodes = [ManifoldNode(index, (0, coordinate), token, [value] * dimension, [0.0] * dimension)
                          for index, (token, coordinate, value) in enumerate(
                              [('A', 0, 0.0), ('B', 3, 0.0), ('C', 1, 5.0), ('D', 2, 10.0)])]
        frozen = baseline.to_dict()
        candidate = DynamicalEnergyManifold.from_dict(frozen)
        report = adapt_grid_structure(candidate, normalizer.to_dict(), observations)
        self.assertTrue(report['changed'])
        self.assertEqual(report['training_nonadjacent_pairs_before'], 3)
        self.assertEqual(report['training_nonadjacent_pairs_after'], 0)
        self.assertEqual(baseline.to_dict(), frozen)
        self.assertEqual(sorted(node.grid_coord for node in candidate.nodes), [(0, index) for index in range(4)])
        for original, changed in zip(baseline.nodes, candidate.nodes):
            self.assertEqual(original.state_token, changed.state_token)
            self.assertEqual(original.center_x, changed.center_x)
            self.assertEqual(original.center_v, changed.center_v)
            self.assertEqual(candidate.token_to_coord[changed.state_token], changed.grid_coord)
        again = adapt_grid_structure(candidate, normalizer.to_dict(), observations)
        self.assertFalse(again['changed'])

    def test_actual_structural_training_activation_and_rollback(self):
        import datetime
        import tempfile
        from traceform.evolution.model_registry import ModelRegistry
        from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode
        from traceform.representation.normalizer import MultimodalNormalizer, CONTINUOUS_FEATURE_NAMES
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        observations = [{'timestamp_utc': (start + datetime.timedelta(seconds=index)).isoformat(),
                         'session_id': 'synthetic'} for index in range(100)]
        normalizer = MultimodalNormalizer()
        normalizer.fit_discovery_window(observations[:70])
        dimension = len(CONTINUOUS_FEATURE_NAMES)
        model = DynamicalEnergyManifold(grid_height=1, grid_width=4)
        model.nodes = [ManifoldNode(index, (0, coordinate), token, [value] * dimension, [0.0] * dimension)
                       for index, (coordinate, token, value) in enumerate(
                           [(0, 'A', 5.0), (3, 'B', 5.0), (1, 'C', 20.0), (2, 'D', 30.0)])]
        frozen = model.to_dict()
        with tempfile.TemporaryDirectory() as directory:
            registry = ModelRegistry(directory)
            initial = registry.register(frozen, normalizer.to_dict(), {'accepted': True})
            registry.activate(initial)
            report = calibrate_candidate(registry, observations)
            self.assertTrue(report['accepted'])
            self.assertTrue(report['structural_change']['changed'])
            self.assertLess(report['candidate_mean_distance'], report['baseline_mean_distance'] * 0.95)
            self.assertTrue(report['holdout_topology_gate']['passed'])
            self.assertEqual(registry.bundle(initial)['manifold'], frozen)
            self.assertEqual(registry.active()['version'], report['candidate_version'])
            self.assertEqual(registry.active()['normalizer'], normalizer.to_dict())
            self.assertEqual(set(report['token_migration']), {'A', 'B', 'C', 'D'})
            self.assertEqual(registry.rollback(), initial)
            self.assertEqual(registry.active()['manifold'], frozen)

    def test_insufficient_data_never_activates(self):
        self.assertFalse(calibrate_candidate(None, [])["accepted"])

    def test_stream_audit_rejects_nested_nonfinite_and_bad_time_but_counts_gaps(self):
        valid = [{"timestamp_utc": "2026-10-01T00:00:00Z", "motion": {"x": [1.0, None]}},
                 {"timestamp_utc": "2026-10-01T00:10:00Z"}]
        report = audit_observations(iter(valid))
        self.assertTrue(report["valid"])
        self.assertEqual(report["gaps_over_300_seconds"], 1)
        self.assertFalse(audit_observations(valid + valid)["valid"])
        self.assertEqual(audit_observations([{"timestamp_utc": "bad", "motion": [float("nan"), float("inf")]}])["nonfinite_values"], 2)
        self.assertFalse(audit_observations([])["valid"])
        self.assertFalse(audit_observations([{"timestamp_utc": "2026-10-01T00:00:00"}])["valid"])

    def test_topology_diagnostic_distinguishes_grid_nonadjacency(self):
        from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode
        from traceform.representation.normalizer import MultimodalNormalizer, CONTINUOUS_FEATURE_NAMES
        normalizer = MultimodalNormalizer()
        model = DynamicalEnergyManifold(grid_height=1, grid_width=4)
        dimension = len(CONTINUOUS_FEATURE_NAMES)
        model.nodes = [ManifoldNode(0, (0, 0), "A", [0.0] * dimension, [0.0] * dimension),
                       ManifoldNode(1, (0, 3), "B", [0.0] * dimension, [0.0] * dimension)]
        observations = [{"timestamp_utc": "2026-10-01T00:00:00Z"}, {"timestamp_utc": "2026-10-01T00:00:30Z"}]
        normalizer.fit_discovery_window(observations)
        report = assess_topology(model.to_dict(), normalizer.to_dict(), observations)
        self.assertEqual(report["topographic_error"], 1)
        self.assertEqual(report["records"], 2)
        model.nodes[1].grid_coord = (0, 1)
        self.assertEqual(assess_topology(model.to_dict(), normalizer.to_dict(), observations)["topographic_error"], 0)
        model.nodes.pop()
        self.assertIsNone(assess_topology(model.to_dict(), normalizer.to_dict(), observations)["topographic_error"])

    def test_accepted_calibration_preserves_baseline_label_lineage(self):
        import copy
        import datetime
        import json
        from pathlib import Path
        import tempfile
        from unittest.mock import patch
        from traceform.evolution.model_registry import ModelRegistry
        from traceform.representation.manifold_lattice import DynamicalEnergyManifold
        from traceform.representation.normalizer import MultimodalNormalizer
        from traceform.streaming.edge_manifold_daemon import EdgeManifoldDaemon
        project = Path(__file__).resolve().parent.parent
        model = DynamicalEnergyManifold.from_dict(json.loads((project / 'artifacts/manifold_dynamics_v1/manifold_lattice_model.json').read_text()))
        normalizer = MultimodalNormalizer.from_dict(json.loads((project / 'artifacts/representation_v1/representation_spec.json').read_text())['normalization_parameters'])
        expected = EdgeManifoldDaemon(normalizer, model).model_version
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        observations = [{'timestamp_utc': (start + datetime.timedelta(seconds=index)).isoformat()} for index in range(100)]
        def fit(candidate, *args, **kwargs):
            self.assertTrue(kwargs['warm_start'])
            self.assertEqual(candidate.to_dict(), model.to_dict())
            candidate.nodes = copy.deepcopy(model.nodes)
        with tempfile.TemporaryDirectory() as directory:
            registry = ModelRegistry(directory)
            previous = registry.register(model.to_dict(), normalizer.to_dict(), {'accepted': True})
            registry.activate(previous)
            for _ in range(2):
                with patch.object(DynamicalEnergyManifold, 'fit_discovery_window', fit), \
                     patch('traceform.evolution.guarded_calibration.score_model', side_effect=[(2, 2), (1, 2)]):
                    report = calibrate_candidate(registry, observations)
                self.assertTrue(report['accepted'])
                from traceform.representation.provenance import content_digest
                for phase, records in (('training', observations[:70]), ('holdout', observations[70:])):
                    for label in ('baseline', 'candidate'):
                        structure = report['incremental_transition_structure'][phase][label]
                        self.assertEqual(structure['source_sha256'], content_digest(records))
                        self.assertEqual(structure['normalizer_sha256'], content_digest(normalizer.to_dict()))
                        self.assertGreaterEqual(structure['metrics']['strongly_connected_components_count'], 1)
                        self.assertIn('stationary_converged', structure['metrics'])
                self.assertEqual(report['cold_state_namespace'], expected)
                self.assertEqual(report['parent_registry_version'], previous)
                previous = registry.active()['version']
            from traceform.evolution import guarded_calibration
            assess = guarded_calibration.assess_topology
            def worsen(candidate, *args, **kwargs):
                candidate.nodes[0].center_x[0] += 0.01
            def topology(data, parameters, records):
                result = assess(data, parameters, records)
                if len(records) == 30:
                    result['topographic_error'] = 0.0 if data == registry.active()['manifold'] else 1.0
                return result
            with patch.object(DynamicalEnergyManifold, 'fit_discovery_window', worsen), \
                 patch('traceform.evolution.guarded_calibration.score_model', side_effect=[(2, 2), (1, 2)]), \
                 patch('traceform.evolution.guarded_calibration.assess_topology', topology):
                rejected = calibrate_candidate(registry, observations)
            self.assertFalse(rejected['accepted'], rejected['holdout_topology_gate'])
            self.assertFalse(rejected['holdout_topology_gate']['passed'])
            self.assertEqual(registry.active()['version'], previous)

