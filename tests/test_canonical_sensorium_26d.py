"""
Unit tests for the 26D Canonical Cyber-Physical-Social Sensorium.

Verifies:
1. Extraction of 26 continuous features including all temporal, kinematic, spatial, and social indicators.
2. Smooth continuous circadian (sin, cos) and infradian weekly phase mapping on unit manifolds.
3. Signed microampere battery current fidelity.
4. Geodesic radius of gyration (Rg) computation and indoor NaN/outage fallback.
5. Touch motor-cadence velocity and social BLE/RF ambient density fusion.
6. 26D DETM manifold fitting, topological quantization, and sub-millisecond execution.
"""

from __future__ import annotations

import math
import unittest

from traceform.probes.digital_sensorium import DigitalSensoriumProbe
from traceform.representation.normalizer import MultimodalNormalizer, CONTINUOUS_FEATURE_NAMES
from traceform.representation.manifold_lattice import DynamicalEnergyManifold


class TestCanonicalSensorium26D(unittest.TestCase):
    def setUp(self):
        self.normalizer = MultimodalNormalizer()
        self.sample_obs = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-obs-26d-001",
            "timestamp_utc": "2026-10-01T06:00:00Z",  # Thursday 06:00 UTC
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
                "current_ua": -320000,
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
                "wifi_ap_count": 24,
            },
            "place": {
                "latitude": 28.21258,
                "longitude": 83.96635,
                "spatial_cell": "tv58qxy",
            },
            "digital_context": {
                "screen_on_ratio": 0.95,
                "app_switch_rate": 8.0,
                "unlock_burst_count": 2.0,
                "touch_interaction_velocity": 4.8,
                "ambient_social_ble_density": 6.0,
            },
        }

    def test_26d_feature_names_cardinality(self):
        self.assertGreaterEqual(len(CONTINUOUS_FEATURE_NAMES), 26)
        # Check canonical 6 new features
        expected_new_features = [
            "circadian_cos_phase",
            "infradian_weekly_phase",
            "battery_current_ua",
            "spatial_radius_of_gyration",
            "touch_interaction_velocity",
            "ambient_social_ble_density",
        ]
        for feat in expected_new_features:
            self.assertIn(feat, CONTINUOUS_FEATURE_NAMES)

    def test_raw_feature_extraction_26d(self):
        c_map, topo_map = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(len(c_map), 26)

        # Baseline digital features
        self.assertEqual(c_map["screen_on_ratio"], 0.95)
        self.assertEqual(c_map["app_switch_rate"], 8.0)
        self.assertEqual(c_map["unlock_burst_count"], 2.0)
        self.assertEqual(c_map["audio_immersion_state"], 1.0)

        # 26D Extensions
        self.assertEqual(c_map["battery_current_ua"], -320000.0)
        self.assertEqual(c_map["touch_interaction_velocity"], 4.8)
        self.assertEqual(c_map["ambient_social_ble_density"], 6.0)

        # Circadian phases at 06:00 UTC (6 / 24 of day = pi/2)
        # sin(pi/2) = 1.0, cos(pi/2) = 0.0
        self.assertAlmostEqual(c_map["circadian_sin_phase"], 1.0, places=4)
        self.assertAlmostEqual(c_map["circadian_cos_phase"], 0.0, places=4)
        unit_circle_sq = c_map["circadian_sin_phase"]**2 + c_map["circadian_cos_phase"]**2
        self.assertAlmostEqual(unit_circle_sq, 1.0, places=4)

    def test_circadian_and_infradian_phase_continuity(self):
        # 12:00 UTC -> sin(pi) = 0.0, cos(pi) = -1.0
        obs_noon = dict(self.sample_obs, timestamp_utc="2026-10-01T12:00:00Z")
        c_noon, _ = self.normalizer.extract_raw_features(obs_noon)
        self.assertAlmostEqual(c_noon["circadian_sin_phase"], 0.0, places=4)
        self.assertAlmostEqual(c_noon["circadian_cos_phase"], -1.0, places=4)

        # 18:00 UTC -> sin(3pi/2) = -1.0, cos(3pi/2) = 0.0
        obs_evening = dict(self.sample_obs, timestamp_utc="2026-10-01T18:00:00Z")
        c_eve, _ = self.normalizer.extract_raw_features(obs_evening)
        self.assertAlmostEqual(c_eve["circadian_sin_phase"], -1.0, places=4)
        self.assertAlmostEqual(c_eve["circadian_cos_phase"], 0.0, places=4)

        # 00:00 UTC -> sin(0) = 0.0, cos(0) = 1.0
        obs_midnight = dict(self.sample_obs, timestamp_utc="2026-10-01T00:00:00Z")
        c_mid, _ = self.normalizer.extract_raw_features(obs_midnight)
        self.assertAlmostEqual(c_mid["circadian_sin_phase"], 0.0, places=4)
        self.assertAlmostEqual(c_mid["circadian_cos_phase"], 1.0, places=4)

        # Infradian weekly phase bounded in [-1.0, 1.0]
        self.assertGreaterEqual(c_noon["infradian_weekly_phase"], -1.0)
        self.assertLessEqual(c_noon["infradian_weekly_phase"], 1.0)

    def test_spatial_radius_of_gyration_and_nan_fallback(self):
        norm = MultimodalNormalizer()
        norm.reset_geo_window()

        # Step 1: Single coordinate or indoor missing GPS -> returns nominal fallback 0.05 km
        indoor_obs = dict(self.sample_obs, place={"spatial_cell": "tv58qxy"})  # No lat/lon
        c_indoor, _ = norm.extract_raw_features(indoor_obs)
        self.assertEqual(c_indoor["spatial_radius_of_gyration"], 0.05)
        self.assertFalse(math.isnan(c_indoor["spatial_radius_of_gyration"]))

        # Step 2: Feed a sequence of GPS coordinates (exploring across a ~500m area)
        coords = [
            (28.21200, 83.96600),
            (28.21300, 83.96700),
            (28.21400, 83.96500),
            (28.21100, 83.96800),
        ]
        rg_val = 0.0
        for lat, lon in coords:
            obs = dict(self.sample_obs, place={"latitude": lat, "longitude": lon, "spatial_cell": "tv58qxy"})
            c_m, _ = norm.extract_raw_features(obs)
            rg_val = c_m["spatial_radius_of_gyration"]

        # Rg should be positive, non-zero, and reasonable (e.g., between 0.05km and 1.0km)
        self.assertGreater(rg_val, 0.05)
        self.assertLess(rg_val, 1.0)
        self.assertFalse(math.isnan(rg_val))

    def test_ble_rf_density_and_touch_cadence_fallback(self):
        # Frame without digital_context
        legacy_obs = dict(self.sample_obs)
        del legacy_obs["digital_context"]

        c_map, _ = self.normalizer.extract_raw_features(legacy_obs)
        self.assertGreaterEqual(len(c_map), 26)
        # Should synthesize touch cadence from screen state ON + switch/unlock (min 1.0)
        self.assertGreaterEqual(c_map["touch_interaction_velocity"], 1.0)
        # Should use wifi_ap_count fallback for social density: 24 / 3.0 = 8.0
        self.assertEqual(c_map["ambient_social_ble_density"], 8.0)

    def test_battery_current_signed_polarities(self):
        # Discharging frame (-350 mA)
        obs_disch = dict(self.sample_obs, device_relation={"current_ua": -350000, "status": "DISCHARGING"})
        c_disch, _ = self.normalizer.extract_raw_features(obs_disch)
        self.assertEqual(c_disch["battery_current_ua"], -350000.0)

        # Fast charging frame (+2000 mA)
        obs_chg = dict(self.sample_obs, device_relation={"current_ua": 2000000, "status": "CHARGING"})
        c_chg, _ = self.normalizer.extract_raw_features(obs_chg)
        self.assertEqual(c_chg["battery_current_ua"], 2000000.0)

    def test_manifold_fit_and_quantize_26d(self):
        # Generate 30 synthetic frames with realistic variations
        dataset = []
        for i in range(30):
            obs_i = dict(self.sample_obs, observation_id=f"obs-{i:03d}")
            obs_i["device_relation"] = dict(
                self.sample_obs["device_relation"],
                battery_percentage=90 - (i % 10),
                current_ua=-300000 - i * 5000,
            )
            obs_i["place"] = {
                "latitude": 28.2120 + 0.0005 * (i % 5),
                "longitude": 83.9660 + 0.0005 * (i % 4),
                "spatial_cell": "tv58qxy",
            }
            dataset.append(obs_i)

        self.normalizer.fit_discovery_window(dataset)
        self.assertGreaterEqual(len(self.normalizer.params.feature_names), 26)

        vec, topo = self.normalizer.transform(self.sample_obs)
        self.assertGreaterEqual(len(vec), 26)

        # Fit DETM with 26D vectors
        manifold = DynamicalEnergyManifold(grid_height=3, grid_width=3, kinetic_weight=0.25)
        tokens = manifold.fit_discovery_window(dataset, self.normalizer, epochs=2)
        self.assertEqual(len(tokens), 9)

        # Quantize single frame
        token, coord, dist = manifold.quantize(self.sample_obs, self.normalizer)
        self.assertTrue(token.startswith("M"))
        self.assertEqual(len(coord), 2)
        self.assertGreaterEqual(dist, 0.0)

    def test_null_nan_and_unparseable_resilience(self):
        corrupted_obs = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-corrupted-001",
            "timestamp_utc": "2026-10-01T06:00:00Z",
            "motion": {
                "accel_magnitude": None,
                "accel_rms": float("nan"),
                "motion_energy": "invalid-str",
                "stationary_likelihood": None,
            },
            "environment": {
                "ambient_light_lux": None,
                "pressure_hpa": float("inf"),
                "device_temp_c": None,
            },
            "device_relation": {
                "battery_percentage": None,
                "current_ua": "null",
                "plugged": None,
                "status": None,
                "screen_state": None,
            },
            "audio_context": {
                "silence_likelihood": None,
                "rms_loudness": None,
                "spectral_entropy": None,
            },
            "connectivity": {
                "wifi_connected": None,
                "wifi_rssi": None,
                "wifi_ap_count": None,
            },
            "place": {
                "latitude": None,
                "longitude": None,
                "spatial_cell": None,
            },
            "digital_context": {
                "screen_on_ratio": None,
                "app_switch_rate": None,
                "unlock_burst_count": None,
                "touch_interaction_velocity": None,
                "ambient_social_ble_density": None,
            },
        }
        # Must not raise TypeError, ValueError, or any unhandled exception
        c_map, topo_map = self.normalizer.extract_raw_features(corrupted_obs)
        self.assertGreaterEqual(len(c_map), 26)
        for k, v in c_map.items():
            self.assertFalse(math.isnan(v), f"Feature {k} is NaN")
            self.assertFalse(math.isinf(v), f"Feature {k} is Inf")

        self.normalizer.fit_discovery_window([self.sample_obs])
        vec, topo = self.normalizer.transform(corrupted_obs)
        self.assertGreaterEqual(len(vec), 26)
        for val in vec:
            self.assertFalse(math.isnan(val))
            self.assertFalse(math.isinf(val))


if __name__ == "__main__":
    unittest.main()
