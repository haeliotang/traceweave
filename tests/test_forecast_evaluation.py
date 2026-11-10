# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import datetime
import unittest

from traceform.forecasting.evaluation import evaluate_forecasts


class TestForecastEvaluation(unittest.TestCase):
    def records(self):
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        return [{"timestamp_utc": (start + datetime.timedelta(seconds=30 * index)).isoformat(),
                 "state_token": "A" if index % 2 == 0 else "B"} for index in range(40)]

    def test_alternation_beats_persistence_and_no_train_leakage(self):
        records = self.records()
        split = datetime.datetime.fromisoformat(records[20]["timestamp_utc"])
        report = evaluate_forecasts(records, split)
        self.assertEqual(report["train_records"], 20)
        self.assertFalse(report["eligible_for_model_selection"])
        self.assertEqual(report["metrics"]["persist"]["top1_accuracy"], 0.0)
        self.assertEqual(report["metrics"]["first_order"]["top1_accuracy"], 1.0)
        records[-1]["state_token"] = "UNSEEN"
        report = evaluate_forecasts(records, split)
        self.assertNotIn("UNSEEN", report["vocabulary"])
        self.assertEqual(report["unknown_holdout_transitions"], 1)
        self.assertIsNone(report["paired_comparisons"]["periodic"]["paired_day_bootstrap_95_interval"])

    def test_reversed_records_rejected(self):
        records = self.records()
        split = datetime.datetime.fromisoformat(records[20]["timestamp_utc"])
        with self.assertRaises(ValueError):
            evaluate_forecasts(list(reversed(records)), split)
