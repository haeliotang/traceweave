# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Tests for Candidate Semantic Inference Engine.
"""

import unittest
from traceform.semantics.projector import (
    CandidateSemanticProjector,
    PhysicalObservableProfile,
)


class TestCandidateSemanticProjector(unittest.TestCase):
    def setUp(self):
        self.projector = CandidateSemanticProjector()

    def test_synthesize_hypothesis_nocturnal_rest(self):
        hyp, conf = self.projector._synthesize_hypothesis(
            stat_ratio=0.95,
            ac_ratio=0.80,
            usb_ratio=0.0,
            unplug_ratio=0.20,
            dark_ratio=0.85,
            night_ratio=0.80,
            day_ratio=0.20,
            wifi_conc=0.95,
            mean_energy=0.01,
        )
        self.assertIn("Nocturnal Rest", hyp)
        self.assertGreaterEqual(conf, 0.8)

    def test_synthesize_hypothesis_diurnal_work(self):
        hyp, conf = self.projector._synthesize_hypothesis(
            stat_ratio=0.90,
            ac_ratio=0.0,
            usb_ratio=0.0,
            unplug_ratio=0.95,
            dark_ratio=0.05,
            night_ratio=0.10,
            day_ratio=0.90,
            wifi_conc=0.85,
            mean_energy=0.02,
        )
        self.assertIn("Diurnal Stationary Indoor Activity", hyp)
        self.assertGreaterEqual(conf, 0.75)

    def test_synthesize_hypothesis_locomotion(self):
        hyp, conf = self.projector._synthesize_hypothesis(
            stat_ratio=0.30,
            ac_ratio=0.0,
            usb_ratio=0.0,
            unplug_ratio=1.0,
            dark_ratio=0.0,
            night_ratio=0.10,
            day_ratio=0.90,
            wifi_conc=0.20,
            mean_energy=0.45,
        )
        self.assertIn("Locomotion", hyp)
        self.assertGreaterEqual(conf, 0.7)

    def test_compute_state_physical_profile(self):
        raw_map = {
            "obs-1": {
                "motion": {
                    "stationary_likelihood": 0.95,
                    "motion_energy": 0.01,
                    "accel_rms": 0.05,
                },
                "environment": {"ambient_light_lux": 0.5, "pressure_hpa": 1012.0},
                "device_relation": {
                    "plugged": "AC",
                    "status": "CHARGING",
                    "battery_percentage": 90,
                    "battery_temp_c": 28.0,
                },
                "connectivity": {"wifi_fingerprint": "ap_alpha_123"},
                "timestamp_utc": "2026-09-03T02:00:00Z",
            },
            "obs-2": {
                "motion": {
                    "stationary_likelihood": 0.98,
                    "motion_energy": 0.02,
                    "accel_rms": 0.06,
                },
                "environment": {"ambient_light_lux": 0.2, "pressure_hpa": 1012.2},
                "device_relation": {
                    "plugged": "AC",
                    "status": "CHARGING",
                    "battery_percentage": 95,
                    "battery_temp_c": 28.5,
                },
                "connectivity": {"wifi_fingerprint": "ap_alpha_123"},
                "timestamp_utc": "2026-09-03T03:00:00Z",
            },
        }
        records = [
            {"observation_id": "obs-1", "timestamp_utc": "2026-09-03T02:00:00Z"},
            {"observation_id": "obs-2", "timestamp_utc": "2026-09-03T03:00:00Z"},
        ]
        profile = self.projector.compute_state_physical_profile("S01", records, raw_map)
        self.assertIsInstance(profile, PhysicalObservableProfile)
        self.assertEqual(profile.state_token, "S01")
        self.assertEqual(profile.stationary_ratio, 1.0)
        self.assertEqual(profile.plugged_ac_ratio, 1.0)
        self.assertEqual(profile.darkness_ratio, 1.0)
        self.assertEqual(profile.top_wifi_ap, "ap_alpha_1")
        self.assertIn("Nocturnal Rest", profile.candidate_semantic_hypothesis)
        self.assertIn("HYPOTHESIS_ONLY", profile.epistemic_quarantine)

    def test_project_regimes_and_motifs(self):
        prof = PhysicalObservableProfile(
            state_token="S01",
            observation_count=100,
            total_duration_hours=0.83,
            stationary_ratio=1.0,
            mean_motion_energy=0.01,
            mean_accel_rms=0.05,
            median_light_lux=0.5,
            darkness_ratio=1.0,
            mean_pressure_hpa=1012.0,
            plugged_ac_ratio=1.0,
            plugged_usb_ratio=0.0,
            unplugged_ratio=0.0,
            charging_ratio=1.0,
            mean_battery_percentage=90.0,
            mean_battery_temp_c=28.0,
            top_wifi_ap="ap_alpha",
            wifi_concentration_ratio=1.0,
            night_ratio=1.0,
            day_ratio=0.0,
            peak_hours_utc=[2, 3],
            physical_signature="NOCTURNAL_STATIONARY_AC_CHARGING_DARK_RF_ap_alpha",
            candidate_semantic_hypothesis="Prolonged Nocturnal Rest / In-bed Charging (Sleep Anchor Candidate)",
            hypothesis_confidence=0.95,
        )
        state_profiles = {"S01": prof}
        regimes = [
            {
                "regime_token": "R01",
                "start_utc": "2026-09-03T00:00:00Z",
                "end_utc": "2026-09-03T06:00:00Z",
                "duration_frames": 720,
                "dominant_states": [["S01", 0.95]],
            }
        ]
        motifs = [
            {
                "motif_token": "M01",
                "pattern": ["S01", "S01", "S01"],
                "support": 15,
            }
        ]
        proj_regimes = self.projector.project_macro_regimes(regimes, state_profiles)
        self.assertEqual(len(proj_regimes), 1)
        self.assertIn(
            "Sustained Prolonged Nocturnal Rest",
            proj_regimes[0]["candidate_regime_interpretation"],
        )

        proj_motifs = self.projector.project_motifs(motifs, state_profiles)
        self.assertEqual(len(proj_motifs), 1)
        self.assertIn("S01", proj_motifs[0]["candidate_sequence_interpretation"])


if __name__ == "__main__":
    unittest.main()
