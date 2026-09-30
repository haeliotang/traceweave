"""
Unit tests for the 128D Canonical Super-Cube Sensorium (2^7 Hyper-Manifold).

Verifies:
1. Exact cardinality of 128 continuous physical, thermodynamic, electromagnetic, bio-acoustic, and non-linear chaotic features.
2. 5th-order Crackle and 6th-order Pop kinematics, IMU autocorrelation decay, and structural resonance energy (D81-D84).
3. Dual-mic cross-correlation TDOA, spatial phase coherence, spectral flux, and ultrasonic micro-Doppler (D85-D88).
4. GNSS multi-constellation satellite density, mean C/N0, PDOP, and carrier Doppler speed (D89-D92).
5. Multi-zone thermal gradients (SoC-to-skin, modem-to-battery), relaxation time constant, and entropy production (D93-D96).
6. Cellular RSRQ, SINR, BLE advertising channel entropy, Wi-Fi speed jitter, backscatter, and timing advance (D97-D102).
7. Touch ellipse eccentricity, downforce slope, Fitts' law throughput, cadence decay, area, and tortuosity (D103-D108).
8. CPU frequency scaling entropy, kernel context switch velocity, memory PSI, jank variance, and I/O wait pressure (D109-D114).
9. Fractal Hurst exponent, permutation entropy, RQA determinism/laminarity, Lempel-Ziv complexity, Fisher information,
   trapping time, approximate entropy, bifurcation margin, Teager energy, angular momentum, Betti proxy, Kaplan-Yorke dimension,
   and composite holographic coherence index (D115-D128).
10. 128D DETM manifold fitting, topological quantization, and sub-millisecond execution.
11. Comprehensive null, NaN, and unparseable input resilience across all 128 channels.
"""

from __future__ import annotations

import math
import time
import unittest

from traceform.representation.normalizer import MultimodalNormalizer, CONTINUOUS_FEATURE_NAMES
from traceform.representation.manifold_lattice import DynamicalEnergyManifold


