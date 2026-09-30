#!/usr/bin/env python3
"""Contract test suite for Physical Observation Stack v1."""

import json
import math
from pathlib import Path
import shutil
import tempfile
import unittest

import numpy as np

from pixel_physical import audio_features, config, connectivity, episodes, ledger, location, observation, transition


class TestPhysicalObservationContractV1(unittest.TestCase):

    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="test_pixel_phys_"))

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _create_mock_obs(self, obs_id: str, lat=28.21, lon=83.96, motion_energy=0.1, stat_like=0.95, plugged="UNPLUGGED", wifi_fp="fp123", accuracy_m=15.0):
        cell = location.encode_geohash(lat, lon, precision=7)
        return observation.PhysicalObservation(
            protocol=config.PROTOCOL,
            observation_id=obs_id,
            timestamp_utc="2026-09-02T20:00:00.000000Z",
            monotonic_ns=1000000000,
            availability={
                "motion": "AVAILABLE",
                "place": "AVAILABLE",
                "environment": "AVAILABLE",
                "connectivity": "AVAILABLE",
                "device_relation": "AVAILABLE",
                "audio_context": "AVAILABLE",
            },
            motion={
                "accel_magnitude": 9.81,
                "accel_rms": 5.66,
                "accel_raw": [0.0, 0.0, 9.81],
                "gyro_energy": 0.0001,
                "gyro_raw": [0.0, 0.0, 0.0],
                "motion_energy": motion_energy,
                "stationary_likelihood": stat_like,
                "orientation_degrees": [120.0, 0.0, 0.0],
                "step_count": 100,
            },
            place={
                "latitude": lat,
                "longitude": lon,
                "accuracy_m": accuracy_m,
                "altitude_m": 800.0,
                "speed_mps": 0.0,
                "bearing_deg": 0.0,
                "provider": "gps",
                "spatial_cell": cell,
                "distance_delta_m": 0.0,
            },
            environment={
                "ambient_light_lux": 10.0,
                "pressure_hpa": 913.0,
                "proximity_cm": 5.0,
                "device_temp_c": 32.0,
            },
            connectivity={
                "wifi_connected": True,
                "wifi_fingerprint": wifi_fp,
                "wifi_rssi": -50,
                "wifi_link_speed_mbps": 500,
                "wifi_frequency_mhz": 5000,
                "wifi_ap_count": 10,
                "wifi_environment_fingerprint": "envfp456",
                "cellular_network_type": "lte",
                "cellular_cell_fingerprint": "cellfp789",
                "cellular_dbm": -80,
            },
            device_relation={
                "battery_percentage": 70,
                "plugged": plugged,
                "status": "DISCHARGING",
                "battery_temp_c": 31.0,
                "voltage_mv": 3950,
                "current_ua": -400000,
                "wired_headset": False,
                "bluetooth_a2dp": False,
            },
            audio_context={
                "rms_loudness": 0.001,
                "peak_level": 0.005,
                "zero_crossing_rate": 0.02,
                "spectral_centroid_hz": 500.0,
                "spectral_bandwidth_hz": 800.0,
                "spectral_entropy": 0.5,
                "silence_likelihood": 0.98,
                "speech_likelihood": 0.0,
                "music_likelihood": 0.0,
            },
            sample_latencies_ms={"motion": 50.0},
        )

    def test_01_geodesic_and_geohash_calculations(self):
        """Tests Haversine distance and geohash spatial cell encoding."""
        # Exact same coordinates
        d0 = location.calculate_haversine_distance_m(28.0, 83.0, 28.0, 83.0)
        self.assertEqual(d0, 0.0)

        # 1 degree latitude delta is approximately 111.19 km
        d1 = location.calculate_haversine_distance_m(28.0, 83.0, 29.0, 83.0)
        self.assertTrue(110000.0 < d1 < 112000.0)

        # Geohash consistency
        gh1 = location.encode_geohash(28.2129, 83.9663, precision=7)
        gh2 = location.encode_geohash(28.2129, 83.9663, precision=7)
        self.assertEqual(gh1, gh2)
        self.assertEqual(len(gh1), 7)

    def test_02_privacy_fingerprints(self):
        """Tests deterministic hashing, regular file 0600 permissions, key stability, and silent rotation prevention."""
        fp1 = connectivity.hash_identifier("00:11:22:33:44:55:MyHomeWiFi")
        fp2 = connectivity.hash_identifier("00:11:22:33:44:55:MyHomeWiFi")
        self.assertEqual(fp1, fp2)
        self.assertNotIn("MyHomeWiFi", fp1)
        self.assertEqual(len(fp1), 16)

        # Dummy/empty MAC handling
        self.assertIsNone(connectivity.hash_identifier(None))
        self.assertIsNone(connectivity.hash_identifier("02:00:00:00:00:00"))

        # Salt file invariants: regular file, mode 0600
        salt_file = connectivity.SALT_FILE
        self.assertTrue(salt_file.exists())
        self.assertFalse(salt_file.is_symlink())
        self.assertEqual(salt_file.stat().st_mode & 0o777, 0o600)

        # Key stability across calls
        s1 = connectivity.get_device_salt()
        s2 = connectivity.get_device_salt()
        self.assertEqual(s1, s2)
        print("FINGERPRINT_KEY_STABILITY=PASS")
        print("FINGERPRINT_SILENT_ROTATION=0")

    def test_03_audio_feature_extraction_guarantees(self):
        """Tests acoustic feature extraction on synthetic silence, sine, and noise."""
        # 1. Digital silence
        silence = np.zeros(16000, dtype=np.float32)
        f_sil = audio_features.extract_features_from_pcm(silence, sample_rate=16000)
        self.assertEqual(f_sil["rms_loudness"], 0.0)
        self.assertEqual(f_sil["silence_likelihood"], 1.0)
        self.assertFalse(math.isnan(f_sil["spectral_centroid_hz"]))

        # 2. 1kHz Sine wave
        t = np.linspace(0, 1.0, 16000, endpoint=False)
        sine = (0.5 * np.sin(2 * np.pi * 1000.0 * t)).astype(np.float32)
        f_sine = audio_features.extract_features_from_pcm(sine, sample_rate=16000)
        self.assertTrue(0.34 < f_sine["rms_loudness"] < 0.36)
        self.assertTrue(900.0 < f_sine["spectral_centroid_hz"] < 1100.0)
        self.assertTrue(f_sine["silence_likelihood"] < 0.1)

        # 3. Random noise
        noise = (np.random.uniform(-0.1, 0.1, 16000)).astype(np.float32)
        f_noise = audio_features.extract_features_from_pcm(noise, sample_rate=16000)
        self.assertFalse(math.isnan(f_noise["spectral_entropy"]))
        self.assertFalse(math.isinf(f_noise["spectral_entropy"]))

    def test_04_transition_detection_rules(self):
        """Tests that transitions fire only on meaningful physical changes."""
        obs1 = self._create_mock_obs("obs-1", lat=28.2100, lon=83.9600)
        obs2 = self._create_mock_obs("obs-2", lat=28.2100, lon=83.9600)

        # 1. Identical observations -> Zero transition
        t_none = transition.detect_transition(obs1, obs2)
        self.assertIsNone(t_none)

        # 2. Stationary to active motion
        obs_motion = self._create_mock_obs("obs-3", lat=28.2100, lon=83.9600, motion_energy=1.2, stat_like=0.1)
        t_motion = transition.detect_transition(obs1, obs_motion)
        self.assertIsNotNone(t_motion)
        self.assertIn("motion", t_motion.dominant_changes)
        self.assertEqual(t_motion.prior_observation_id, "obs-1")
        self.assertEqual(t_motion.subsequent_observation_id, "obs-3")

        # 3. Spatial cell displacement (~200m)
        obs_place = self._create_mock_obs("obs-4", lat=28.2120, lon=83.9620)
        t_place = transition.detect_transition(obs1, obs_place)
        self.assertIsNotNone(t_place)
        self.assertIn("place", t_place.dominant_changes)

        # 4. Charging state change
        obs_charge = self._create_mock_obs("obs-5", lat=28.2100, lon=83.9600, plugged="PLUGGED_USB")
        t_charge = transition.detect_transition(obs1, obs_charge)
        self.assertIsNotNone(t_charge)
        self.assertIn("device_relation", t_charge.dominant_changes)

    def test_05_episode_assembly_and_boundaries(self):
        """Tests episode aggregation and segmentation logic."""
        assembler = episodes.EpisodeAssembler(stability_timeout_s=10.0)

        obs1 = self._create_mock_obs("obs-1")
        obs2 = self._create_mock_obs("obs-2")
        obs3 = self._create_mock_obs("obs-3", lat=28.2150, lon=83.9650)  # Place transition

        # Initial obs starts active episode
        closed1, active1 = assembler.process_observation(obs1)
        self.assertIsNone(closed1)
        self.assertEqual(len(active1.evidence_observation_ids), 1)

        # Stable obs continues active episode
        closed2, active2 = assembler.process_observation(obs2)
        self.assertIsNone(closed2)
        self.assertEqual(len(active2.evidence_observation_ids), 2)
        self.assertEqual(active1.episode_id, active2.episode_id)

        # Transition triggers boundary
        trans = transition.detect_transition(obs2, obs3)
        self.assertIsNotNone(trans)
        closed3, active3 = assembler.process_observation(obs3, transition=trans)
        self.assertIsNotNone(closed3)
        self.assertEqual(closed3.episode_boundary_reason, "PLACE_TRANSITION")
        self.assertNotEqual(closed3.episode_id, active3.episode_id)
        self.assertEqual(active3.initial_state_ref, "obs-3")

    def test_06_ledger_durability_and_truncated_tail_recovery(self):
        """Tests append-only ledger durability and fault-tolerant tail parsing."""
        obs_ledger = self.temp_dir / "obs.jsonl"
        obs1 = self._create_mock_obs("obs-1")
        obs2 = self._create_mock_obs("obs-2")

        ledger.append_observation(obs1, ledger_path=obs_ledger)
        ledger.append_observation(obs2, ledger_path=obs_ledger)

        # Verify reads
        items = ledger.read_recent_observations(limit=10, ledger_path=obs_ledger)
        self.assertEqual(len(items), 2)
        self.assertEqual(items[0].observation_id, "obs-1")
        self.assertEqual(items[1].observation_id, "obs-2")

        # Simulate abrupt power-kill resulting in half-written trailing garbage
        with obs_ledger.open("a") as f:
            f.write('{"protocol":"pixel-sensorium.physical-observation.v1","observation_id":"obs-TRUNCATED')

        # Read should cleanly ignore trailing corrupt line and return valid records
        recovered = ledger.read_recent_observations(limit=10, ledger_path=obs_ledger)
        self.assertEqual(len(recovered), 2)
        self.assertEqual(recovered[1].observation_id, "obs-2")


    def test_07_place_hysteresis_and_jitter_suppression(self):
        """Tests accuracy-aware spatial hysteresis and suppression of geohash boundary jitter."""
        # 1. Coordinate shift of 40m right across geohash boundary, but accuracy is 25m (uncertainty ~53m)
        obs_a = self._create_mock_obs("obs-a", lat=28.21259, lon=83.96640, accuracy_m=24.0)
        # Small displacement across cell border (cell changes from tv58qxy to tv58w8n, ~40m delta)
        obs_b = self._create_mock_obs("obs-b", lat=28.21289, lon=83.96626, accuracy_m=26.0)

        # Transition should be suppressed because displacement (40m) is within noise envelope (~53m)
        t_jitter = transition.detect_transition(obs_a, obs_b)
        self.assertIsNone(t_jitter, "Boundary jitter within accuracy envelope must not emit a place transition")

        # 2. Genuine movement of 150m (exceeding accuracy envelope)
        obs_c = self._create_mock_obs("obs-c", lat=28.21400, lon=83.96750, accuracy_m=20.0)
        t_real = transition.detect_transition(obs_a, obs_c)
        self.assertIsNotNone(t_real, "Displacement exceeding uncertainty envelope must emit place transition")
        self.assertIn("place", t_real.dominant_changes)
        print("PLACE_HYSTERESIS_CONTRACT=PASS")


if __name__ == "__main__":
    unittest.main()
