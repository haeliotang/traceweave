"""
Unit tests for the 20D Canonical Cyber-Physical Sensorium.

Verifies:
1. Extraction of 20 continuous features including digital and circadian indicators.
2. Smooth continuous circadian phase mapping without discontinuity.
3. Audio immersion state detection from wired and bluetooth accessories.
4. Robust fallback behavior for historical logs missing digital fields.
5. DigitalSensoriumProbe graceful degradation in non-Android environments.
"""

import math
import unittest

from traceform.probes.digital_sensorium import DigitalSensoriumProbe
from traceform.representation.normalizer import MultimodalNormalizer, CONTINUOUS_FEATURE_NAMES
from traceform.representation.manifold_lattice import DynamicalEnergyManifold


class TestDigitalSensorium20D(unittest.TestCase):
    def setUp(self):
        self.normalizer = MultimodalNormalizer()
        self.sample_obs = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-obs-20d-001",
            "timestamp_utc": "2026-10-01T06:00:00Z",
            "motion": {
                "accel_magnitude": 9.81,
                "accel_rms": 5.61,
                "motion_energy": 0.05,
                "stationary_likelihood": 0.95,
            },
            "environment": {
                "ambient_light_lux": 150.0,
                "pressure_hpa": 1013.25,
                "device_temp_c": 26.5,
            },
            "device_relation": {
                "battery_percentage": 92,
                "plugged": "UNPLUGGED",
                "status": "DISCHARGING",
                "wired_headset": True,
                "bluetooth_a2dp": False,
                "screen_state": "ON",
            },
            "audio_context": {
                "silence_likelihood": 0.8,
                "rms_loudness": 0.03,
                "spectral_entropy": 0.15,
            },
            "connectivity": {
                "wifi_connected": True,
                "wifi_rssi": -48,
            },
            "digital_context": {
                "screen_on_ratio": 0.95,
                "app_switch_rate": 8.0,
                "unlock_burst_count": 2.0,
            },
        }

    def test_20d_feature_names_cardinality(self):
        self.assertEqual(len(CONTINUOUS_FEATURE_NAMES), 20)
        self.assertIn("screen_on_ratio", CONTINUOUS_FEATURE_NAMES)
        self.assertIn("app_switch_rate", CONTINUOUS_FEATURE_NAMES)
        self.assertIn("unlock_burst_count", CONTINUOUS_FEATURE_NAMES)
        self.assertIn("audio_immersion_state", CONTINUOUS_FEATURE_NAMES)
        self.assertIn("circadian_sin_phase", CONTINUOUS_FEATURE_NAMES)

    def test_raw_feature_extraction(self):
        c_map, topo_map = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertEqual(len(c_map), 20)
        self.assertEqual(c_map["screen_on_ratio"], 0.95)
        self.assertEqual(c_map["app_switch_rate"], 8.0)
        self.assertEqual(c_map["unlock_burst_count"], 2.0)
        self.assertEqual(c_map["audio_immersion_state"], 1.0)
        # At 06:00 UTC (6 / 24 of day = pi/2), sin(pi/2) = 1.0
        self.assertAlmostEqual(c_map["circadian_sin_phase"], 1.0, places=4)

    def test_circadian_phase_continuity(self):
        # 12:00 UTC -> sin(pi) = 0.0
        obs_noon = dict(self.sample_obs, timestamp_utc="2026-10-01T12:00:00Z")
        c_noon, _ = self.normalizer.extract_raw_features(obs_noon)
        self.assertAlmostEqual(c_noon["circadian_sin_phase"], 0.0, places=4)

        # 18:00 UTC -> sin(3pi/2) = -1.0
        obs_evening = dict(self.sample_obs, timestamp_utc="2026-10-01T18:00:00Z")
        c_eve, _ = self.normalizer.extract_raw_features(obs_evening)
        self.assertAlmostEqual(c_eve["circadian_sin_phase"], -1.0, places=4)

        # 00:00 UTC -> sin(0) = 0.0
        obs_midnight = dict(self.sample_obs, timestamp_utc="2026-10-01T00:00:00Z")
        c_mid, _ = self.normalizer.extract_raw_features(obs_midnight)
        self.assertAlmostEqual(c_mid["circadian_sin_phase"], 0.0, places=4)

    def test_backward_compatibility_with_missing_digital_context(self):
        # Emulate older historical frame without digital_context
        legacy_obs = dict(self.sample_obs)
        del legacy_obs["digital_context"]
        del legacy_obs["device_relation"]["screen_state"]

        c_map, _ = self.normalizer.extract_raw_features(legacy_obs)
        self.assertEqual(len(c_map), 20)
        self.assertEqual(c_map["screen_on_ratio"], 0.0)
        self.assertEqual(c_map["app_switch_rate"], 0.0)
        self.assertEqual(c_map["unlock_burst_count"], 0.0)

    def test_manifold_fit_and_quantize_20d(self):
        # Test full SOM fit with 20D inputs
        dataset = [dict(self.sample_obs, observation_id=f"obs-{i}") for i in range(25)]
        self.normalizer.fit_discovery_window(dataset)
        manifold = DynamicalEnergyManifold(grid_height=3, grid_width=3, kinetic_weight=0.25)
        tokens = manifold.fit_discovery_window(dataset, self.normalizer, epochs=2)
        self.assertEqual(len(tokens), 9)

        token, coord, dist = manifold.quantize(self.sample_obs, self.normalizer)
        self.assertTrue(token.startswith("M"))
        self.assertEqual(len(coord), 2)
        self.assertGreaterEqual(dist, 0.0)

    def test_digital_sensorium_probe_fallback(self):
        probe = DigitalSensoriumProbe()
        state = probe.sample_screen_state()
        self.assertIn(state, ["ON", "DOZING", "OFF", "UNKNOWN"])
        metrics = probe.sample_digital_interaction_metrics()
        self.assertIn("screen_on_ratio", metrics)
        self.assertIn("app_switch_rate", metrics)
        self.assertIn("unlock_burst_count", metrics)


if __name__ == "__main__":
    unittest.main()
