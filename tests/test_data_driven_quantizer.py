# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import unittest
from typing import List, Dict, Any, Tuple
from traceform.representation.data_driven_quantizer import DataDrivenQuantizer


class MockNormalizer:
    def transform(self, obs: Dict[str, Any]) -> Tuple[List[float], Dict[str, str]]:
        # Creates a dummy 15-dimensional vector for testing
        val = float(obs.get("motion", {}).get("accel_magnitude", 0.0))
        return [val] * 15, {}


class TestDataDrivenQuantizer(unittest.TestCase):

    def setUp(self):
        self.normalizer = MockNormalizer()
        self.quantizer = DataDrivenQuantizer()

        self.observations = [
            {
                "motion": {
                    "accel_magnitude": 1.0,
                    "accel_rms": 0.1,
                    "motion_energy": 0.5,
                    "stationary_likelihood": 0.9,
                },
                "environment": {
                    "ambient_light_lux": 100,
                    "pressure_hpa": 1013,
                    "device_temp_c": 30,
                },
                "device_relation": {
                    "battery_percentage": 100,
                    "plugged": True,
                    "status": "charging",
                },
                "audio_context": {
                    "status": "silent",
                    "silence_likelihood": 0.9,
                    "rms_loudness": 0.1,
                    "spectral_entropy": 0.5,
                },
                "connectivity": {
                    "wifi_connected": True,
                    "wifi_rssi": -50,
                    "wifi_fingerprint": "home",
                    "cellular_cell_fingerprint": "cell1",
                },
                "place": {"spatial_cell": "c1"},
            }
            for _ in range(10)
        ] + [
            {
                "motion": {
                    "accel_magnitude": 5.0,
                    "accel_rms": 2.1,
                    "motion_energy": 4.5,
                    "stationary_likelihood": 0.1,
                },
                "environment": {
                    "ambient_light_lux": 500,
                    "pressure_hpa": 1010,
                    "device_temp_c": 35,
                },
                "device_relation": {
                    "battery_percentage": 50,
                    "plugged": False,
                    "status": "discharging",
                },
                "audio_context": {
                    "status": "noisy",
                    "silence_likelihood": 0.1,
                    "rms_loudness": 0.8,
                    "spectral_entropy": 2.5,
                },
                "connectivity": {
                    "wifi_connected": False,
                    "wifi_rssi": -80,
                    "wifi_fingerprint": "work",
                    "cellular_cell_fingerprint": "cell2",
                },
                "place": {"spatial_cell": "c2"},
            }
            for _ in range(15)
        ]

    def test_fit_discovery_window(self):
        self.quantizer.fit_discovery_window(
            self.observations, self.normalizer, n_clusters=2
        )
        self.assertEqual(len(self.quantizer.centroids), 2)
        self.assertEqual(len(self.quantizer.state_tokens), 2)
        # S01 should be the one with 15 observations (val=5.0)
        # S02 should be the one with 10 observations (val=1.0)

        # We can check quantization to verify correctness
        state, cid, dist = self.quantizer.quantize(
            self.observations[-1], self.normalizer
        )
        self.assertEqual(state, "S01")

    def test_quantize(self):
        self.quantizer.fit_discovery_window(
            self.observations, self.normalizer, n_clusters=2
        )

        obs_test = {"motion": {"accel_magnitude": 4.8}}
        state, cid, dist = self.quantizer.quantize(obs_test, self.normalizer)
        self.assertEqual(state, "S01")

        obs_test2 = {"motion": {"accel_magnitude": 1.2}}
        state2, cid2, dist2 = self.quantizer.quantize(obs_test2, self.normalizer)
        self.assertEqual(state2, "S02")

    def test_serialization(self):
        self.quantizer.fit_discovery_window(
            self.observations, self.normalizer, n_clusters=2
        )

        data = self.quantizer.to_dict()
        new_quantizer = DataDrivenQuantizer.from_dict(data)

        self.assertEqual(self.quantizer.centroids, new_quantizer.centroids)
        self.assertEqual(self.quantizer.state_tokens, new_quantizer.state_tokens)

    def test_auto_clusters(self):
        # With auto clustering, silhouette should be used
        # We need more distinct groups for it to pick >2, or it might just pick 2.
        # Let's just ensure it runs without error and returns reasonable number of clusters.
        obs_expanded = (
            self.observations
            + [{"motion": {"accel_magnitude": 10.0}} for _ in range(5)]
            + [{"motion": {"accel_magnitude": -5.0}} for _ in range(6)]
            + [{"motion": {"accel_magnitude": 20.0}} for _ in range(7)]
        )

        self.quantizer.fit_discovery_window(
            obs_expanded, self.normalizer, n_clusters="auto"
        )
        self.assertTrue(5 <= len(self.quantizer.centroids) <= 40)
        self.assertEqual(
            len(self.quantizer.centroids), len(self.quantizer.state_tokens)
        )


if __name__ == "__main__":
    unittest.main()
