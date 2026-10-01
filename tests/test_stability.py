"""
Tests for Temporal Stability Validation Engine.
"""

import unittest
from traceform.stability.temporal_validator import (
    TemporalStabilityValidator,
    StabilityMetrics,
)


class TestTemporalStability(unittest.TestCase):
    def setUp(self):
        self.validator = TemporalStabilityValidator(max_gap_seconds=1800.0)

    def test_spearman_rank_correlation(self):
        # Identical distributions
        p = {"A": 0.5, "B": 0.3, "C": 0.2}
        q = {"A": 0.5, "B": 0.3, "C": 0.2}
        corr = self.validator._spearman_rank_correlation(p, q)
        self.assertAlmostEqual(corr, 1.0, places=2)

        # Reversed order
        q_rev = {"A": 0.2, "B": 0.3, "C": 0.5}
        corr_rev = self.validator._spearman_rank_correlation(p, q_rev)
        self.assertAlmostEqual(corr_rev, -1.0, places=2)

    def test_matrix_jsd(self):
        vocab = ["A", "B"]
        m1 = {"A": {"A": 0.8, "B": 0.2}, "B": {"A": 0.1, "B": 0.9}}
        m2 = {"A": {"A": 0.8, "B": 0.2}, "B": {"A": 0.1, "B": 0.9}}
        stat = {"A": 0.5, "B": 0.5}

        # Identical matrices should have 0 JSD
        jsd_zero = self.validator._matrix_jsd(vocab, m1, m2, stat)
        self.assertEqual(jsd_zero, 0.0)

        # Divergent matrix
        m3 = {"A": {"A": 0.1, "B": 0.9}, "B": {"A": 0.9, "B": 0.1}}
        jsd_diff = self.validator._matrix_jsd(vocab, m1, m3, stat)
        self.assertGreater(jsd_diff, 0.0)

    def test_validate_synthetic_recurrence(self):
        # Repeating pattern: S01 -> S02 -> S03
        pattern = ["S01", "S02", "S03", "S01", "S02", "S03"] * 10
        w1_records = [
            {"state_token": tok, "timestamp": 1000 + i * 60, "duration": 60.0}
            for i, tok in enumerate(pattern)
        ]
        # W2 has the same repeating pattern plus minor noise
        pattern_w2 = ["S01", "S02", "S03", "S01", "S02", "S03"] * 10
        w2_records = [
            {"state_token": tok, "timestamp": 20000 + i * 60, "duration": 60.0}
            for i, tok in enumerate(pattern_w2)
        ]

        metrics, details = self.validator.validate(w1_records, w2_records)
        self.assertIsInstance(metrics, StabilityMetrics)
        self.assertEqual(metrics.w1_observation_count, len(w1_records))
        self.assertEqual(metrics.w2_observation_count, len(w2_records))
        self.assertEqual(metrics.vocabulary_jaccard_similarity, 1.0)
        self.assertEqual(metrics.w2_frames_covered_by_w1_vocabulary_ratio, 1.0)
        self.assertGreater(metrics.shared_vocabulary_rank_correlation, 0.9)
        self.assertGreater(metrics.distribution_cosine_similarity, 0.9)
        self.assertTrue(metrics.stability_verdict_confirmed)
        self.assertIn("motifs_recurrence_details", details)


if __name__ == "__main__":
    unittest.main()
