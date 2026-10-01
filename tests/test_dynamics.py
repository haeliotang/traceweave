"""
Tests for Phase Transition and Infradian Dynamics Engines.
"""

import unittest
from traceform.dynamics.bifurcation import PhaseTransitionDetector, SlidingWindowPoint
from traceform.dynamics.infradian import (
    InfradianDynamicsAuditor,
    InfradianComparisonMetrics,
)


class TestDynamics(unittest.TestCase):
    def test_bifurcation_sliding_spectrum(self):
        # 100 steps of stable repetitive state, then 100 steps of high entropy noise
        records = [
            {"state_token": "S01", "timestamp_utc": f"2026-09-03T{i%24:02d}:00:00Z"}
            for i in range(100)
        ]
        records += [
            {
                "state_token": f"S{10 + (i % 10):02d}",
                "timestamp_utc": f"2026-09-07T{i%24:02d}:00:00Z",
            }
            for i in range(100)
        ]

        detector = PhaseTransitionDetector(window_size=40, step_size=20)
        spectrum = detector.compute_sliding_spectrum(records)

        self.assertGreater(len(spectrum), 3)
        self.assertIsInstance(spectrum[0], SlidingWindowPoint)
        # Early stable window should have near 0 entropy
        self.assertAlmostEqual(spectrum[0].state_entropy, 0.0, places=2)
        # Later window should have high entropy and trigger bifurcation alert
        self.assertGreater(spectrum[-1].state_entropy, 2.0)
        self.assertTrue(any(p.is_bifurcation_alert for p in spectrum))

    def test_infradian_dynamics_auditor(self):
        # 2026-09-02 is Wednesday (weekday)
        # 2026-09-05 is Saturday (weekend)
        weekday_recs = [
            {"state_token": "S01", "timestamp_utc": "2026-09-02T10:00:00Z"}
            for _ in range(50)
        ]
        weekend_recs = [
            {"state_token": "S02", "timestamp_utc": "2026-09-05T12:00:00Z"}
            for _ in range(50)
        ]
        all_recs = weekday_recs + weekend_recs

        auditor = InfradianDynamicsAuditor()
        metrics, details = auditor.audit_weekly_split(all_recs)

        self.assertIsInstance(metrics, InfradianComparisonMetrics)
        self.assertEqual(metrics.weekday_observation_count, 50)
        self.assertEqual(metrics.weekend_observation_count, 50)
        self.assertTrue(metrics.is_distinct_bistable_attractor)
        self.assertIn("top_weekday_states", details)
        self.assertIn("top_weekend_states", details)


if __name__ == "__main__":
    unittest.main()
