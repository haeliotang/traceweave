"""
Unit tests for MultimodalDistanceMetric.
"""

import unittest
from traceform.representation.metrics import MultimodalDistanceMetric


class TestMultimodalDistanceMetric(unittest.TestCase):
    def setUp(self):
        self.metric = MultimodalDistanceMetric()
        self.vec_a = [0.0] * 15
        self.topo_a = {
            "spatial_cell": "tv58qxy",
            "wifi_fingerprint": "wifi_home_1",
            "cellular_cell_fingerprint": "cell_tower_1",
        }

        self.vec_b = [1.0] * 15
        self.topo_b = {
            "spatial_cell": "tv58qxy",
            "wifi_fingerprint": "wifi_home_1",
            "cellular_cell_fingerprint": "cell_tower_1",
        }

        self.vec_c = [2.0] * 15
        self.topo_c = {
            "spatial_cell": "tv58abc",
            "wifi_fingerprint": "wifi_work_2",
            "cellular_cell_fingerprint": "cell_tower_2",
        }

    def test_identity(self):
        d_self = self.metric.compute_distance(
            self.vec_a, self.topo_a, self.vec_a, self.topo_a
        )
        self.assertEqual(d_self, 0.0)

    def test_symmetry(self):
        d_ab = self.metric.compute_distance(
            self.vec_a, self.topo_a, self.vec_b, self.topo_b
        )
        d_ba = self.metric.compute_distance(
            self.vec_b, self.topo_b, self.vec_a, self.topo_a
        )
        self.assertAlmostEqual(d_ab, d_ba, places=5)

    def test_topological_sensitivity(self):
        # same continuous vector, different topology
        d_same_topo = self.metric.compute_distance(
            self.vec_a, self.topo_a, self.vec_a, self.topo_a
        )
        d_diff_topo = self.metric.compute_distance(
            self.vec_a, self.topo_a, self.vec_a, self.topo_c
        )
        self.assertEqual(d_same_topo, 0.0)
        self.assertGreater(d_diff_topo, 0.15)


if __name__ == "__main__":
    unittest.main()
