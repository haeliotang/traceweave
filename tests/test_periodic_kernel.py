import datetime
import unittest

from traceform.forecasting.periodic_kernel import PeriodicTransitionKernel


class TestPeriodicKernel(unittest.TestCase):
    def setUp(self):
        self.records = []
        for day, target in [(5, "B"), (10, "C")]:
            for minute in range(20):
                self.records.extend([
                    {"timestamp_utc": f"2026-10-{day:02d}T06:{minute:02d}:00Z", "state_token": "A"},
                    {"timestamp_utc": f"2026-10-{day:02d}T06:{minute:02d}:01Z", "state_token": target},
                ])
        prior = {"A": {"B": 0.5, "C": 0.5}, "B": {"A": 1.0}, "C": {"A": 1.0}}
        self.kernel = PeriodicTransitionKernel().fit(self.records, prior)

    def test_weekly_condition_and_normalization(self):
        monday = datetime.datetime.fromisoformat("2026-10-05T06:10:00+00:00")
        saturday = monday + datetime.timedelta(days=5)
        first = self.kernel.row("A", monday)
        second = self.kernel.row("A", saturday)
        self.assertGreater(first["B"], second["B"])
        self.assertAlmostEqual(sum(first.values()), 1.0)
        self.assertTrue(all(value >= 0 for value in first.values()))

    def test_six_hour_and_week_boundaries_are_continuous(self):
        for text in ["2026-10-05T06:00:00+00:00", "2026-10-12T00:00:00+00:00"]:
            boundary = datetime.datetime.fromisoformat(text)
            before = self.kernel.row("A", boundary - datetime.timedelta(microseconds=1))
            after = self.kernel.row("A", boundary + datetime.timedelta(microseconds=1))
            self.assertLess(sum(abs(before[key] - after[key]) for key in before), 1e-8)

    def test_timezone_equivalent_instants(self):
        kernel = PeriodicTransitionKernel("Asia/Kathmandu")
        utc = datetime.datetime.fromisoformat("2026-10-05T00:15:00+00:00")
        local = utc.astimezone(kernel.timezone)
        self.assertEqual(kernel.phases(utc), kernel.phases(local))
        with self.assertRaises(ValueError):
            kernel.phases(utc.replace(tzinfo=None))

    def test_model_change_does_not_create_transition(self):
        records = [dict(row, model_version=str(index)) for index, row in enumerate(self.records)]
        kernel = PeriodicTransitionKernel().fit(records, self.kernel.global_trans)
        self.assertEqual(kernel.counts, {})
        instant = datetime.datetime.fromisoformat("2026-10-05T06:00:00+00:00")
        self.assertEqual(kernel.row("A", instant), {"B": 0.5, "C": 0.5})
