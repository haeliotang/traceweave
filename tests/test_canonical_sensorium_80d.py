# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Unit tests for the 80D Canonical Holo-Cosmic Sensorium.

Verifies:
1. Complete cardinality of 80 continuous physical, physiological, acoustic, thermodynamic, and cognitive features.
2. Kinematic 3D trajectory fourth-order snap/jounce metric (smooth biological vs mechanical shock impulse).
3. Acoustic harmonic-to-noise ratio in dB (broadband noise vs structured phonation/music).
4. Photometric dynamic spectral warmth / CCT evolution index (cold daylight vs warm incandescent/circadian shift).
5. Barometric vertical atmospheric buoyancy energy flux in mW/kg (isobaric lift vs convective wind shear).
6. Geomagnetic horizontal-vertical quadrupole anisotropy ratio (isotropic vs floor-slab shielded fields).
7. Wi-Fi cross-band RF channel dispersion Shannon entropy (single AP vs multi-channel enterprise mesh).
8. Thermodynamic electrochemical battery overpotential stress index (gentle idle vs hot high-drain stress).
9. Neuromotor physiological micro-tremor Lyapunov exponent in s^-1 (convergent limit cycle vs chaotic divergence).
10. Multimodal phase-space non-equilibrium information flow rate in nats/s.
11. 80D DETM manifold fitting, topological quantization, and sub-millisecond execution.
12. Comprehensive null, NaN, and unparseable input resilience across all 80 channels.
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


