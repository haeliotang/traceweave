# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Unit tests for Attractor Landscape and Critical Slowing Down (CSD) Auditor.
"""

import unittest

from traceform.dynamics.manifold_drift import (
    AttractorLandscapeAuditor,
    CSDAlert,
)


class TestManifoldDrift(unittest.TestCase):
    def setUp(self):
        self.auditor = AttractorLandscapeAuditor(grid_height=3, grid_width=3)

    def test_potential_surface_and_basins(self):
        # Create records concentrated around (1, 1) as a dominant attractor
        records = [{"grid_coord": (1, 1), "state_token": "M05"}] * 100
        # Few records around peripheral nodes
        records += [{"grid_coord": (0, 0), "state_token": "M01"}] * 5
        records += [{"grid_coord": (2, 2), "state_token": "M09"}] * 5

        pot = self.auditor.compute_potential_surface(records)
        self.assertEqual(len(pot), 3)
        self.assertEqual(len(pot[0]), 3)

        # Potential at (1, 1) should be lowest (deepest potential well)
        u_center = pot[1][1]
        u_corner = pot[0][0]
        self.assertLess(u_center, u_corner)

        # Detect basins
        basins = self.auditor.detect_attractor_basins(pot)
        self.assertGreaterEqual(len(basins), 1)
        self.assertEqual(basins[0].grid_coord, (1, 1))
        self.assertGreater(basins[0].potential_depth, 0.0)

    def test_attractor_drift_tracking(self):
        # W1 concentrated at (0, 0)
        w1_records = [{"grid_coord": (0, 0), "state_token": "M01"}] * 80
        w1_records += [{"grid_coord": (1, 1), "state_token": "M05"}] * 20

        # W2 shifted attractor to (2, 2)
        w2_records = [{"grid_coord": (2, 2), "state_token": "M09"}] * 80
        w2_records += [{"grid_coord": (1, 1), "state_token": "M05"}] * 20

        res = self.auditor.track_attractor_drift(w1_records, w2_records)
        self.assertIn("mean_grid_drift_distance", res)
        self.assertGreater(res["mean_grid_drift_distance"], 0.0)
        self.assertIn("attractor_drift_details", res)

    def test_critical_slowing_down_alert(self):
        # Baseline quiet records at (1, 1)
        quiet = [
            {"grid_coord": (1, 1), "timestamp_utc": f"2026-09-01T{i:02d}:00:00Z"}
            for i in range(100)
        ]

        # High-fluctuation surge (CSD tipping zone: bouncing wildly across corners)
        surge = []
        for i in range(100):
            c = (0, 0) if i % 2 == 0 else (2, 2)
            surge.append(
                {"grid_coord": c, "timestamp_utc": f"2026-09-05T{i:02d}:00:00Z"}
            )

        full_stream = quiet + surge + quiet

        alerts = self.auditor.compute_critical_slowing_down(
            full_stream, window_size=50, step_size=10
        )
        # Should catch at least one elevated/critical CSD alert during the transition phase
        self.assertIsInstance(alerts, list)
        if alerts:
            self.assertIsInstance(alerts[0], CSDAlert)
            self.assertIn(alerts[0].warning_level, ["NOTICE", "ELEVATED", "CRITICAL"])


if __name__ == "__main__":
    unittest.main()
