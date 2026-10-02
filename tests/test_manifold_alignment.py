import unittest

from traceform.dynamics.manifold_alignment import align_metric_spaces, node_distance_matrix, round_transport


class TestManifoldAlignment(unittest.TestCase):
    def test_permuted_metric_structure_and_marginals(self):
        first = [[0, 0.3, 1], [0.3, 0, 0.7], [1, 0.7, 0]]
        permutation = [2, 0, 1]
        second = [[first[row][column] for column in permutation] for row in permutation]
        report = align_metric_spaces(first, second)
        self.assertLess(report["squared_gw_objective"], 0.005)
        self.assertLess(report["marginal_error"], 1e-6)
        self.assertTrue(report["converged"])

    def test_unequal_cardinality_and_invalid_distances(self):
        report = align_metric_spaces([[0, 1], [1, 0]], [[0, 0.5, 1], [0.5, 0, 0.5], [1, 0.5, 0]])
        self.assertEqual(len(report["coupling"]), 2)
        self.assertEqual(len(report["coupling"][0]), 3)
        self.assertLess(report["marginal_error"], 1e-6)
        with self.assertRaises(ValueError):
            align_metric_spaces([[0, 1], [2, 0]], [[0]])

    def test_distance_normalization(self):
        model = {"nodes": [{"center_x": [0, 0]}, {"center_x": [3, 4]}]}
        self.assertEqual(node_distance_matrix(model), [[0, 1], [1, 0]])

    def test_transport_rounding_restores_both_marginals(self):
        coupling = round_transport([[0.49, 0.02], [0.005, 0.485]])
        for row in coupling:
            self.assertAlmostEqual(sum(row), 0.5)
        for column in range(2):
            self.assertAlmostEqual(sum(row[column] for row in coupling), 0.5)
        self.assertTrue(all(value >= 0 for row in coupling for value in row))
