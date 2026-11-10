# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import unittest

from traceform.dynamics.alignment_statement import SCALE, quantize_alignment_witness, validate_alignment_witness


class TestAlignmentStatement(unittest.TestCase):
    def test_quantization_does_not_hide_invalid_float_structure(self):
        coupling = [[1 / 9] * 3 for _ in range(3)]
        invalid = [[0, .2000001, .5000003], [.2000001, 0, .3000001], [.5000003, .3000001, 0]]
        with self.assertRaisesRegex(ValueError, 'triangle'):
            quantize_alignment_witness(invalid, invalid, coupling)
        asymmetric = [[0, .5000001], [.5000003, 0]]
        with self.assertRaisesRegex(ValueError, 'symmetric'):
            quantize_alignment_witness(asymmetric, asymmetric, [[.25] * 2 for _ in range(2)])

    def test_quantization_preserves_unequal_uniform_marginals(self):
        first = [[0, .5, 1], [.5, 0, .5], [1, .5, 0]]
        second = [[0, 1], [1, 0]]
        coupling = [[1 / 6, 1 / 6] for _ in range(3)]
        witness, statement = quantize_alignment_witness(first, second, coupling)
        self.assertEqual([sum(row) for row in witness["coupling"]], witness["first_mass"])
        self.assertEqual([sum(row[j] for row in witness["coupling"]) for j in range(2)], witness["second_mass"])
        objective = sum((first[i][j] - second[k][l]) ** 2 * coupling[i][k] * coupling[j][l]
                        for i in range(3) for j in range(3) for k in range(2) for l in range(2))
        self.assertLessEqual(abs(objective - statement["objective_numerator"] / statement["objective_denominator"]),
                             statement["quantization"]["objective_error_upper_bound"])
        self.assertFalse(statement["cryptographic_zero_knowledge"])

    def test_quantization_rejects_nonfinite_and_wrong_marginals(self):
        metric = [[0, 1], [1, 0]]
        for coupling in ([[float("nan"), .25], [.25, .25]], [[.5, .5], [0, 0]]):
            with self.assertRaises(ValueError):
                quantize_alignment_witness(metric, metric, coupling)

    def test_exact_objective_and_permutation(self):
        metric = [[0, SCALE], [SCALE, 0]]
        masses = [SCALE // 2] * 2
        aligned = [[0, SCALE // 2], [SCALE // 2, 0]]
        self.assertEqual(validate_alignment_witness(metric, metric, aligned, masses, masses)["objective_numerator"], 0)
        uniform = [[SCALE // 4] * 2 for _ in range(2)]
        report = validate_alignment_witness(metric, metric, uniform, masses, masses)
        self.assertEqual(report["objective_numerator"] * 2, report["objective_denominator"])
        self.assertFalse(report["cryptographic_zero_knowledge"])

    def test_rejects_invalid_range_metric_and_marginals(self):
        metric = [[0, SCALE], [SCALE, 0]]
        masses = [SCALE // 2] * 2
        with self.assertRaises(ValueError):
            validate_alignment_witness(metric, metric, [[0, SCALE], [0, 0]], masses, masses)
        with self.assertRaises(ValueError):
            validate_alignment_witness([[0, True], [True, 0]], metric, [[SCALE // 2, 0], [0, SCALE // 2]], masses, masses)
        with self.assertRaises(ValueError):
            validate_alignment_witness([[0, SCALE, 0], [SCALE, 0, 0], [0, 0, 0]], metric, [], [], [])
