import datetime
import math
import random
import unittest

from traceform.forecasting.periodic_kernel import PeriodicTransitionKernel
from traceform.forecasting.periodic_spectrum import PeriodicSpectrum, TRUNCATION_WEIGHT_BOUND


class TestPeriodicSpectrum(unittest.TestCase):
    def test_exact_kernel_parity_across_calendar_boundaries(self):
        randomizer = random.Random(42)
        prior = {source: {target: 0.25 for target in range(4)} for source in range(4)}
        records = []
        start = datetime.datetime(2026, 3, 1, tzinfo=datetime.timezone.utc)
        for index in range(1200):
            instant = start + datetime.timedelta(seconds=index * 1800)
            records.append({"timestamp_utc": instant.isoformat(), "state_token": randomizer.randrange(4),
                            "model_version": "first" if index < 600 else "second"})
        for zone in ["UTC", "Asia/Kathmandu", "America/New_York", "Australia/Lord_Howe"]:
            exact = PeriodicTransitionKernel(zone).fit(records, prior)
            summary = PeriodicSpectrum(exact)
            for offset in [0, 6 * 86400 - 0.001, 7 * 86400 + 0.001, 30 * 86400, 240 * 86400]:
                instant = start + datetime.timedelta(seconds=offset)
                for source in range(4):
                    expected, actual = exact.row(source, instant), summary.row(source, instant)
                    self.assertLess(sum(abs(actual[key] - expected[key]) for key in expected), 1e-12)
                    self.assertAlmostEqual(sum(actual.values()), 1.0, places=14)
                    self.assertTrue(all(value >= 0 for value in actual.values()))

    def test_snapshot_is_stable_and_empty_fallback(self):
        kernel = PeriodicTransitionKernel().fit([], {0: {0: 1.0}})
        summary = PeriodicSpectrum(kernel)
        instant = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        kernel.global_trans[0][0] = 0
        kernel.timezone = datetime.timezone(datetime.timedelta(hours=3))
        self.assertEqual(summary.row(0, instant), {0: 1.0})
        self.assertEqual(summary.row(9, instant), {9: 1.0})
        self.assertEqual(summary.truncation_row_l1_bound(0), 0)
        with self.assertRaises(ValueError):
            summary.row(0, instant.replace(tzinfo=None))
        kernel = PeriodicTransitionKernel().fit([], {0: {0: 0.5, 1: 0.5}, 1: {1: 1.0}})
        kernel.counts = {0: {(0.0, 0.0): {1: 10}}}
        summary = PeriodicSpectrum(kernel)
        before = summary.row(0, instant)
        kernel.timezone = datetime.timezone(datetime.timedelta(hours=3))
        self.assertEqual(summary.row(0, instant), before)

    def test_noncanonical_and_invalid_counts_rejected(self):
        for day, week, count in [(0.1, 0, 1), (math.nan, 0, 1), (0, 0, -1), (0, 0, 1.5)]:
            kernel = PeriodicTransitionKernel().fit([], {0: {0: 1.0}})
            kernel.counts = {0: {(day, week): {0: count}}}
            with self.assertRaises(ValueError):
                PeriodicSpectrum(kernel)
        for prior in [{0: {0: math.nan}}, {0: {0: -1}}, {0: {0: 0.5}}]:
            with self.assertRaises(ValueError):
                PeriodicSpectrum(PeriodicTransitionKernel().fit([], prior))

    def test_truncation_bound_and_low_weight_tails(self):
        self.assertLess(TRUNCATION_WEIGHT_BOUND, 1e-17)
        kernel = PeriodicTransitionKernel().fit([], {0: {0: 0.5, 1: 0.5}, 1: {1: 1.0}})
        kernel.counts = {0: {(0.0, 0.0): {1: 1000000}}}
        summary = PeriodicSpectrum(kernel)
        self.assertLess(summary.truncation_row_l1_bound(0), 1e-13)
        start = datetime.datetime(2026, 10, 5, tzinfo=datetime.timezone.utc)
        for hours in range(168):
            instant = start + datetime.timedelta(hours=hours)
            first, second = kernel.row(0, instant), summary.row(0, instant)
            self.assertLess(sum(abs(first[key] - second[key]) for key in first), 1e-12)
