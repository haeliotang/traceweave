# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Unit tests for MultimodalNormalizer.
"""

import unittest
from traceform.representation.normalizer import MultimodalNormalizer


class TestMultimodalNormalizer(unittest.TestCase):
    def setUp(self):
        self.mock_obs = [
            {
                "observation_id": f"obs-{i}",
                "timestamp_utc": f"2026-09-03T{i:02d}:00:00Z",
                "monotonic_ns": 1000000000 * i,
                "motion": {
                    "accel_magnitude": 9.8 + (i * 0.1),
                    "accel_rms": 5.6 + (i * 0.05),
                    "motion_energy": i * 0.02,
                    "stationary_likelihood": max(0.0, 1.0 - (i * 0.1)),
                },
                "environment": {
                    "ambient_light_lux": i * 10.0,
                    "pressure_hpa": 915.0 + i,
                    "device_temp_c": 30.0 + (i * 0.5),
                },
                "device_relation": {
                    "battery_percentage": 50 + i,
                    "plugged": "PLUGGED_AC" if i % 2 == 0 else "UNPLUGGED",
                    "status": "CHARGING" if i % 2 == 0 else "DISCHARGING",
                },
                "audio_context": {
                    "silence_likelihood": 0.9,
                    "rms_loudness": 0.01,
                    "spectral_entropy": 0.2,
                },
                "connectivity": {
                    "wifi_connected": True,
                    "wifi_rssi": -50 - i,
                    "wifi_fingerprint": f"wifi_{i % 3}",
                    "cellular_cell_fingerprint": f"cell_{i % 2}",
                },
                "place": {
                    "spatial_cell": "tv58qxy",
                },
            }
            for i in range(10)
        ]

    def test_fit_and_transform(self):
        norm = MultimodalNormalizer()
        params = norm.fit_discovery_window(self.mock_obs, description="Test Window")
        self.assertEqual(params.fitted_observation_count, 10)
        self.assertIn("motion_accel_magnitude", params.medians)
        self.assertIn("env_pressure_hpa", params.iqrs)

        # Test transformation of single frame
        vec, topo = norm.transform(self.mock_obs[0])
        self.assertEqual(len(vec), len(params.feature_names))
        self.assertEqual(topo["spatial_cell"], "tv58qxy")
        self.assertEqual(topo["plugged_state"], "PLUGGED_AC")

    def test_determinism(self):
        norm = MultimodalNormalizer()
        norm.fit_discovery_window(self.mock_obs)
        vec1, topo1 = norm.transform(self.mock_obs[3])
        vec2, topo2 = norm.transform(self.mock_obs[3])
        self.assertEqual(vec1, vec2)
        self.assertEqual(topo1, topo2)


if __name__ == "__main__":
    unittest.main()
