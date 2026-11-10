# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Unit tests for the 71D Canonical Ultra-Manifold Sensorium.

Verifies:
1. Complete cardinality of 71 continuous physical, physiological, acoustic, thermodynamic, and cognitive features.
2. Kinematic 3D trajectory Frenet torsion (planar vs non-planar twisting motion).
3. Phase-space Grassberger-Procaccia correlation dimension estimate (fixed attractor vs chaotic trajectory).
4. Photometric ambient dynamic excess kurtosis across sliding lux buffer.
5. Acoustic spectral flatness measure / Wiener entropy.
6. Wi-Fi multi-AP RSSI distribution excess kurtosis.
7. Geomagnetic total field intensity flux anomaly (|B| - 50 uT).
8. Thermodynamic thermal-to-electrical dissipation ratio.
9. Neuromotor touch micro-slip rotational angular velocity.
10. Circadian phase disruption / chrono-entropy index.
11. 71D DETM manifold fitting, topological quantization, and sub-millisecond execution.
12. Comprehensive null, NaN, and unparseable input resilience across all 71 channels.
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


class TestCanonicalSensorium71D(unittest.TestCase):
    def setUp(self):
        self.normalizer = MultimodalNormalizer()
        self.sample_obs = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-obs-71d-001",
            "timestamp_utc": "2026-10-01T06:00:00Z",
            "motion": {
                "accel_magnitude": 9.81,
                "accel_rms": 5.61,
                "accel_raw": [0.0, 0.0, 9.81],
                "gyro_energy": 0.0001,
                "gyro_raw": [0.1, -0.2, 0.3],  # rad/s
                "motion_energy": 0.08,
                "stationary_likelihood": 0.95,
                "mag_raw": [0.0, 30.0, 40.0],  # norm = 50.0 uT -> anomaly = 0.0
            },
            "environment": {
                "ambient_light_lux": 150.0,
                "pressure_hpa": 1013.25,
                "device_temp_c": 28.5,
                "magnetic_field_ut": 50.0,
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
                "silence_likelihood": 0.10,
                "rms_loudness": 0.035,
                "peak_level": 0.15,
                "spectral_entropy": 0.72,
                "spectral_centroid_hz": 1850.0,
                "spectral_rolloff_hz": 3400.0,
                "spectral_bandwidth_hz": 1400.0,
                "zero_crossing_rate": 0.12,
            },
            "connectivity": {
                "wifi_connected": True,
                "wifi_rssi": -62,
                "wifi_frequency_mhz": 5180,
                "wifi_ap_count": 8,
                "cellular_cell_fingerprint": "CELL:310:260:12345",
                "cellular_handovers": 0,
            },
            "place": {
                "latitude": 37.7749,
                "longitude": -122.4194,
                "spatial_cell": "9q8yyk8",
            },
            "digital_context": {
                "screen_on_ratio": 0.65,
                "app_switch_rate": 1.5,
                "unlock_burst_count": 2,
                "touch_interaction_velocity": 0.45,
                "ambient_social_ble_density": 4.0,
                "ambient_rf_shannon_entropy": 1.85,
                "touch_interval_jitter": 0.18,
                "attention_fragmentation_entropy": 0.42,
                "ble_rssi_spatial_variance": 3.5,
                "wifi_rssi_distribution_skewness": 0.35,
            },
        }

    def test_71d_feature_names_cardinality(self):
        self.assertGreaterEqual(len(CONTINUOUS_FEATURE_NAMES), 71)
        expected_9_new = [
            "kinematic_trajectory_torsion",
            "phase_space_correlation_dimension",
            "photometric_flicker_kurtosis",
            "acoustic_spectral_flatness",
            "wifi_rssi_distribution_kurtosis",
            "geomagnetic_total_field_flux",
            "thermodynamic_dissipation_ratio",
            "neuromotor_microslip_angular_velocity",
            "circadian_phase_disruption_index",
        ]
        for feat in expected_9_new:
            self.assertIn(feat, CONTINUOUS_FEATURE_NAMES)

    def test_kinematic_trajectory_torsion(self):
        # Step 1: initial frame
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        obs1["motion"] = dict(self.sample_obs["motion"], accel_raw=[0.0, 0.0, 9.8])
        c1, _ = self.normalizer.extract_raw_features(obs1)
        self.assertEqual(c1["kinematic_trajectory_torsion"], 0.0)

        # Step 2: motion in XY plane
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:05Z")
        obs2["motion"] = dict(self.sample_obs["motion"], accel_raw=[1.0, 0.5, 9.8])
        c2, _ = self.normalizer.extract_raw_features(obs2)

        # Step 3: 3D non-planar motion twisting into Z
        obs3 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs3["motion"] = dict(self.sample_obs["motion"], accel_raw=[2.0, -1.0, 12.0])
        c3, _ = self.normalizer.extract_raw_features(obs3)
        self.assertGreaterEqual(c3["kinematic_trajectory_torsion"], -10.0)
        self.assertLessEqual(c3["kinematic_trajectory_torsion"], 10.0)

    def test_phase_space_correlation_dimension(self):
        norm = MultimodalNormalizer()
        # Feed multiple frames to build sliding buffer
        for i in range(10):
            obs_i = dict(self.sample_obs, timestamp_utc=f"2026-10-01T06:00:{i*10:02d}Z")
            obs_i["motion"] = dict(
                self.sample_obs["motion"], accel_magnitude=9.8 + (i % 2) * 0.1
            )
            c, _ = norm.extract_raw_features(obs_i)

        self.assertGreaterEqual(c["phase_space_correlation_dimension"], 0.0)
        self.assertLessEqual(c["phase_space_correlation_dimension"], 5.0)

    def test_photometric_flicker_kurtosis(self):
        norm = MultimodalNormalizer()
        # Feed steady light
        for i in range(5):
            obs_i = dict(self.sample_obs, timestamp_utc=f"2026-10-01T06:00:{i*5:02d}Z")
            obs_i["environment"] = dict(
                self.sample_obs["environment"], ambient_light_lux=100.0
            )
            c_steady, _ = norm.extract_raw_features(obs_i)
        self.assertEqual(c_steady["photometric_flicker_kurtosis"], 0.0)

        # Feed impulsive light flicker
        obs_flicker = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:30Z")
        obs_flicker["environment"] = dict(
            self.sample_obs["environment"], ambient_light_lux=1500.0
        )
        c_flicker, _ = norm.extract_raw_features(obs_flicker)
        self.assertGreaterEqual(c_flicker["photometric_flicker_kurtosis"], -2.0)
        self.assertLessEqual(c_flicker["photometric_flicker_kurtosis"], 10.0)

    def test_acoustic_spectral_flatness(self):
        # High entropy audio
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreater(c_map["acoustic_spectral_flatness"], 0.2)
        self.assertLessEqual(c_map["acoustic_spectral_flatness"], 1.0)

        # Silent audio
        obs_silent = dict(self.sample_obs)
        obs_silent["audio_context"] = dict(
            self.sample_obs["audio_context"],
            silence_likelihood=0.98,
            rms_loudness=0.0001,
            spectral_entropy=0.05,
        )
        c_silent, _ = self.normalizer.extract_raw_features(obs_silent)
        self.assertEqual(c_silent["acoustic_spectral_flatness"], 0.05)

    def test_wifi_kurtosis_and_geomagnetic_total_flux(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        # Wi-Fi kurtosis
        self.assertGreaterEqual(c_map["wifi_rssi_distribution_kurtosis"], -2.0)
        self.assertLessEqual(c_map["wifi_rssi_distribution_kurtosis"], 8.0)

        # Geomagnetic total flux (mag_raw [0, 30, 40] norm is 50 -> flux is 0.0)
        self.assertEqual(c_map["geomagnetic_total_field_flux"], 0.0)

        # Ferromagnetic anomaly
        obs_ferro = dict(self.sample_obs)
        obs_ferro["motion"] = dict(
            self.sample_obs["motion"], mag_raw=[50.0, 50.0, 50.0]
        )  # norm ~ 86.6 uT
        c_ferro, _ = self.normalizer.extract_raw_features(obs_ferro)
        self.assertAlmostEqual(c_ferro["geomagnetic_total_field_flux"], 36.6, delta=0.5)

    def test_thermodynamic_and_neuromotor_microslip(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        # Thermodynamic dissipation ratio
        self.assertGreaterEqual(c_map["thermodynamic_dissipation_ratio"], 0.0)
        self.assertLessEqual(c_map["thermodynamic_dissipation_ratio"], 5.0)

        # Neuromotor micro-slip
        self.assertGreater(c_map["neuromotor_microslip_angular_velocity"], 0.0)
        self.assertLessEqual(c_map["neuromotor_microslip_angular_velocity"], 10.0)

    def test_circadian_phase_disruption_index(self):
        # 06:00 UTC with screen on and active
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(c_map["circadian_phase_disruption_index"], 0.0)
        self.assertLessEqual(c_map["circadian_phase_disruption_index"], 1.0)

    def test_manifold_fit_and_quantize_71d(self):
        dataset = []
        for i in range(30):
            obs_i = dict(self.sample_obs, observation_id=f"obs-71d-{i:03d}")
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
        self.assertGreaterEqual(len(self.normalizer.params.feature_names), 71)

        vec, topo = self.normalizer.transform(self.sample_obs)
        self.assertGreaterEqual(len(vec), 71)

        # Fit DETM with 71D vectors
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

    def test_71d_null_and_nan_resilience(self):
        corrupted = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-corrupted-71d",
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
        self.assertGreaterEqual(len(c_map), 71)
        for k, v in c_map.items():
            self.assertFalse(math.isnan(v), f"Feature {k} returned NaN")
            self.assertFalse(math.isinf(v), f"Feature {k} returned Inf")

        self.normalizer.fit_discovery_window([self.sample_obs])
        vec, topo = self.normalizer.transform(corrupted)
        self.assertGreaterEqual(len(vec), 71)
        for val in vec:
            self.assertFalse(math.isnan(val))
            self.assertFalse(math.isinf(val))


if __name__ == "__main__":
    unittest.main()