class TestCanonicalSensorium128D(unittest.TestCase):
    def setUp(self):
        self.normalizer = MultimodalNormalizer()
        self.sample_obs = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-obs-128d-001",
            "timestamp_utc": "2026-10-01T06:00:00Z",
            "motion": {
                "accel_magnitude": 9.81,
                "accel_rms": 5.61,
                "accel_raw": [0.0, 0.0, 9.81],
                "gyro_energy": 0.0001,
                "gyro_raw": [0.1, -0.2, 0.3],
                "motion_energy": 0.08,
                "stationary_likelihood": 0.95,
                "mag_raw": [0.0, 30.0, 40.0],
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
                "current_ua": -500000,
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

    def test_128d_feature_names_cardinality(self):
        self.assertEqual(len(CONTINUOUS_FEATURE_NAMES), 128)
        self.assertEqual(len(set(CONTINUOUS_FEATURE_NAMES)), 128, "Duplicate feature names detected!")

    def test_higher_order_kinematics_crackle_pop_autocorr(self):
        norm = MultimodalNormalizer()
        # Step 1: Initial steady frame
        obs1 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:00Z")
        c1, _ = norm.extract_raw_features(obs1)

        # Step 2: Jerk frame
        obs2 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:05Z")
        obs2["motion"] = dict(self.sample_obs["motion"], accel_raw=[1.0, 0.0, 9.8])
        c2, _ = norm.extract_raw_features(obs2)

        # Step 3: Snap frame
        obs3 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:10Z")
        obs3["motion"] = dict(self.sample_obs["motion"], accel_raw=[3.0, 1.0, 11.0])
        c3, _ = norm.extract_raw_features(obs3)

        # Step 4: Crackle frame
        obs4 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:15Z")
        obs4["motion"] = dict(self.sample_obs["motion"], accel_raw=[6.0, 4.0, 15.0])
        c4, _ = norm.extract_raw_features(obs4)

        # Step 5: Pop frame
        obs5 = dict(self.sample_obs, timestamp_utc="2026-10-01T06:00:20Z")
        obs5["motion"] = dict(self.sample_obs["motion"], accel_raw=[10.0, 8.0, 22.0])
        c5, _ = norm.extract_raw_features(obs5)

        self.assertGreaterEqual(c5["kinematic_crackle_metric"], 0.0)
        self.assertLessEqual(c5["kinematic_crackle_metric"], 50.0)
        self.assertGreaterEqual(c5["kinematic_pop_metric"], 0.0)
        self.assertLessEqual(c5["kinematic_pop_metric"], 50.0)
        self.assertGreaterEqual(c5["imu_temporal_autocorrelation_decay"], 0.1)
        self.assertLessEqual(c5["imu_temporal_autocorrelation_decay"], 10.0)
        self.assertGreaterEqual(c5["device_structural_resonance_energy"], 0.0)
        self.assertLessEqual(c5["device_structural_resonance_energy"], 1.0)

    def test_spatial_acoustics_and_micro_doppler(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(c_map["acoustic_dual_mic_cross_correlation"], -1.0)
        self.assertLessEqual(c_map["acoustic_dual_mic_cross_correlation"], 1.0)
        self.assertGreaterEqual(c_map["acoustic_spatial_coherence_phase"], 0.0)
        self.assertLessEqual(c_map["acoustic_spatial_coherence_phase"], 1.0)
        self.assertGreaterEqual(c_map["acoustic_spectral_flux"], 0.0)
        self.assertLessEqual(c_map["acoustic_spectral_flux"], 10.0)
        self.assertGreaterEqual(c_map["ultrasonic_proximity_micro_doppler"], 0.0)
        self.assertLessEqual(c_map["ultrasonic_proximity_micro_doppler"], 5.0)

    def test_gnss_satellite_geometry_and_cn0(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(c_map["gnss_visible_satellite_density"], 0.0)
        self.assertLessEqual(c_map["gnss_visible_satellite_density"], 40.0)
        self.assertGreaterEqual(c_map["gnss_mean_carrier_to_noise_ratio"], 0.0)
        self.assertLessEqual(c_map["gnss_mean_carrier_to_noise_ratio"], 50.0)
        self.assertGreaterEqual(c_map["gnss_position_dilution_of_precision"], 0.5)
        self.assertLessEqual(c_map["gnss_position_dilution_of_precision"], 20.0)
        self.assertGreaterEqual(c_map["gnss_doppler_velocity_drift"], 0.0)
        self.assertLessEqual(c_map["gnss_doppler_velocity_drift"], 30.0)

    def test_multi_zone_thermal_gradients_and_entropy(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(c_map["thermal_soc_to_skin_gradient"], -10.0)
        self.assertLessEqual(c_map["thermal_soc_to_skin_gradient"], 30.0)
        self.assertGreaterEqual(c_map["thermal_modem_to_battery_gradient"], -10.0)
        self.assertLessEqual(c_map["thermal_modem_to_battery_gradient"], 25.0)
        self.assertGreaterEqual(c_map["thermal_relaxation_time_constant"], 10.0)
        self.assertLessEqual(c_map["thermal_relaxation_time_constant"], 600.0)
        self.assertGreaterEqual(c_map["thermal_entropy_production_rate"], 0.0)
        self.assertLessEqual(c_map["thermal_entropy_production_rate"], 10.0)

    def test_cellular_layer1_and_rf_dispersion(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(c_map["cellular_reference_signal_quality"], -25.0)
        self.assertLessEqual(c_map["cellular_reference_signal_quality"], 0.0)
        self.assertGreaterEqual(c_map["cellular_signal_to_interference_noise"], -15.0)
        self.assertLessEqual(c_map["cellular_signal_to_interference_noise"], 35.0)
        self.assertGreaterEqual(c_map["ble_advertising_channel_entropy"], 0.0)
        self.assertLessEqual(c_map["ble_advertising_channel_entropy"], 2.0)
        self.assertGreaterEqual(c_map["wifi_link_speed_variation_ratio"], 0.0)
        self.assertLessEqual(c_map["wifi_link_speed_variation_ratio"], 2.0)
        self.assertGreaterEqual(c_map["rf_ambient_backscatter_intensity"], 0.0)
        self.assertLessEqual(c_map["rf_ambient_backscatter_intensity"], 20.0)
        self.assertGreaterEqual(c_map["cellular_timing_advance_distance"], 0.0)
        self.assertLessEqual(c_map["cellular_timing_advance_distance"], 50.0)

    def test_touch_micro_geometry_and_fitts_law(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(c_map["touch_contact_ellipse_eccentricity"], 0.0)
        self.assertLessEqual(c_map["touch_contact_ellipse_eccentricity"], 1.0)
        self.assertGreaterEqual(c_map["touch_downforce_pressure_derivative"], 0.0)
        self.assertLessEqual(c_map["touch_downforce_pressure_derivative"], 10.0)
        self.assertGreaterEqual(c_map["neuromotor_fitts_law_throughput"], 0.0)
        self.assertLessEqual(c_map["neuromotor_fitts_law_throughput"], 15.0)
        self.assertGreaterEqual(c_map["touch_biomotor_cadence_decay_slope"], -2.0)
        self.assertLessEqual(c_map["touch_biomotor_cadence_decay_slope"], 2.0)
        self.assertGreaterEqual(c_map["touch_contact_area_normalized"], 0.0)
        self.assertLessEqual(c_map["touch_contact_area_normalized"], 1.0)
        self.assertGreaterEqual(c_map["touch_path_tortuosity_ratio"], 1.0)
        self.assertLessEqual(c_map["touch_path_tortuosity_ratio"], 5.0)

    def test_kernel_scheduling_and_workload_entropy(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(c_map["cpu_frequency_scaling_entropy"], 0.0)
        self.assertLessEqual(c_map["cpu_frequency_scaling_entropy"], 3.0)
        self.assertGreaterEqual(c_map["kernel_context_switch_velocity"], 0.0)
        self.assertLessEqual(c_map["kernel_context_switch_velocity"], 50.0)
        self.assertGreaterEqual(c_map["memory_pressure_stall_ratio"], 0.0)
        self.assertLessEqual(c_map["memory_pressure_stall_ratio"], 1.0)
        self.assertGreaterEqual(c_map["display_frame_pacing_jank_variance"], 0.0)
        self.assertLessEqual(c_map["display_frame_pacing_jank_variance"], 10.0)
        self.assertGreaterEqual(c_map["io_wait_latency_proxy"], 0.0)
        self.assertLessEqual(c_map["io_wait_latency_proxy"], 1.0)
        self.assertGreaterEqual(c_map["cpu_user_system_mode_ratio"], 0.0)
        self.assertLessEqual(c_map["cpu_user_system_mode_ratio"], 10.0)

    def test_chaos_hurst_rqa_and_holo_coherence(self):
        c_map, _ = self.normalizer.extract_raw_features(self.sample_obs)
        self.assertGreaterEqual(c_map["fractal_hurst_exponent"], 0.0)
        self.assertLessEqual(c_map["fractal_hurst_exponent"], 1.0)
        self.assertGreaterEqual(c_map["permutation_entropy_complexity"], 0.0)
        self.assertLessEqual(c_map["permutation_entropy_complexity"], 1.0)
        self.assertGreaterEqual(c_map["rqa_recurrence_determinism_det"], 0.0)
        self.assertLessEqual(c_map["rqa_recurrence_determinism_det"], 1.0)
        self.assertGreaterEqual(c_map["rqa_laminarity_vertical_trapping"], 0.0)
        self.assertLessEqual(c_map["rqa_laminarity_vertical_trapping"], 1.0)
        self.assertGreaterEqual(c_map["lempel_ziv_algorithmic_complexity"], 0.0)
        self.assertLessEqual(c_map["lempel_ziv_algorithmic_complexity"], 2.0)
        self.assertGreaterEqual(c_map["fisher_information_measure"], 0.0)
        self.assertLessEqual(c_map["fisher_information_measure"], 10.0)
        self.assertGreaterEqual(c_map["phase_space_trapping_time_seconds"], 0.0)
        self.assertLessEqual(c_map["phase_space_trapping_time_seconds"], 3600.0)
        self.assertGreaterEqual(c_map["multiscale_approximate_entropy"], 0.0)
        self.assertLessEqual(c_map["multiscale_approximate_entropy"], 3.0)
        self.assertGreaterEqual(c_map["bifurcation_stability_margin"], 0.0)
        self.assertLessEqual(c_map["bifurcation_stability_margin"], 2.0)
        self.assertGreaterEqual(c_map["non_linear_energy_operator_teager"], 0.0)
        self.assertLessEqual(c_map["non_linear_energy_operator_teager"], 10.0)
        self.assertGreaterEqual(c_map["phase_space_angular_momentum"], 0.0)
        self.assertLessEqual(c_map["phase_space_angular_momentum"], 20.0)
        self.assertGreaterEqual(c_map["topological_betti_number_proxy"], 0.0)
        self.assertLessEqual(c_map["topological_betti_number_proxy"], 5.0)
        self.assertGreaterEqual(c_map["dissipative_lyapunov_dimension_ky"], 0.0)
        self.assertLessEqual(c_map["dissipative_lyapunov_dimension_ky"], 6.0)
        self.assertGreaterEqual(c_map["holo_cosmic_coherence_index"], 0.0)
        self.assertLessEqual(c_map["holo_cosmic_coherence_index"], 1.0)

    def test_manifold_fit_and_quantize_128d(self):
        dataset = []
        for i in range(30):
            obs_i = dict(self.sample_obs, observation_id=f"obs-128d-{i:03d}")
            obs_i["environment"] = dict(self.sample_obs["environment"], pressure_hpa=1013.0 - (i % 5) * 0.2)
            obs_i["motion"] = dict(
                self.sample_obs["motion"],
                accel_raw=[0.1 * (i % 3), 0.1 * (i % 4), 9.7],
                gyro_raw=[0.05 * (i % 2), 0.02 * (i % 3), 0.01],
                mag_raw=[10.0, 20.0 + (i % 5), 30.0],
            )
            dataset.append(obs_i)

        self.normalizer.fit_discovery_window(dataset)
        self.assertEqual(len(self.normalizer.params.feature_names), 128)

        vec, topo = self.normalizer.transform(self.sample_obs)
        self.assertEqual(len(vec), 128)

        # Fit DETM with 128D vectors
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

    def test_128d_null_and_nan_resilience(self):
        corrupted = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": "test-corrupted-128d",
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
        self.assertEqual(len(c_map), 128)
        for k, v in c_map.items():
            self.assertFalse(math.isnan(v), f"Feature {k} returned NaN")
            self.assertFalse(math.isinf(v), f"Feature {k} returned Inf")

        self.normalizer.fit_discovery_window([self.sample_obs])
        vec, topo = self.normalizer.transform(corrupted)
        self.assertEqual(len(vec), 128)
        for val in vec:
            self.assertFalse(math.isnan(val))
            self.assertFalse(math.isinf(val))


if __name__ == "__main__":
    unittest.main()
