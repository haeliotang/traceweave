"""
Unit tests for Dynamical Energy-Guided Topological Manifold (DETM) Lattice.
"""

import unittest

from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.manifold_lattice import (
    DynamicalEnergyManifold,
    ManifoldNode,
)


class TestManifoldLattice(unittest.TestCase):
    def test_training_velocity_resets_across_long_and_nonpositive_gaps(self):
        manifold = DynamicalEnergyManifold(ema_alpha=.4)
        for times in ([0, 1, 1000, 1001], [0, 1, 1, 2], [0, 1, 0, 1]):
            velocities = manifold._compute_phase_velocities([[0], [1], [100], [101]], times)
            self.assertEqual(velocities[2], [0])
            self.assertAlmostEqual(velocities[3][0], .4)

    def test_training_resets_normalizer_and_velocity_at_stream_boundaries(self):
        from unittest.mock import patch
        class Normalizer:
            def __init__(self):
                self.resets = 0
            def reset_state(self):
                self.resets += 1
            def transform(self, observation):
                return observation['vector'], None
        observations = [{'timestamp_utc': f'2026-10-01T00:00:0{index}Z',
                         'vector': [index], 'boot_id': 'old' if index < 2 else 'new'}
                        for index in range(4)]
        normalizer = Normalizer()
        manifold = DynamicalEnergyManifold(grid_height=1, grid_width=1)
        with patch.object(manifold, '_compute_phase_velocities', wraps=manifold._compute_phase_velocities) as velocity_fit:
            manifold.fit_discovery_window(observations, normalizer, epochs=1)
        self.assertEqual(normalizer.resets, 2)
        self.assertEqual(velocity_fit.call_args.kwargs['discontinuities'], [True, False, True, False])
        velocities = manifold._compute_phase_velocities([[0], [1], [2], [3]], [0, 1, 2, 3],
                                                       discontinuities=[True, False, True, False])
        self.assertEqual(velocities[2], [0])
        self.assertAlmostEqual(velocities[3][0], .4)

    def test_training_resets_at_session_and_coordinate_binding_changes(self):
        from unittest.mock import patch
        class Normalizer:
            def __init__(self):
                self.resets = 0
            def reset_state(self):
                self.resets += 1
            def transform(self, observation):
                return observation['vector'], None
        for field in ('session_id', 'model_sha256', 'normalizer_sha256'):
            observations = [{'timestamp_utc': f'2026-10-01T00:00:0{index}Z',
                             'vector': [index], field: 'old' if index < 2 else 'new'}
                            for index in range(4)]
            normalizer = Normalizer()
            manifold = DynamicalEnergyManifold(grid_height=1, grid_width=1)
            with self.subTest(field=field), patch.object(manifold, '_compute_phase_velocities',
                    wraps=manifold._compute_phase_velocities) as velocity_fit:
                manifold.fit_discovery_window(observations, normalizer, epochs=1)
                self.assertEqual(normalizer.resets, 2)
                self.assertEqual(velocity_fit.call_args.kwargs['discontinuities'], [True, False, True, False])

    def test_incremental_fit_updates_existing_nodes_without_resampling_or_relabeling(self):
        from unittest.mock import patch
        class Normalizer:
            def transform(self, observation):
                return observation['vector'], None
        manifold = DynamicalEnergyManifold(grid_height=1, grid_width=1)
        manifold.nodes = [ManifoldNode(0, (0, 0), 'retained-token', [10.0], [0.0])]
        with patch('random.Random.sample', side_effect=AssertionError('incremental fit must not resample')):
            tokens = manifold.fit_discovery_window(
                [{'timestamp_utc': '2026-10-01T00:00:00Z', 'vector': [0.0]}],
                Normalizer(), epochs=1, warm_start=True)
        self.assertEqual(tokens, ['retained-token'])
        self.assertEqual(manifold.nodes[0].grid_coord, (0, 0))
        self.assertAlmostEqual(manifold.nodes[0].center_x[0], 9)
        self.assertEqual(manifold.nodes[0].center_v, [0])
        manifold.nodes[0].center_v = []
        with self.assertRaisesRegex(ValueError, 'dimension'):
            manifold.fit_discovery_window([{'vector': [0]}], Normalizer(), warm_start=True)

    def test_simulated_phase_quantization_preserves_real_sensor_history(self):
        manifold = DynamicalEnergyManifold()
        manifold.nodes = [ManifoldNode(0, (0, 0), 'A', [0] * 128, [1] * 128),
                          ManifoldNode(1, (0, 1), 'B', [0] * 128, [-1] * 128)]
        manifold.last_obs_vector = [3] * 128
        manifold.last_velocity = [4] * 128
        for velocity, expected in (([1] * 128, 'A'), ([-1] * 128, 'B')):
            token, _, distance = manifold.quantize_phase([0] * 128, velocity)
            self.assertEqual(token, expected)
            self.assertEqual(distance, 0)
        self.assertEqual(manifold.last_obs_vector, [3] * 128)
        self.assertEqual(manifold.last_velocity, [4] * 128)
        for vector, velocity in (([0], [0]), ([0] * 128, [0]), ([float('nan')] * 128, [0] * 128)):
            with self.assertRaises(ValueError):
                manifold.quantize_phase(vector, velocity)

    def setUp(self):
        self.normalizer = MultimodalNormalizer()
        # Synthetic observations with continuous features
        self.obs_seq = []
        for i in range(120):
            # Alternating between two physical zones with smooth transition
            zone = i % 40
            lux = 10.0 if zone < 20 else 500.0
            acc = 9.8 if zone < 20 else 11.5
            self.obs_seq.append(
                {
                    "timestamp_utc": f"2026-09-02T22:{i//2:02d}:{(i%2)*30:02d}Z",
                    "observation_id": f"obs-{i:03d}",
                    "motion": {
                        "stationary_likelihood": 0.9 if zone < 20 else 0.2,
                        "motion_energy": 0.05 if zone < 20 else 0.6,
                    },
                    "environment": {
                        "ambient_light_lux": lux,
                        "barometric_pressure_hpa": 1013.2,
                        "device_temp_c": 30.0,
                    },
                    "device_relation": {
                        "battery_percentage": 90.0 - i * 0.05,
                        "plugged": "UNPLUGGED",
                    },
                    "audio_context": {
                        "silence_likelihood": 0.9,
                        "rms_loudness": 0.01,
                        "spectral_entropy": 0.2,
                    },
                    "connectivity": {
                        "wifi_connected": True,
                        "wifi_fingerprint": "wf_1",
                    },
                    "place": {"spatial_cell": "tv58xy"},
                }
            )
        self.normalizer.fit_discovery_window(self.obs_seq)

    def test_manifold_fit_and_tokens(self):
        manifold = DynamicalEnergyManifold(grid_height=3, grid_width=3)
        tokens = manifold.fit_discovery_window(
            self.obs_seq, self.normalizer, epochs=4, seed=42
        )

        self.assertEqual(len(tokens), 9)
        self.assertEqual(len(manifold.nodes), 9)
        self.assertEqual(tokens[0], "M01")

        # Check all grid coordinates present
        coords = [nd.grid_coord for nd in manifold.nodes]
        for u in range(3):
            for v in range(3):
                self.assertIn((u, v), coords)

    def test_energy_distance_monotonicity(self):
        node = ManifoldNode(
            node_id=0,
            grid_coord=(0, 0),
            state_token="M01",
            center_x=[0.0] * 15,
            center_v=[1.0] + [0.0] * 14,
        )
        manifold = DynamicalEnergyManifold()

        # Same position, parallel velocity
        d_close = manifold.compute_energy_distance([0.0] * 15, [1.0] + [0.0] * 14, node)

        # Same position, opposite velocity (high kinetic divergence)
        d_opp = manifold.compute_energy_distance([0.0] * 15, [-1.0] + [0.0] * 14, node)

        self.assertLess(d_close, d_opp)

    def test_quantize_realtime_frame(self):
        manifold = DynamicalEnergyManifold(grid_height=3, grid_width=3)
        manifold.fit_discovery_window(self.obs_seq, self.normalizer, epochs=3, seed=42)

        # Quantize first frame
        tok1, coord1, dist1 = manifold.quantize(self.obs_seq[0], self.normalizer)
        self.assertTrue(tok1.startswith("M"))
        self.assertIsInstance(coord1, tuple)
        self.assertGreaterEqual(dist1, 0.0)

        # Quantize next frame
        tok2, coord2, dist2 = manifold.quantize(self.obs_seq[1], self.normalizer)
        self.assertTrue(tok2.startswith("M"))

    def test_serialization(self):
        manifold = DynamicalEnergyManifold(grid_height=2, grid_width=2)
        manifold.fit_discovery_window(self.obs_seq, self.normalizer, epochs=2, seed=42)

        data = manifold.to_dict()
        restored = DynamicalEnergyManifold.from_dict(data)

        self.assertEqual(manifold.k_states, restored.k_states)
        self.assertEqual(len(manifold.nodes), len(restored.nodes))
        self.assertEqual(manifold.nodes[0].grid_coord, restored.nodes[0].grid_coord)

    def test_topological_neighborhood_decay(self):
        manifold = DynamicalEnergyManifold(grid_height=3, grid_width=3)
        # Check neighborhood weight decays with grid Manhattan/Euclidean distance
        bmu_coord = (1, 1)
        h_center = manifold.compute_neighborhood_weight((1, 1), bmu_coord, sigma=1.0)
        h_adjacent = manifold.compute_neighborhood_weight((1, 2), bmu_coord, sigma=1.0)
        h_corner = manifold.compute_neighborhood_weight((0, 0), bmu_coord, sigma=1.0)

        self.assertAlmostEqual(h_center, 1.0)
        self.assertLess(h_adjacent, h_center)
        self.assertLess(h_corner, h_adjacent)

    def test_quantize_dimension_safety(self):
        manifold = DynamicalEnergyManifold(grid_height=2, grid_width=2)
        manifold.fit_discovery_window(self.obs_seq, self.normalizer, epochs=2, seed=42)

        # Force last_obs_vector and last_velocity to an older / mismatched dimension (e.g. 5D instead of 26D)
        manifold.last_obs_vector = [0.1, 0.2, 0.3, 0.4, 0.5]
        manifold.last_velocity = [0.01, 0.02, 0.03, 0.04, 0.05]

        # Quantizing with normalizer expecting 26D should not raise IndexError
        tok, coord, dist = manifold.quantize(self.obs_seq[0], self.normalizer)
        self.assertTrue(tok.startswith("M"))
        self.assertEqual(
            len(manifold.last_obs_vector), len(self.normalizer.params.feature_names)
        )
        self.assertEqual(
            len(manifold.last_velocity), len(self.normalizer.params.feature_names)
        )


if __name__ == "__main__":
    unittest.main()