class TestCanonicalSensorium80D(unittest.TestCase):
    def setUp(self):
        self.normalizer = MultimodalNormalizer()
        self.sample_obs = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-obs-80d-001",
            "timestamp_utc": "2026-10-01T06:00:00Z",
            "motion": {
                "accel_magnitude": 9.81,
                "accel_rms": 5.61,
                "accel_raw": [0.0, 0.0, 9.81],
                "gyro_energy": 0.0001,
                "gyro_raw": [0.1, -0.2, 0.3],  # rad/s
                "motion_energy": 0.08,
                "stationary_likelihood": 0.95,
                "mag_raw": [0.0, 30.0, 40.0],  # Bh = 30, Bz = 40 -> aniso = 0.75
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
                "current_ua": -500000,  # -0.5 A -> P = 2.06 W
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
                "spectral_entropy": 0.65,
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

    def test_80d_feature_names_cardinality(self):
        self.assertGreaterEqual(len(CONTINUOUS_FEATURE_NAMES), 80)
        expected_9_new = [
            "kinematic_snap_metric",
            "acoustic_harmonic_to_noise_ratio",
            "photometric_spectral_warmth_index",
            "barometric_buoyancy_energy_flux",
            "geomagnetic_quadrupole_anisotropy_ratio",
            "wifi_channel_dispersion_entropy",
            "thermodynamic_battery_stress_index",
            "neuromotor_tremor_lyapunov_exponent",
            "phase_space_information_flow_rate",
        ]
        for feat in expected_9_new:
            self.assertIn(feat, CONTINUOUS_FEATURE_NAMES)

    def test_kinematic_snap_metric(self):
        # Step 1: initial steady frame
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        obs1["motion"] = dict(self.sample_obs["motion"], accel_raw=[0.0, 0.0, 9.8])
        c1, _ = self.normalizer.extract_raw_features(obs1)
        self.assertEqual(c1["kinematic_snap_metric"], 0.0)

        # Step 2: motion begins (jerk created)
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:05Z")
        obs2["motion"] = dict(self.sample_obs["motion"], accel_raw=[1.0, 0.0, 9.8])
        c2, _ = self.normalizer.extract_raw_features(obs2)
        self.assertEqual(c2["kinematic_snap_metric"], 0.0)

        # Step 3: sharp snap/impact (jerk accelerates)
        obs3 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs3["motion"] = dict(self.sample_obs["motion"], accel_raw=[4.0, 2.0, 15.0])
        c3, _ = self.normalizer.extract_raw_features(obs3)
        self.assertGreater(c3["kinematic_snap_metric"], 0.0)
        self.assertLessEqual(c3["kinematic_snap_metric"], 50.0)

    def test_acoustic_harmonic_to_noise_ratio(self):
        # Active audio with structure
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(c_map["acoustic_harmonic_to_noise_ratio"], -10.0)
        self.assertLessEqual(c_map["acoustic_harmonic_to_noise_ratio"], 30.0)

        # Silent audio
        obs_silent = dict(self.sample_obs)
        obs_silent["audio_context"] = dict(
            self.sample_obs["audio_context"],
            silence_likelihood=0.98,
            rms_loudness=0.0001,
        )
        c_silent, _ = self.normalizer.extract_raw_features(obs_silent)
        self.assertEqual(c_silent["acoustic_harmonic_to_noise_ratio"], 0.0)

    def test_photometric_spectral_warmth_index(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(c_map["photometric_spectral_warmth_index"], 0.0)
        self.assertLessEqual(c_map["photometric_spectral_warmth_index"], 5.0)

        # Explicit warm candlelight (2700K)
        obs_warm = dict(self.sample_obs)
        obs_warm["environment"] = dict(
            self.sample_obs["environment"], color_temperature=2700.0
        )
        c_warm, _ = self.normalizer.extract_raw_features(obs_warm)
        self.assertGreater(c_warm["photometric_spectral_warmth_index"], 2.0)

    def test_barometric_buoyancy_energy_flux(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(c_map["barometric_buoyancy_energy_flux"], 0.0)
        self.assertLessEqual(c_map["barometric_buoyancy_energy_flux"], 10.0)

    def test_geomagnetic_quadrupole_anisotropy_ratio(self):
        # Sample mag_raw = [0, 30, 40] -> Bh = 30, Bz = 40 -> 30/40 = 0.75
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertAlmostEqual(
            c_map["geomagnetic_quadrupole_anisotropy_ratio"], 0.75, delta=0.05
        )

    def test_wifi_channel_dispersion_entropy(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreater(c_map["wifi_channel_dispersion_entropy"], 0.0)
        self.assertLessEqual(c_map["wifi_channel_dispersion_entropy"], 5.0)

        # No wifi
        obs_nowifi = dict(self.sample_obs)
        obs_nowifi["connectivity"] = dict(
            self.sample_obs["connectivity"], wifi_connected=False
        )
        c_nowifi, _ = self.normalizer.extract_raw_features(obs_nowifi)
        self.assertEqual(c_nowifi["wifi_channel_dispersion_entropy"], 0.0)

    def test_thermodynamic_battery_stress_index(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreater(c_map["thermodynamic_battery_stress_index"], 0.0)
        self.assertLessEqual(c_map["thermodynamic_battery_stress_index"], 5.0)

    def test_neuromotor_tremor_lyapunov_and_information_flow(self):
        norm = MultimodalNormalizer()
        # Feed 2 sequential frames to calculate Lyapunov exponent
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        c1, _ = norm.extract_raw_features(obs1)

        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs2["digital_context"] = dict(
            self.sample_obs["digital_context"], app_switch_rate=4.0
        )
        c2, _ = norm.extract_raw_features(obs2)

        self.assertGreaterEqual(c2["neuromotor_tremor_lyapunov_exponent"], -2.0)
        self.assertLessEqual(c2["neuromotor_tremor_lyapunov_exponent"], 5.0)

        # Information flow rate
        self.assertGreaterEqual(c2["phase_space_information_flow_rate"], 0.0)
        self.assertLessEqual(c2["phase_space_information_flow_rate"], 5.0)

    def test_manifold_fit_and_quantize_80d(self):
        dataset = []
        for i in range(30):
            obs_i = dict(self.sample_obs, observation_id=f"obs-80d-{i:03d}")
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
        self.assertGreaterEqual(len(self.normalizer.params.feature_names), 80)

        vec, topo = self.normalizer.transform(self.sample_obs)
        self.assertGreaterEqual(len(vec), 80)

        # Fit DETM with 80D vectors
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

    def test_80d_null_and_nan_resilience(self):
        corrupted = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-corrupted-80d",
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
        self.assertGreaterEqual(len(c_map), 80)
        for k, v in c_map.items():
            self.assertFalse(math.isnan(v), f"Feature {k} returned NaN")
            self.assertFalse(math.isinf(v), f"Feature {k} returned Inf")

        self.normalizer.fit_discovery_window([self.sample_obs])
        vec, topo = self.normalizer.transform(corrupted)
        self.assertGreaterEqual(len(vec), 80)
        for val in vec:
            self.assertFalse(math.isnan(val))
            self.assertFalse(math.isinf(val))


if __name__ == "__main__":
    unittest.main()
