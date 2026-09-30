"""
Unit tests for Multiscale Renormalization & Anomalous Diffusion Analysis.
"""

import unittest
import random

from traceform.dynamics.multiscale import (
    MultiscaleDiffusionAuditor,
    DiffusionRegime,
    CoarseGrainedScale,
)


class TestMultiscaleDiffusion(unittest.TestCase):
    def setUp(self):
        self.auditor = MultiscaleDiffusionAuditor(step_duration_seconds=30.0)

    def test_msd_computation(self):
        # 1. Static trajectory
        static_coords = [(1.0, 1.0)] * 100
        msd_static = self.auditor.compute_msd(static_coords, lags=[1, 2, 4])
        for lag, val in msd_static.items():
            self.assertAlmostEqual(val, 0.0)

        # 2. Linear ballistic movement (u(t) = t, v(t) = 0)
        # ||r(t+tau) - r(t)||^2 = tau^2
        linear_coords = [(float(t), 0.0) for t in range(50)]
        msd_linear = self.auditor.compute_msd(linear_coords, lags=[1, 2, 3])
        self.assertAlmostEqual(msd_linear[1], 1.0, places=2)
        self.assertAlmostEqual(msd_linear[2], 4.0, places=2)
        self.assertAlmostEqual(msd_linear[3], 9.0, places=2)

    def test_hurst_exponent_regimes(self):
        # Ballistic motion: MSD ~ tau^2 => ln(MSD) ~ 2 ln(tau) => slope=2 => H=1.0 (SUPER_DIFFUSIVE)
        ballistic_msd = {1: 1.0, 2: 4.0, 4: 16.0, 8: 64.0, 16: 256.0}
        regime_b = self.auditor.estimate_hurst_exponent(ballistic_msd)
        self.assertEqual(regime_b.regime_type, "SUPER_DIFFUSIVE")
        self.assertGreater(regime_b.hurst_exponent, 0.8)
        self.assertGreater(regime_b.r_squared, 0.98)

        # Bounded confinement: MSD saturates / constant => slope ~ 0 => H < 0.2 (SUB_DIFFUSIVE)
        sub_msd = {1: 1.0, 2: 1.1, 4: 1.15, 8: 1.18, 16: 1.20}
        regime_sub = self.auditor.estimate_hurst_exponent(sub_msd)
        self.assertEqual(regime_sub.regime_type, "SUB_DIFFUSIVE")
        self.assertLess(regime_sub.hurst_exponent, 0.45)

    def test_coarse_grained_determinism(self):
        # Strongly periodic deterministic sequence A -> B -> A -> B
        tokens = ["M01", "M02"] * 50
        coords = [(1.0, 1.0), (2.0, 2.0)] * 50
        scales = self.auditor.compute_coarse_grained_determinism(tokens, coords, scales=[1, 2])
        self.assertEqual(len(scales), 2)
        # Scale 1 has high determinism
        self.assertGreater(scales[0].transition_determinism, 0.9)
        self.assertEqual(scales[0].scale_factor, 1)

    def test_audit_multiscale_dynamics(self):
        # Mock records
        rng = random.Random(42)
        records = []
        u, v = 1.0, 1.0
        for i in range(120):
            # Confined random steps around (1, 1)
            u += rng.uniform(-0.1, 0.1)
            v += rng.uniform(-0.1, 0.1)
            records.append({
                "grid_coord": (u, v),
                "state_token": f"M{i%4+1:02d}",
            })

        res = self.auditor.audit_multiscale_dynamics(records, lags=[1, 2, 4, 8])
        self.assertEqual(res["record_count"], 120)
        self.assertIn("diffusion_regime", res)
        self.assertIn("coarse_grained_scales", res)
        self.assertIn("hurst_exponent", res["diffusion_regime"])


if __name__ == "__main__":
    unittest.main()
