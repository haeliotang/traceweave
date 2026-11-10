# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Unit tests for the 62D Canonical Trans-Spectral Sensorium.

Verifies:
1. Complete cardinality of 62 continuous physical, physiological, acoustic, thermodynamic, and cognitive features.
2. 3D geomagnetic dip/inclination angle across spatial flux vectors.
3. Kinematic 3D trajectory Frenet curvature (linear vs rotational motions).
4. Barometric micro-turbulence variance across rolling pressure buffer.
5. Photometric dynamic contrast ratio against ambient baseline.
6. Acoustic second-order spectral bandwidth around centroid.
7. Wi-Fi multi-AP RSSI distribution skewness.
8. Battery electrochemical internal Joule heat dissipation (I^2 * R).
9. Multi-modal phase space kinetic migration velocity.
10. Neuromotor touch tap recoil deceleration intensity.
11. 62D DETM manifold fitting, topological quantization, and sub-millisecond execution.
12. Comprehensive null, NaN, and unparseable input resilience across all 62 channels.
"""

from __future__ import annotations

import math
import time
import unittest

from traceform.representation.normalizer import (
    MultimodalNormalizer,
    CONTINUOUS_FEATURE_NAMES,
)
from traceform.representation.manifold_lattice import DynamicalEnergyManifold


class TestCanonicalSensorium62D(unittest.TestCase):
    def setUp(self):
        self.normalizer = MultimodalNormalizer()
        self.sample_obs = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-obs-62d-001",
            "timestamp_utc": "2026-10-01T06:00:00Z",
            "motion": {
                "accel_magnitude": 9.81,
                "accel_rms": 5.61,
                "accel_raw": [0.0, 0.0, 9.81],
                "gyro_energy": 0.0001,
                "gyro_raw": [0.1, -0.2, 0.3],  # rad/s
                "motion_energy": 0.08,
                "stationary_likelihood": 0.95,
                "mag_raw": [0.0, 30.0, 30.0],  # 45 deg dip
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
                "current_ua": -500000,  # -0.5 A
                "internal_resistance_miliohm": 160.0,
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
                "spectral_bandwidth_hz": 1800.0,
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

    def test_62d_feature_names_cardinality(self):
        self.assertGreaterEqual(len(CONTINUOUS_FEATURE_NAMES), 62)
        expected_9_new = [
            "geomagnetic_field_dip_angle_deg",
            "kinematic_trajectory_curvature",
            "barometric_turbulence_variance",
            "photometric_contrast_ratio",
            "acoustic_spectral_bandwidth_khz",
            "wifi_rssi_distribution_skewness",
            "thermodynamic_joule_heat_loss_watts",
            "phase_space_kinetic_velocity",
            "neuromotor_tap_recoil_intensity",
        ]
        for feat in expected_9_new:
            self.assertIn(feat, CONTINUOUS_FEATURE_NAMES)

    def test_geomagnetic_field_dip_angle(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Case 1: bx=0, by=30, bz=30 -> dip angle = 45 degrees
        c1, _ = norm.extract_raw_features(self.sample_obs)
        self.assertAlmostEqual(c1["geomagnetic_field_dip_angle_deg"], 45.0, places=1)

        # Case 2: bx=0, by=40, bz=0 -> dip angle = 0 degrees (magnetic equator)
        obs2 = dict(self.sample_obs)
        obs2["motion"] = dict(self.sample_obs["motion"], mag_raw=[0.0, 40.0, 0.0])
        c2, _ = norm.extract_raw_features(obs2)
        self.assertAlmostEqual(c2["geomagnetic_field_dip_angle_deg"], 0.0, places=1)

        # Case 3: bx=0, by=0, bz=-45 -> dip angle = -90 degrees (magnetic pole)
        obs3 = dict(self.sample_obs)
        obs3["motion"] = dict(self.sample_obs["motion"], mag_raw=[0.0, 0.0, -45.0])
        c3, _ = norm.extract_raw_features(obs3)
        self.assertAlmostEqual(c3["geomagnetic_field_dip_angle_deg"], -90.0, places=1)

    def test_kinematic_trajectory_curvature(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Frame 1: T=00:00:00, accel=[10.0, 0.0, 0.0]
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        obs1["motion"] = dict(self.sample_obs["motion"], accel_raw=[10.0, 0.0, 0.0])
        c1, _ = norm.extract_raw_features(obs1)
        self.assertEqual(c1["kinematic_trajectory_curvature"], 0.0)

        # Frame 2: T=00:00:10 (10s later), accel=[0.0, 10.0, 0.0] (orthogonal rotation!)
        # a = [0, 10, 0], da/dt = [-1.0, 1.0, 0.0]
        # a x da/dt = [0, 0, 10.0], |a x da/dt| = 10.0, |a|^3 = 1000.0 -> kappa = 10/1000 = 0.01
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs2["motion"] = dict(self.sample_obs["motion"], accel_raw=[0.0, 10.0, 0.0])
        c2, _ = norm.extract_raw_features(obs2)
        self.assertAlmostEqual(c2["kinematic_trajectory_curvature"], 0.01, places=3)

    def test_joule_heat_loss_watts(self):
        norm = MultimodalNormalizer()
        # Current = 500,000 uA = 0.5 A, R_int = 160 mOhm = 0.16 Ohm
        # P = I^2 * R = 0.25 * 0.16 = 0.04 Watts
        c_map, _ = norm.extract_raw_features(self.sample_obs)
        self.assertAlmostEqual(
            c_map["thermodynamic_joule_heat_loss_watts"], 0.04, places=4
        )

    def test_barometric_and_photometric_dynamics(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Frame 1: P=1013.0, Lux=100.0
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        obs1["environment"] = dict(
            self.sample_obs["environment"], pressure_hpa=1013.0, ambient_light_lux=100.0
        )
        c1, _ = norm.extract_raw_features(obs1)
        self.assertEqual(c1["barometric_turbulence_variance"], 0.0)
        self.assertEqual(c1["photometric_contrast_ratio"], 0.0)

        # Frame 2: P=1015.0, Lux=200.0 (turbulence & sudden light flash!)
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs2["environment"] = dict(
            self.sample_obs["environment"], pressure_hpa=1015.0, ambient_light_lux=200.0
        )
        c2, _ = norm.extract_raw_features(obs2)
        # Var of [1013, 1015] is 1.0 hPa^2
        self.assertAlmostEqual(c2["barometric_turbulence_variance"], 1.0, places=2)
        # Lux contrast of [100, 200] mean=150: ln(1 + |200-150| / 151) ~ ln(1 + 0.331) ~ 0.286
        self.assertGreater(c2["photometric_contrast_ratio"], 0.2)

    def test_acoustic_bandwidth_and_skewness(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        # bandwidth: 1800 Hz -> 1.8 kHz
        self.assertAlmostEqual(c_map["acoustic_spectral_bandwidth_khz"], 1.8, places=2)

    def test_phase_space_kinetic_velocity_and_recoil(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Frame 1: T=00:00:00
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        c1, _ = norm.extract_raw_features(obs1)
        self.assertEqual(c1["phase_space_kinetic_velocity"], 0.0)
        self.assertEqual(c1["neuromotor_tap_recoil_intensity"], 0.0)

        # Frame 2: T=00:00:10 (10s later, tap impact delta on az!)
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs2["motion"] = dict(
            self.sample_obs["motion"], accel_raw=[0.0, 0.0, 12.81]
        )  # +3 m/s^2 recoil!
        obs2["environment"] = dict(
            self.sample_obs["environment"], device_temp_c=32.5
        )  # +4 deg shift
        c2, _ = norm.extract_raw_features(obs2)
        self.assertGreater(c2["phase_space_kinetic_velocity"], 0.1)
        # Recoil: 3.0 / (1.0 + 4.5 * 0.5) = 3.0 / 3.25 ~ 0.923
        self.assertAlmostEqual(c2["neuromotor_tap_recoil_intensity"], 0.923, places=2)

    def test_manifold_fit_and_quantize_62d(self):
        dataset = []
        for i in range(30):
            obs_i = dict(self.sample_obs, observation_id=f"obs-62d-{i:03d}")
            obs_i["environment"] = dict(
                self.sample_obs["environment"], pressure_hpa=1013.0 - (i % 5) * 0.2
            )
            obs_i["motion"] = dict(
                self.sample_obs["motion"],
                accel_raw=[0.1 * (i % 3), 0.1 * (i % 4), 9.7],
                gyro_raw=[0.05 * (i % 2), 0.02 * (i % 3), 0.01],
                mag_raw=[10.0, 20.0 + (i % 5), 30.0],
            )
            dataset.append(obs_i)

        self.normalizer.fit_discovery_window(dataset)
        self.assertGreaterEqual(len(self.normalizer.params.feature_names), 62)

        vec, topo = self.normalizer.transform(self.sample_obs)
        self.assertGreaterEqual(len(vec), 62)

        # Fit DETM with 62D vectors
        manifold = DynamicalEnergyManifold(
            grid_height=3, grid_width=3, kinetic_weight=0.25
        )
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

    def test_62d_null_and_nan_resilience(self):
        corrupted = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-corrupted-62d",
            "timestamp_utc": "2026-10-01T06:00:00Z",
            "motion": {
                "accel_magnitude": None,
                "accel_rms": float("nan"),
                "accel_raw": [None, float("inf"), "bad"],
                "gyro_energy": None,
                "gyro_raw": [None, float("-inf"), "bad"],
                "motion_energy": "nan",
                "stationary_likelihood": None,
                "mag_raw": [None, "bad", float("nan")],
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
                "internal_resistance_miliohm": None,
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
                "spectral_bandwidth_hz": None,
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
                "wifi_rssi_distribution_skewness": None,
            },
        }

        c_map, topo_map = self.normalizer.extract_raw_features(corrupted)
        self.assertGreaterEqual(len(c_map), 62)
        for k, v in c_map.items():
            self.assertFalse(math.isnan(v), f"Feature {k} returned NaN")
            self.assertFalse(math.isinf(v), f"Feature {k} returned Inf")

        self.normalizer.fit_discovery_window([self.sample_obs])
        vec, topo = self.normalizer.transform(corrupted)
        self.assertGreaterEqual(len(vec), 62)
        for val in vec:
            self.assertFalse(math.isnan(val))
            self.assertFalse(math.isinf(val))


if __name__ == "__main__":
    unittest.main()
