"""
Unit tests for Trajectory Structure Discovery components.
"""

import unittest
from traceform.discovery.recurrence import compute_rqa_metrics
from traceform.discovery.motifs import extract_trajectory_motifs
from traceform.discovery.transition_graph import build_transition_graph


class TestDiscovery(unittest.TestCase):
    def setUp(self):
        # 200-step synthetic alternating sequence
        self.mock_records = [
            {
                "step_index": i,
                "timestamp_utc": f"2026-09-03T{(i//10)%24:02d}:{(i%10)*6:02d}:00Z",
                "state_token": "S01" if (i % 4 in (0, 1)) else "S02",
                "normalized_features": [0.1 * (i % 2)] * 15,
                "topological_descriptors": {"spatial_cell": "tv58qxy"},
            }
            for i in range(200)
        ]

    def test_rqa_extraction(self):
        # Synthetic recurrence matrix
        n = 50
        r_mat = [
            [1 if i == j or abs(i - j) % 4 == 0 else 0 for j in range(n)]
            for i in range(n)
        ]
        rqa = compute_rqa_metrics(r_mat, epsilon=0.25)
        self.assertGreater(rqa.recurrence_rate, 0.0)
        self.assertGreater(rqa.determinism, 0.0)

    def test_motif_extraction(self):
        motifs = extract_trajectory_motifs(
            self.mock_records, min_length=3, min_support=3
        )
        self.assertTrue(len(motifs) > 0)
        # Verify first motif is structured
        self.assertIn("S01", motifs[0].pattern)

    def test_transition_graph(self):
        trans_mat, metrics = build_transition_graph(self.mock_records)
        self.assertIn("S01", trans_mat)
        self.assertIn("S02", trans_mat)
        self.assertEqual(metrics.total_nodes, 2)
        self.assertGreater(metrics.total_directed_edges, 0)


if __name__ == "__main__":
    unittest.main()
