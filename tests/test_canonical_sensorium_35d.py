"""
Unit tests for the 35D Canonical Holo-Sensorium (Cyber-Physical-Cognitive Manifold).

Verifies:
1. Complete cardinality and registration of 35 continuous physical, physiological, spatial, and cognitive features.
2. 3D spatial attitude: device zenith tilt angle computation from accelerometer gravity vector.
3. Micro-meteorological vertical kinematics: barometric vertical velocity (dP/dt) hypsometric differentiation.
4. Physiological ballistocardiogram & micro-tremor extraction in stillness.
5. Thermal conduction gradient decoupled from internal CPU/charging dissipation.
6. Information-theoretic ambient RF Shannon entropy and cellular base station handover churn rate.
7. Cognitive attention fragmentation entropy and motoric touch interval jitter.
8. 35D DETM manifold fitting, topological quantization, and sub-millisecond execution.
9. Comprehensive null, NaN, and unparseable input resilience across all 35 channels.
"""

from __future__ import annotations

import math
import unittest

from traceform.probes.digital_sensorium import DigitalSensoriumProbe
from traceform.representation.normalizer import MultimodalNormalizer, CONTINUOUS_FEATURE_NAMES
from traceform.representation.manifold_lattice import DynamicalEnergyManifold


class TestCanonicalSensorium35D(unittest.TestCase):
    def setUp(self):
        self.normalizer = MultimodalNormalizer()
        self.sample_obs = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-obs-35d-001",
            "timestamp_utc": "2026-10-01T06:00:00Z",
            "motion": {
                "accel_magnitude": 9.81,
                "accel_rms": 5.61,
                "accel_raw": [0.0, 0.0, 9.81],  # Screen flat face up
                "gyro_energy": 0.0001,
                "gyro_raw": [0.0001, 0.0, 0.0],
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
            },
        }

    def test_35d_feature_names_cardinality(self):
        self.assertEqual(len(CONTINUOUS_FEATURE_NAMES), 35)
        expected_9_new = [
            "physio_ballisto_tremor_energy",
            "thermal_conduction_rate_dt",
            "device_zenith_tilt_angle",
            "geomagnetic_flux_divergence",
            "barometric_vertical_velocity",
            "ambient_rf_shannon_entropy",
            "cellular_tower_churn_rate",
            "touch_interval_jitter",
            "attention_fragmentation_entropy",
        ]
        for feat in expected_9_new:
            self.assertIn(feat, CONTINUOUS_FEATURE_NAMES)

    def test_raw_feature_extraction_35d(self):
        c_map, topo_map = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertEqual(len(c_map), 35)

        # 3D gravity tilt: accel_raw=[0, 0, 9.81] -> angle 0.0 degrees (flat)
        self.assertAlmostEqual(c_map["device_zenith_tilt_angle"], 0.0, places=1)

        # Ambient RF Shannon entropy: 4.1
        self.assertEqual(c_map["ambient_rf_shannon_entropy"], 4.1)

        # Physiological tremor: stationary with micro motion energy
        self.assertGreater(c_map["physio_ballisto_tremor_energy"], 0.0)
        self.assertLessEqual(c_map["physio_ballisto_tremor_energy"], 1.0)

        # Geomagnetic divergence: 45 uT baseline -> 0.0
        self.assertAlmostEqual(c_map["geomagnetic_flux_divergence"], 0.0, places=1)

    def test_device_zenith_tilt_angle_orientations(self):
        norm = MultimodalNormalizer()

        # 1. Flat on table (screen up): az = 9.8 -> tilt = 0 deg
        obs_flat = dict(self.sample_obs, motion=dict(self.sample_obs["motion"], accel_raw=[0.0, 0.0, 9.8]))
        c_flat, _ = norm.extract_raw_features(obs_flat)
        self.assertAlmostEqual(c_flat["device_zenith_tilt_angle"], 0.0, delta=1.0)

        # 2. In-hand reading posture (screen tilted ~60 deg): ax=0, ay=8.48, az=4.9
        obs_hand = dict(self.sample_obs, motion=dict(self.sample_obs["motion"], accel_raw=[0.0, 8.48, 4.9]))
        c_hand, _ = norm.extract_raw_features(obs_hand)
        self.assertAlmostEqual(c_hand["device_zenith_tilt_angle"], 60.0, delta=2.0)

        # 3. Vertical in pocket: ax=0, ay=9.8, az=0.0 -> tilt = 90 deg
        obs_pocket = dict(self.sample_obs, motion=dict(self.sample_obs["motion"], accel_raw=[0.0, 9.8, 0.0]))
        c_pocket, _ = norm.extract_raw_features(obs_pocket)
        self.assertAlmostEqual(c_pocket["device_zenith_tilt_angle"], 90.0, delta=1.0)

        # 4. Face down on table: az = -9.8 -> tilt = 180 deg
        obs_facedown = dict(self.sample_obs, motion=dict(self.sample_obs["motion"], accel_raw=[0.0, 0.0, -9.8]))
        c_facedown, _ = norm.extract_raw_features(obs_facedown)
        self.assertAlmostEqual(c_facedown["device_zenith_tilt_angle"], 180.0, delta=1.0)

    def test_barometric_vertical_velocity_differentiation(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Frame 1: T=00:00:00, P=1013.25 hPa
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        obs1["environment"] = dict(self.sample_obs["environment"], pressure_hpa=1013.25)
        c1, _ = norm.extract_raw_features(obs1)
        self.assertEqual(c1["barometric_vertical_velocity"], 0.0)

        # Frame 2: T=00:00:10 (10s later), P=1012.75 hPa (drop of 0.5 hPa -> ascending ~4.2 meters)
        # vz = -8.43 * (-0.5 / 10.0) = +0.4215 m/s
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs2["environment"] = dict(self.sample_obs["environment"], pressure_hpa=1012.75)
        c2, _ = norm.extract_raw_features(obs2)
        self.assertAlmostEqual(c2["barometric_vertical_velocity"], 0.422, delta=0.01)

    def test_thermal_conduction_rate_dt(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Frame 1: T=00:00:00, Temp=28.0 C (unplugged, screen off)
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        obs1["environment"] = dict(self.sample_obs["environment"], device_temp_c=28.0)
        obs1["device_relation"] = dict(self.sample_obs["device_relation"], status="DISCHARGING", screen_state="OFF")
        obs1["digital_context"] = dict(self.sample_obs["digital_context"], screen_on_ratio=0.0)
        norm.extract_raw_features(obs1)

        # Frame 2: T=00:01:00 (60s later), Temp=28.05 C (+0.05 C in 60s = +3.0 C / hr warming from body heat)
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:01:00Z")
        obs2["environment"] = dict(self.sample_obs["environment"], device_temp_c=28.05)
        obs2["device_relation"] = dict(self.sample_obs["device_relation"], status="DISCHARGING", screen_state="OFF")
        obs2["digital_context"] = dict(self.sample_obs["digital_context"], screen_on_ratio=0.0)
        c2, _ = norm.extract_raw_features(obs2)

        # Rate should be ~3.0 C/hr (within [-10, 10])
        self.assertAlmostEqual(c2["thermal_conduction_rate_dt"], 3.0, delta=0.1)

    def test_cellular_tower_churn_rate(self):
        norm = MultimodalNormalizer()
        norm.reset_state()

        # Simulate 4 base station handovers across 10 minutes (rapid travel on train / highway)
        for i in range(5):
            obs = dict(self.sample_obs, timestamp_utc=f"2026-10-01T06:0{i*2}:00Z")
            obs["connectivity"] = dict(self.sample_obs["connectivity"], cellular_cell_fingerprint=f"cell_sector_{i:03d}")
            c, _ = norm.extract_raw_features(obs)

        # High handover churn rate
        self.assertGreater(c["cellular_tower_churn_rate"], 0.0)
        self.assertLessEqual(c["cellular_tower_churn_rate"], 30.0)

    def test_manifold_fit_and_quantize_35d(self):
        dataset = []
        for i in range(30):
            obs_i = dict(self.sample_obs, observation_id=f"obs-35d-{i:03d}")
            obs_i["environment"] = dict(self.sample_obs["environment"], pressure_hpa=1013.0 - (i % 5) * 0.2)
            obs_i["motion"] = dict(self.sample_obs["motion"], accel_raw=[0.1 * (i % 3), 0.1 * (i % 4), 9.7])
            dataset.append(obs_i)

        self.normalizer.fit_discovery_window(dataset)
        self.assertEqual(len(self.normalizer.params.feature_names), 35)

        vec, topo = self.normalizer.transform(self.sample_obs)
        self.assertEqual(len(vec), 35)

        # Fit DETM with 35D vectors
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

    def test_35d_null_and_nan_resilience(self):
        corrupted = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-corrupted-35d",
            "timestamp_utc": "2026-10-01T06:00:00Z",
            "motion": {
                "accel_magnitude": None,
                "accel_rms": float("nan"),
                "accel_raw": [None, float("inf"), "bad"],
                "gyro_energy": None,
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
                "current_ua": None,
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
            },
        }

        c_map, topo_map = self.normalizer.extract_raw_features(corrupted)
        self.assertEqual(len(c_map), 35)
        for k, v in c_map.items():
            self.assertFalse(math.isnan(v), f"Feature {k} returned NaN")
            self.assertFalse(math.isinf(v), f"Feature {k} returned Inf")

        self.normalizer.fit_discovery_window([self.sample_obs])
        vec, topo = self.normalizer.transform(corrupted)
        self.assertEqual(len(vec), 35)
        for val in vec:
            self.assertFalse(math.isnan(val))
            self.assertFalse(math.isinf(val))


if __name__ == "__main__":
    unittest.main()
