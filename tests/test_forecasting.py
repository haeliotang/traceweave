# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Tests for Trajectory Forecasting and Hitting Time Estimator.
"""

import unittest
from traceform.forecasting.predictor import (
    VariableOrderPredictor,
    HittingTimeEstimator,
    ForecastingEvaluationMetrics,
)


class TestForecasting(unittest.TestCase):
    def test_variable_order_predictor_fit_and_predict(self):
        train_tokens = ["S01", "S02", "S01", "S02", "S01", "S03", "S01", "S02"] * 10
        predictor = VariableOrderPredictor(smoothing=0.01)
        predictor.fit(train_tokens)

        # After S01, S02 should be the most likely next state
        proba_after_s01 = predictor.predict_proba(["S01"])
        self.assertIn("S02", proba_after_s01)
        self.assertGreater(proba_after_s01["S02"], proba_after_s01["S03"])

        # Predict with trigram context
        proba_trigram = predictor.predict_proba(["S02", "S01"])
        self.assertGreater(proba_trigram["S02"], proba_trigram["S01"])

    def test_variable_order_predictor_evaluation(self):
        train_tokens = ["A", "B", "A", "B", "A", "B"] * 20
        test_tokens = ["A", "B", "A", "B", "A", "B"] * 10

        predictor = VariableOrderPredictor(smoothing=0.01)
        predictor.fit(train_tokens)

        metrics = predictor.evaluate(test_tokens)
        self.assertIsInstance(metrics, ForecastingEvaluationMetrics)
        self.assertGreaterEqual(metrics.top1_accuracy, 0.90)
        self.assertGreaterEqual(metrics.top3_accuracy, 0.99)
        self.assertGreater(metrics.total_test_steps, 50)

    def test_hitting_time_estimator(self):
        # A simple linear chain: A -> B -> C (where C is absorbing target)
        trans_matrix = {
            "A": {"A": 0.0, "B": 1.0, "C": 0.0},
            "B": {"A": 0.0, "B": 0.0, "C": 1.0},
            "C": {"A": 0.0, "B": 0.0, "C": 1.0},
        }
        vocab = ["A", "B", "C"]
        target_states = {"C"}

        estimator = HittingTimeEstimator(step_nominal_seconds=30.0)
        ht_res = estimator.compute_expected_hitting_time(
            trans_matrix, target_states, vocab
        )

        self.assertEqual(ht_res["C"]["expected_steps"], 0.0)
        self.assertAlmostEqual(ht_res["B"]["expected_steps"], 1.0, places=1)
        self.assertAlmostEqual(ht_res["A"]["expected_steps"], 2.0, places=1)
        self.assertAlmostEqual(ht_res["A"]["expected_minutes"], 1.0, places=1)


if __name__ == "__main__":
    unittest.main()
