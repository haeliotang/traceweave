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
                    "motion": {"stationary_likelihood": 0.9 if zone < 20 else 0.2, "motion_energy": 0.05 if zone < 20 else 0.6},
                    "environment": {"ambient_light_lux": lux, "barometric_pressure_hpa": 1013.2, "device_temp_c": 30.0},
                    "device_relation": {"battery_percentage": 90.0 - i * 0.05, "plugged": "UNPLUGGED"},
                    "audio_context": {"silence_likelihood": 0.9, "rms_loudness": 0.01, "spectral_entropy": 0.2},
                    "connectivity": {"wifi_connected": True, "wifi_fingerprint": "wf_1"},
                    "place": {"spatial_cell": "tv58xy"},
                }
            )
        self.normalizer.fit_discovery_window(self.obs_seq)

    def test_manifold_fit_and_tokens(self):
        manifold = DynamicalEnergyManifold(grid_height=3, grid_width=3)
        tokens = manifold.fit_discovery_window(self.obs_seq, self.normalizer, epochs=4, seed=42)

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
        self.assertEqual(len(manifold.last_obs_vector), len(self.normalizer.params.feature_names))
        self.assertEqual(len(manifold.last_velocity), len(self.normalizer.params.feature_names))


if __name__ == "__main__":
    unittest.main()
