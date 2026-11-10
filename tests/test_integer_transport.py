# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import unittest
import random

from traceform.dynamics.alignment_statement import SCALE, validate_alignment_witness
from traceform.dynamics.integer_transport import cycle_objective, northwest_transport, optimize_integer_transport, optimize_uniform_permutation, optimize_quantized_uniform_permutation


class TestIntegerTransport(unittest.TestCase):
    def test_quantized_search_rejects_worse_full_precision_candidate(self):
        points, other = [1127, 6, 9398, 6318], [5906, 1203, 9552, 7846]
        first = [[abs(a - b) for b in points] for a in points]
        second = [[abs(a - b) for b in other] for a in other]
        result = optimize_quantized_uniform_permutation(first, second, [250000] * 4, [250000] * 4)
        self.assertTrue(result['full_precision_candidate_rejected'])
        self.assertEqual(result['permutation'], [0, 1, 2, 3])
        self.assertEqual(result['objective_numerator'], result['initial_objective_numerator'])

    def test_six_bit_search_keeps_full_precision_rejection_gate(self):
        points, other = [4508, 24, 37592, 25272], [23624, 4812, 38208, 31384]
        first = [[abs(a - b) for b in points] for a in points]
        second = [[abs(a - b) for b in other] for a in other]
        result = optimize_quantized_uniform_permutation(first, second, [250000] * 4, [250000] * 4,
                                                       search_distance_shift=14)
        self.assertTrue(result['full_precision_candidate_rejected'])
        self.assertEqual(result['permutation'], [0, 1, 2, 3])
        self.assertEqual(result['objective_numerator'], result['initial_objective_numerator'])
        self.assertEqual(result['search_distance_shift'], 14)

    def test_quantized_search_reports_exact_loss_and_never_worsens_identity(self):
        rng = random.Random(812)
        for nodes in (4, 8, 16):
            for _ in range(4):
                points = [rng.randrange(SCALE + 1) for _ in range(nodes)]
                other = [rng.randrange(SCALE + 1) for _ in range(nodes)]
                first = [[abs(a - b) for b in points] for a in points]
                second = [[abs(a - b) for b in other] for a in other]
                masses = [SCALE // nodes] * nodes
                result = optimize_quantized_uniform_permutation(first, second, masses, masses)
                exact = validate_alignment_witness(first, second, result['coupling'], masses, masses)
                self.assertEqual(result['objective_numerator'], exact['objective_numerator'])
                self.assertLessEqual(result['objective_numerator'], result['initial_objective_numerator'])
                self.assertEqual(result['search_distance_shift'], 12)

    def test_uniform_permutation_descent_recovers_transposition_at_full_shape(self):
        for nodes in (1, 2, 4, 8, 16):
            with self.subTest(nodes=nodes):
                points = [index * index * 4000 for index in range(nodes)]
                first = [[abs(a - b) for b in points] for a in points]
                permutation = list(range(nodes))
                if nodes > 1:
                    permutation[0], permutation[1] = permutation[1], permutation[0]
                second = [[first[i][j] for j in permutation] for i in permutation]
                masses = [SCALE // nodes] * nodes
                result = optimize_uniform_permutation(first, second, masses, masses)
                self.assertEqual(result['objective_numerator'], 0)
                self.assertEqual(sorted(result['permutation']), list(range(nodes)))
                exact = validate_alignment_witness(first, second, result['coupling'], masses, masses)
                self.assertEqual(exact['objective_numerator'], result['objective_numerator'])

    def test_uniform_permutation_rejects_nonuniform_or_rectangular_contract(self):
        metric = [[0, SCALE], [SCALE, 0]]
        for first_mass, second_mass in (([400000, 600000], [500000, 500000]),
                                        ([500000, 500000], [400000, 600000]),
                                        ([500000, 500000], [SCALE])):
            with self.assertRaises(ValueError):
                optimize_uniform_permutation(metric, metric, first_mass, second_mass)

    def test_private_mass_seed_has_exact_rectangular_and_zero_marginals(self):
        first, second = [0, 400000, 600000], [333334, 333333, 333333]
        coupling = northwest_transport(first, second)
        self.assertEqual([sum(row) for row in coupling], first)
        self.assertEqual([sum(row[j] for row in coupling) for j in range(3)], second)
        for masses in ([True, SCALE - 1], [-1, SCALE + 1], [1, 2]):
            with self.assertRaises(ValueError):
                northwest_transport(masses, second)

    def test_cycle_polynomial_matches_independent_four_index_objective(self):
        first = [[0, 1235, 2469], [1235, 0, 1234], [2469, 1234, 0]]
        second = [[0, 400000, 800000], [400000, 0, 400000], [800000, 400000, 0]]
        masses = [5, 7, SCALE - 12]
        coupling = northwest_transport(masses, masses)
        initial = validate_alignment_witness(first, second, coupling, masses, masses)['objective_numerator']
        values = []
        for delta in range(-5, 1):
            candidate = [row[:] for row in coupling]
            candidate[0][0] += delta; candidate[1][1] += delta
            candidate[0][1] -= delta; candidate[1][0] -= delta
            exact = validate_alignment_witness(first, second, candidate, masses, masses)['objective_numerator']
            self.assertEqual(cycle_objective(first, second, coupling, initial, 0, 1, 0, 1, delta), exact)
            values.append(exact)
        self.assertEqual(min(values), min(values[0], values[-1]))

    def test_descent_improves_swapped_masses_without_supplied_transport(self):
        metric = [[0, SCALE], [SCALE, 0]]
        result = optimize_integer_transport(metric, metric, [400000, 600000], [600000, 400000])
        self.assertGreater(result['initial_objective_numerator'], 0)
        self.assertEqual(result['objective_numerator'], 0)
        self.assertEqual(result['coupling'], [[0, 400000], [600000, 0]])

    def test_rectangular_descent_preserves_marginals_and_never_increases_objective(self):
        first = [[0, SCALE], [SCALE, 0]]
        second = [[0, 400000, 800000], [400000, 0, 400000], [800000, 400000, 0]]
        masses = [333334, 333333, 333333]
        result = optimize_integer_transport(first, second, [500000, 500000], masses)
        self.assertLessEqual(result['objective_numerator'], result['initial_objective_numerator'])
        self.assertEqual([sum(row) for row in result['coupling']], [500000, 500000])
        self.assertEqual([sum(row[j] for row in result['coupling']) for j in range(3)], masses)
