"""
Unit tests for Null & Surrogate models.
"""

import unittest
from traceform.null_models.surrogates import (
    generate_permutation_surrogate,
    generate_first_order_markov_surrogate,
    compute_empirical_transition_matrix,
    compute_transition_entropy_rate,
)


class TestNullModels(unittest.TestCase):
    def setUp(self):
        # A simple alternating trajectory S01 -> S02 -> S01 -> S02
        self.seq = ["S01", "S02"] * 50

    def test_permutation_preserves_marginals(self):
        surr = generate_permutation_surrogate(self.seq, seed=42)
        self.assertEqual(len(surr), len(self.seq))
        self.assertEqual(surr.count("S01"), self.seq.count("S01"))
        self.assertEqual(surr.count("S02"), self.seq.count("S02"))
        # But order should be perturbed
        self.assertNotEqual(surr, self.seq)

    def test_markov_surrogate(self):
        vocab, trans, stat = compute_empirical_transition_matrix(self.seq)
        self.assertEqual(vocab, ["S01", "S02"])
        self.assertAlmostEqual(trans["S01"]["S02"], 1.0, places=3)
        self.assertAlmostEqual(trans["S02"]["S01"], 1.0, places=3)

        # In strict alternating Markov chain, entropy rate should be 0.0
        h_rate = compute_transition_entropy_rate(trans, stat)
        self.assertAlmostEqual(h_rate, 0.0, places=2)

        markov_surr = generate_first_order_markov_surrogate(self.seq, seed=42)
        self.assertEqual(len(markov_surr), len(self.seq))
        # Since S01 always transitions to S02 and vice versa, markov surrogate must alternate
        self.assertEqual(markov_surr, self.seq)


if __name__ == "__main__":
    unittest.main()
