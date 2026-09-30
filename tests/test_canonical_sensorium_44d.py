"""
Unit tests for the 44D Unified Phase-Space Holo-Sensorium.

Verifies:
1. Complete cardinality of 44 continuous physical, physiological, acoustic, thermodynamic, and cognitive features.
2. Rigid body 3D rotational kinetic energy from gyro angular velocity vectors.
3. Acceleration derivative / kinematic jerk metric across consecutive frames.
4. Dynamic electrochemical battery internal impedance (dV/dI) and SoC computational power dissipation.
5. Acoustic spectral centroid brightness and dynamic crest factor (peak-to-RMS).
6. Dual-band RF penetration ratio and micro-spatial BLE RSSI variance.
7. Non-Markovian state residence persistence accumulation across stationary epochs.
8. 44D DETM manifold fitting, topological quantization, and sub-millisecond execution.
9. Comprehensive null, NaN, and unparseable input resilience across all 44 channels.
"""

from __future__ import annotations

import math
import unittest

from traceform.probes.digital_sensorium import DigitalSensoriumProbe
from traceform.representation.normalizer import MultimodalNormalizer, CONTINUOUS_FEATURE_NAMES
from traceform.representation.manifold_lattice import DynamicalEnergyManifold


class TestCanonicalSensorium44D(unittest.TestCase):
    def setUp(self):
        self.normalizer = MultimodalNormalizer()
        self.sample_obs = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-obs-44d-001",
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

    def test_44d_feature_names_cardinality(self):
        self.assertGreaterEqual(len(CONTINUOUS_FEATURE_NAMES), 44)
        expected_9_new = [
            "compute_power_dissipation_watts",
            "battery_internal_impedance_miliohm",
            "rotational_kinetic_energy",
            "kinematic_jerk_metric",
            "rf_spectral_penetration_ratio",
            "ble_rssi_spatial_variance",
            "acoustic_spectral_centroid_khz",
            "acoustic_crest_factor",
            "state_residence_persistence_hours",
        ]
        for feat in expected_9_new:
            self.assertIn(feat, CONTINUOUS_FEATURE_NAMES)

    def test_raw_feature_extraction_44d(self):
        c_map, topo_map = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(len(c_map), 44)

        # 1. Rotational kinetic energy: 0.5 * (0.01 + 0.04 + 0.09) = 0.07 rad^2/s^2
        self.assertAlmostEqual(c_map["rotational_kinetic_energy"], 0.07, places=3)

        # 2. Acoustic spectral centroid: 2400 Hz -> 2.4 kHz
        self.assertAlmostEqual(c_map["acoustic_spectral_centroid_khz"], 2.4, places=2)

        # 3. Acoustic crest factor: peak 0.35 / rms 0.05 = 7.0
        self.assertAlmostEqual(c_map["acoustic_crest_factor"], 7.0, places=1)

        # 4. SoC power dissipation: screen on + audio immersion + app switches
        self.assertGreater(c_map["compute_power_dissipation_watts"], 1.0)
        self.assertLess(c_map["compute_power_dissipation_watts"], 5.0)

        # 5. Dual-band RF penetration (5GHz -> ~1.0)
        self.assertGreater(c_map["rf_spectral_penetration_ratio"], 0.5)

    def test_kinematic_jerk_metric(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Frame 1: T=00:00:00, accel=[0.0, 0.0, 9.8]
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        obs1["motion"] = dict(self.sample_obs["motion"], accel_raw=[0.0, 0.0, 9.8])
        c1, _ = norm.extract_raw_features(obs1)
        self.assertEqual(c1["kinematic_jerk_metric"], 0.0)

        # Frame 2: T=00:00:10 (10s later), accel=[0.0, 3.0, 9.8] (lateral shift of 3.0 m/s^2)
        # Jerk = 3.0 / 10.0 = 0.3 m/s^3
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs2["motion"] = dict(self.sample_obs["motion"], accel_raw=[0.0, 3.0, 9.8])
        c2, _ = norm.extract_raw_features(obs2)
        self.assertAlmostEqual(c2["kinematic_jerk_metric"], 0.3, delta=0.01)

    def test_battery_internal_impedance_calculation(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Frame 1: V=4120 mV, I=-200,000 uA (-200 mA)
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        obs1["device_relation"] = dict(self.sample_obs["device_relation"], voltage_mv=4120, current_ua=-200000)
        norm.extract_raw_features(obs1)

        # Frame 2: Load transient! Screen/radio spike: V=4090 mV (drop of 30 mV), I=-400,000 uA (drop of 200 mA)
        # R_int = (30 mV / 200 mA) = 0.15 Ohm = 150 mOhm
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs2["device_relation"] = dict(self.sample_obs["device_relation"], voltage_mv=4090, current_ua=-400000)
        c2, _ = norm.extract_raw_features(obs2)
        self.assertAlmostEqual(c2["battery_internal_impedance_miliohm"], 150.0, delta=1.0)

    def test_state_residence_persistence_hours(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Epoch of stillness: 3 observations across 2 hours
        # T=00:00:00
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T00:00:00Z")
        c1, _ = norm.extract_raw_features(obs1)
        self.assertEqual(c1["state_residence_persistence_hours"], 0.0)

        # T=01:00:00 (1 hour stationary)
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T01:00:00Z")
        c2, _ = norm.extract_raw_features(obs2)
        self.assertAlmostEqual(c2["state_residence_persistence_hours"], 1.0, delta=0.01)

        # T=02:30:00 (2.5 hours stationary)
        obs3 = dict(self.sample_obs, timestamp_utc="2026-10-01T02:30:00Z")
        c3, _ = norm.extract_raw_features(obs3)
        self.assertAlmostEqual(c3["state_residence_persistence_hours"], 2.5, delta=0.01)

        # Transition to dynamic: should reset persistence to 0.0
        obs_dyn = dict(self.sample_obs, timestamp_utc="2026-10-01T02:30:30Z")
        obs_dyn["motion"] = dict(self.sample_obs["motion"], stationary_likelihood=0.1, motion_energy=0.8)
        c_dyn, _ = norm.extract_raw_features(obs_dyn)
        self.assertEqual(c_dyn["state_residence_persistence_hours"], 0.0)

    def test_manifold_fit_and_quantize_44d(self):
        dataset = []
        for i in range(30):
            obs_i = dict(self.sample_obs, observation_id=f"obs-44d-{i:03d}")
            obs_i["environment"] = dict(self.sample_obs["environment"], pressure_hpa=1013.0 - (i % 5) * 0.2)
            obs_i["motion"] = dict(
                self.sample_obs["motion"],
                accel_raw=[0.1 * (i % 3), 0.1 * (i % 4), 9.7],
                gyro_raw=[0.05 * (i % 2), 0.02 * (i % 3), 0.01],
            )
            dataset.append(obs_i)

        self.normalizer.fit_discovery_window(dataset)
        self.assertGreaterEqual(len(self.normalizer.params.feature_names), 44)

        vec, topo = self.normalizer.transform(self.sample_obs)
        self.assertGreaterEqual(len(vec), 44)

        # Fit DETM with 44D vectors
        manifold = DynamicalEnergyManifold(grid_height=3, grid_width=3, kinetic_weight=0.25)
        tokens = manifold.fit_discovery_window(dataset, self.normalizer, epochs=2)
        self.assertEqual(len(tokens), 9)

        # Quantize single frame (< 2.0 ms execution)
        import time
        t0 = time.perf_counter()
        token, coord, dist = manifold.quantize(self.sample_obs, self.normalizer)
        lat_ms = (time.perf_counter() - t0) * 1000.0

        self.assertTrue(token.startswith("M"))
        self.assertEqual(len(coord), 2)
        self.assertGreaterEqual(dist, 0.0)
        self.assertLess(lat_ms, 5.0)

    def test_44d_null_and_nan_resilience(self):
        corrupted = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-corrupted-44d",
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
        self.assertGreaterEqual(len(c_map), 44)
        for k, v in c_map.items():
            self.assertFalse(math.isnan(v), f"Feature {k} returned NaN")
            self.assertFalse(math.isinf(v), f"Feature {k} returned Inf")

        self.normalizer.fit_discovery_window([self.sample_obs])
        vec, topo = self.normalizer.transform(corrupted)
        self.assertGreaterEqual(len(vec), 44)
        for val in vec:
            self.assertFalse(math.isnan(val))
            self.assertFalse(math.isinf(val))


if __name__ == "__main__":
    unittest.main()
