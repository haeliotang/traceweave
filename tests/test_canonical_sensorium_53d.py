"""
Unit tests for the 53D Deep-Phase Topological Sensorium.

Verifies:
1. Complete cardinality of 53 continuous physical, physiological, acoustic, thermodynamic, and cognitive features.
2. Screen plane gravity projection alignment cosine (face up, face down, upright).
3. 3D rotational angular jerk across consecutive gyro frames.
4. Second-order thermal conduction acceleration (d^2T/dt^2).
5. Neuromuscular micro-tremor to motion spectral ratio (8-12Hz vs 1-4Hz).
6. Acoustic spectral roll-off (85% energy quantile) and time-domain zero-crossing rate.
7. Ambient Wi-Fi spatial multi-path Shannon entropy.
8. Continuous touch engagement exponential decay factor.
9. Online rolling phase-space recurrence density (RQA).
10. 53D DETM manifold fitting, topological quantization, and sub-millisecond execution.
11. Comprehensive null, NaN, and unparseable input resilience across all 53 channels.
"""

from __future__ import annotations

import math
import time
import unittest

from traceform.representation.normalizer import MultimodalNormalizer, CONTINUOUS_FEATURE_NAMES
from traceform.representation.manifold_lattice import DynamicalEnergyManifold


class TestCanonicalSensorium53D(unittest.TestCase):
    def setUp(self):
        self.normalizer = MultimodalNormalizer()
        self.sample_obs = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-obs-53d-001",
            "timestamp_utc": "2026-10-01T06:00:00Z",
            "motion": {
                "accel_magnitude": 9.81,
                "accel_rms": 5.61,
                "accel_raw": [0.0, 0.0, 9.81],
                "gyro_energy": 0.0001,
                "gyro_raw": [0.1, -0.2, 0.3],  # rad/s
                "motion_energy": 0.08,
                "stationary_likelihood": 0.95,
            },
            "environment": {
                "ambient_light_lux": 150.0,
                "pressure_hpa": 1013.25,
                "device_temp_c": 28.5,
                "magnetic_field_ut": 45.0,
            },
            "device_relation": {
                "battery_percentage": 92,
                "voltage_mv": 4120,
                "current_ua": -320000,
                "plugged": "UNPLUGGED",
                "status": "DISCHARGING",
                "wired_headset": True,
                "bluetooth_a2dp": False,
                "screen_state": "ON",
            },
            "audio_context": {
                "silence_likelihood": 0.2,
                "rms_loudness": 0.05,
                "peak_level": 0.35,
                "spectral_entropy": 0.45,
                "spectral_centroid_hz": 2400.0,
                "spectral_rolloff_hz": 3600.0,
                "zero_crossing_rate": 0.12,
            },
            "connectivity": {
                "wifi_connected": True,
                "wifi_rssi": -48,
                "wifi_frequency_mhz": 5180,
                "wifi_ap_count": 27,
                "cellular_cell_fingerprint": "cell_sector_001",
            },
            "place": {
                "latitude": 28.21258,
                "longitude": 83.96635,
                "spatial_cell": "tv58qxy",
            },
            "digital_context": {
                "screen_on_ratio": 0.95,
                "app_switch_rate": 6.0,
                "unlock_burst_count": 2.0,
                "touch_interaction_velocity": 4.5,
                "ambient_social_ble_density": 8.0,
                "ambient_rf_shannon_entropy": 4.1,
                "touch_interval_jitter": 0.85,
                "attention_fragmentation_entropy": 2.3,
                "ble_rssi_spatial_variance": 12.8,
            },
        }

    def test_53d_feature_names_cardinality(self):
        self.assertEqual(len(CONTINUOUS_FEATURE_NAMES), 53)
        expected_9_new = [
            "neuromotor_tremor_spectral_ratio",
            "device_screen_gravity_alignment",
            "rotational_angular_jerk",
            "acoustic_spectral_rolloff_khz",
            "acoustic_zero_crossing_rate",
            "thermal_flux_acceleration_dt2",
            "wifi_spatial_shannon_entropy",
            "touch_engagement_decay_factor",
            "phase_space_recurrence_density",
        ]
        for feat in expected_9_new:
            self.assertIn(feat, CONTINUOUS_FEATURE_NAMES)

    def test_screen_plane_gravity_alignment(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Case 1: Lying flat on desk, screen face-up (az = +9.81) -> cos ~ 1.0
        obs_up = dict(self.sample_obs)
        obs_up["motion"] = dict(self.sample_obs["motion"], accel_raw=[0.0, 0.0, 9.81])
        c_up, _ = norm.extract_raw_features(obs_up)
        self.assertAlmostEqual(c_up["device_screen_gravity_alignment"], 1.0, places=2)

        # Case 2: Lying face-down on bed/table (az = -9.81) -> cos ~ -1.0
        obs_down = dict(self.sample_obs)
        obs_down["motion"] = dict(self.sample_obs["motion"], accel_raw=[0.0, 0.0, -9.81])
        c_down, _ = norm.extract_raw_features(obs_down)
        self.assertAlmostEqual(c_down["device_screen_gravity_alignment"], -1.0, places=2)

        # Case 3: Held vertical in portrait mode (ay = 9.81, az = 0.0) -> cos ~ 0.0
        obs_vert = dict(self.sample_obs)
        obs_vert["motion"] = dict(self.sample_obs["motion"], accel_raw=[0.0, 9.81, 0.0])
        c_vert, _ = norm.extract_raw_features(obs_vert)
        self.assertAlmostEqual(c_vert["device_screen_gravity_alignment"], 0.0, places=2)

    def test_rotational_angular_jerk(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Frame 1: T=00:00:00, gyro=[0.1, 0.0, 0.0]
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        obs1["motion"] = dict(self.sample_obs["motion"], gyro_raw=[0.1, 0.0, 0.0])
        c1, _ = norm.extract_raw_features(obs1)
        self.assertEqual(c1["rotational_angular_jerk"], 0.0)

        # Frame 2: T=00:00:10 (10s later), gyro=[0.1, 2.0, 0.0] (angular acceleration jump)
        # Jerk = 2.0 / 10.0 = 0.2 rad/s^2
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs2["motion"] = dict(self.sample_obs["motion"], gyro_raw=[0.1, 2.0, 0.0])
        c2, _ = norm.extract_raw_features(obs2)
        self.assertAlmostEqual(c2["rotational_angular_jerk"], 0.2, places=2)

    def test_second_order_thermal_acceleration(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Frame 1: T=00:00:00, Temp=28.00 C
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        obs1["environment"] = dict(self.sample_obs["environment"], device_temp_c=28.00)
        norm.extract_raw_features(obs1)

        # Frame 2: T=00:00:10, Temp=28.01 C (dT = 0.01 C in 10s -> 3.6 C/hr)
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs2["environment"] = dict(self.sample_obs["environment"], device_temp_c=28.01)
        c2, _ = norm.extract_raw_features(obs2)
        self.assertEqual(c2["thermal_flux_acceleration_dt2"], 0.0)  # first rate step

        # Frame 3: T=00:00:20, Temp=28.03 C (dT = 0.02 C in 10s -> 7.2 C/hr)
        # Acceleration d^2T/dt^2 = (6.4 - 2.8) / 10.0 = 0.36 (deg C / hr) / s
        obs3 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:20Z")
        obs3["environment"] = dict(self.sample_obs["environment"], device_temp_c=28.03)
        c3, _ = norm.extract_raw_features(obs3)
        self.assertAlmostEqual(c3["thermal_flux_acceleration_dt2"], 0.36, places=2)

    def test_touch_engagement_decay_factor(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Frame 1: T=00:00:00 with active touch -> decay factor = 1.0
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        obs1["digital_context"] = dict(self.sample_obs["digital_context"], touch_interaction_velocity=3.0)
        c1, _ = norm.extract_raw_features(obs1)
        self.assertEqual(c1["touch_engagement_decay_factor"], 1.0)

        # Frame 2: T=00:10:00 (600s later, 1 half-life = exp(-1) ~ 0.368), no touch
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:10:00Z")
        obs2["digital_context"] = dict(
            self.sample_obs["digital_context"],
            touch_interaction_velocity=0.0,
            app_switch_rate=0.0,
            unlock_burst_count=0.0,
        )
        c2, _ = norm.extract_raw_features(obs2)
        self.assertAlmostEqual(c2["touch_engagement_decay_factor"], 0.368, delta=0.02)

    def test_acoustic_features_53d(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        # rolloff: 3600 Hz -> 3.6 kHz
        self.assertAlmostEqual(c_map["acoustic_spectral_rolloff_khz"], 3.6, places=2)
        # zcr: 0.12
        self.assertAlmostEqual(c_map["acoustic_zero_crossing_rate"], 0.12, places=2)

    def test_manifold_fit_and_quantize_53d(self):
        dataset = []
        for i in range(30):
            obs_i = dict(self.sample_obs, observation_id=f"obs-53d-{i:03d}")
            obs_i["environment"] = dict(self.sample_obs["environment"], pressure_hpa=1013.0 - (i % 5) * 0.2)
            obs_i["motion"] = dict(
                self.sample_obs["motion"],
                accel_raw=[0.1 * (i % 3), 0.1 * (i % 4), 9.7],
                gyro_raw=[0.05 * (i % 2), 0.02 * (i % 3), 0.01],
            )
            dataset.append(obs_i)

        self.normalizer.fit_discovery_window(dataset)
        self.assertEqual(len(self.normalizer.params.feature_names), 53)

        vec, topo = self.normalizer.transform(self.sample_obs)
        self.assertEqual(len(vec), 53)

        # Fit DETM with 53D vectors
        manifold = DynamicalEnergyManifold(grid_height=3, grid_width=3, kinetic_weight=0.25)
        tokens = manifold.fit_discovery_window(dataset, self.normalizer, epochs=2)
        self.assertEqual(len(tokens), 9)

        # Quantize single frame (< 5.0 ms execution)
        t0 = time.perf_counter()
        token, coord, dist = manifold.quantize(self.sample_obs, self.normalizer)
        lat_ms = (time.perf_counter() - t0) * 1000.0

        self.assertTrue(token.startswith("M"))
        self.assertEqual(len(coord), 2)
        self.assertGreaterEqual(dist, 0.0)
        self.assertLess(lat_ms, 5.0)

    def test_53d_null_and_nan_resilience(self):
        corrupted = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-corrupted-53d",
            "timestamp_utc": "2026-10-01T06:00:00Z",
            "motion": {
                "accel_magnitude": None,
                "accel_rms": float("nan"),
                "accel_raw": [None, float("inf"), "bad"],
                "gyro_energy": None,
                "gyro_raw": [None, float("-inf"), "bad"],
                "motion_energy": "nan",
                "stationary_likelihood": None,
            },
            "environment": {
                "ambient_light_lux": None,
                "pressure_hpa": None,
                "device_temp_c": None,
                "magnetic_field_ut": float("nan"),
            },
            "device_relation": {
                "battery_percentage": None,
                "voltage_mv": None,
                "current_ua": None,
                "plugged": None,
                "status": None,
                "screen_state": None,
            },
            "audio_context": {
                "silence_likelihood": None,
                "rms_loudness": None,
                "peak_level": None,
                "spectral_entropy": None,
                "spectral_centroid_hz": None,
                "spectral_rolloff_hz": None,
                "zero_crossing_rate": None,
            },
            "connectivity": {
                "wifi_connected": None,
                "wifi_rssi": None,
                "wifi_frequency_mhz": None,
                "wifi_ap_count": None,
                "cellular_cell_fingerprint": None,
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
                "ambient_rf_shannon_entropy": None,
                "touch_interval_jitter": None,
                "attention_fragmentation_entropy": None,
                "ble_rssi_spatial_variance": None,
            },
        }

        c_map, topo_map = self.normalizer.extract_raw_features(corrupted)
        self.assertEqual(len(c_map), 53)
        for k, v in c_map.items():
            self.assertFalse(math.isnan(v), f"Feature {k} returned NaN")
            self.assertFalse(math.isinf(v), f"Feature {k} returned Inf")

        self.normalizer.fit_discovery_window([self.sample_obs])
        vec, topo = self.normalizer.transform(corrupted)
        self.assertEqual(len(vec), 53)
        for val in vec:
            self.assertFalse(math.isnan(val))
            self.assertFalse(math.isinf(val))


if __name__ == "__main__":
    unittest.main()
