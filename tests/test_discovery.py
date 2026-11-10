# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

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

    def test_transition_graph_empty_has_zero_components(self):
        matrix, metrics = build_transition_graph([])
        self.assertEqual(matrix, {})
        self.assertEqual(metrics.total_nodes, 0)
        self.assertEqual(metrics.total_directed_edges, 0)
        self.assertEqual(metrics.strongly_connected_components_count, 0)

    def test_transition_graph_counts_actual_strong_components(self):
        tokens = ["A", "B", "A", "C", "D", "C", "E"]
        records = [{"timestamp_utc": f"2026-10-01T00:00:{index:02d}Z", "state_token": token}
                   for index, token in enumerate(tokens)]
        matrix, metrics = build_transition_graph(records)
        self.assertEqual(metrics.strongly_connected_components_count, 3)
        self.assertEqual(metrics.total_directed_edges, 6)
        self.assertEqual(matrix["E"], {"E": 1.0})
        # The unobserved self-loop fallback must not become an empirical edge.
        self.assertFalse(any(edge["source"] == "E" for edge in metrics.top_transition_edges))

    def test_transition_graph_rejects_cross_segment_and_nonpositive_time_pairs(self):
        for boundary in ({"boot_id": "new"}, {"model_version": "new"},
                         {"continuity_epoch": 2}, {"source_mode": "new"},
                         {"continuous_from_previous": False}, {"session_id": "new"},
                         {"model_sha256": "new"}, {"normalizer_sha256": "new"}):
            records = [{"timestamp_utc": "2026-10-01T00:00:00Z", "state_token": "A"},
                       {"timestamp_utc": "2026-10-01T00:00:01Z", "state_token": "B", **boundary}]
            with self.subTest(boundary=boundary):
                matrix, metrics = build_transition_graph(records)
                self.assertEqual(matrix, {"A": {"A": 1.0}, "B": {"B": 1.0}})
                self.assertEqual(metrics.total_directed_edges, 0)
                self.assertEqual(metrics.strongly_connected_components_count, 2)
        for second in ("2026-10-01T00:00:00Z", "2026-09-30T23:59:59Z", "2026-10-01T01:00:00Z"):
            records = [{"timestamp_utc": "2026-10-01T00:00:00Z", "state_token": "A"},
                       {"timestamp_utc": second, "state_token": "B"}]
            with self.subTest(second=second):
                self.assertEqual(build_transition_graph(records)[1].total_directed_edges, 0)

    def test_transition_components_match_independent_reachability(self):
        import datetime
        import random
        rng = random.Random(20261001)
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        for size in range(1, 9):
            for trial in range(10):
                nodes = [str(index) for index in range(size)]
                edges = [(left, right) for left in range(size) for right in range(size) if rng.random() < 0.25]
                sequence = [(token, False) for token in nodes]
                for left, right in edges:
                    sequence.extend([(nodes[left], False), (nodes[right], True)])
                records = [{"timestamp_utc": (start + datetime.timedelta(seconds=index)).isoformat(),
                            "state_token": token, "continuous_from_previous": continuous}
                           for index, (token, continuous) in enumerate(sequence)]
                reach = [[left == right or (left, right) in edges for right in range(size)] for left in range(size)]
                for middle in range(size):
                    for left in range(size):
                        for right in range(size):
                            reach[left][right] |= reach[left][middle] and reach[middle][right]
                expected = sum(not any(reach[left][right] and reach[right][left] for right in range(left))
                               for left in range(size))
                with self.subTest(size=size, trial=trial):
                    self.assertEqual(build_transition_graph(records)[1].strongly_connected_components_count, expected)

    def test_periodic_transition_graph_stationary_distribution(self):
        records = [{"timestamp_utc": f"2026-10-01T00:00:{index:02d}Z", "state_token": token}
                   for index, token in enumerate(["A", "B", "A", "C", "A"])]
        matrix, metrics = build_transition_graph(records)
        for token, expected in {"A": 0.5, "B": 0.25, "C": 0.25}.items():
            self.assertAlmostEqual(metrics.stationary_distribution[token], expected, places=10)
        self.assertTrue(metrics.stationary_converged)
        self.assertLessEqual(metrics.stationarity_residual, 1e-10)

    def test_transition_graph(self):
        trans_mat, metrics = build_transition_graph(self.mock_records)
        self.assertIn("S01", trans_mat)
        self.assertIn("S02", trans_mat)
        self.assertEqual(metrics.total_nodes, 2)
        self.assertGreater(metrics.total_directed_edges, 0)


if __name__ == "__main__":
    unittest.main()
