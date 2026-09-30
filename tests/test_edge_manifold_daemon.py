"""
Unit tests for Edge Manifold Daemon (DETM & Online CSD Tipping Detector).
"""

import unittest

from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.manifold_lattice import DynamicalEnergyManifold
from traceform.streaming.edge_manifold_daemon import (
    EdgeManifoldDaemon,
    EdgeDaemonEvent,
)


class TestEdgeManifoldDaemon(unittest.TestCase):
    def setUp(self):
        # Create normalizer and DETM model
        self.normalizer = MultimodalNormalizer()
        synthetic_obs = []
        for i in range(60):
            zone = i % 20
            synthetic_obs.append({
                "timestamp_utc": f"2026-09-02T22:{i//2:02d}:{(i%2)*30:02d}Z",
                "observation_id": f"obs-{i:03d}",
                "motion": {"stationary_likelihood": 0.9 if zone < 10 else 0.1, "motion_energy": 0.05 if zone < 10 else 0.8},
                "environment": {"ambient_light_lux": 15.0 if zone < 10 else 400.0, "barometric_pressure_hpa": 1013.2, "device_temp_c": 30.0},
                "device_relation": {"battery_percentage": 90.0 - i * 0.05, "plugged": "UNPLUGGED"},
                "audio_context": {"silence_likelihood": 0.9, "rms_loudness": 0.01, "spectral_entropy": 0.2},
                "connectivity": {"wifi_connected": True, "wifi_fingerprint": "wf_1"},
                "place": {"spatial_cell": "tv58xy"},
            })

        self.normalizer.fit_discovery_window(synthetic_obs)
        self.manifold = DynamicalEnergyManifold(grid_height=3, grid_width=3, kinetic_weight=0.25)
        self.manifold.fit_discovery_window(synthetic_obs, self.normalizer, epochs=2, seed=42)

        self.daemon = EdgeManifoldDaemon(
            normalizer=self.normalizer,
            manifold=self.manifold,
            ring_buffer_size=30,
            dwell_threshold_steps=10,
            csd_threshold_sigma=2.0,
        )

    def test_submillisecond_quantization(self):
        obs = {
            "timestamp_utc": "2026-09-03T10:00:00Z",
            "observation_id": "test-obs-001",
            "motion": {"stationary_likelihood": 0.9, "motion_energy": 0.05},
            "environment": {"ambient_light_lux": 20.0, "barometric_pressure_hpa": 1013.2, "device_temp_c": 30.0},
            "device_relation": {"battery_percentage": 85.0, "plugged": "UNPLUGGED"},
            "audio_context": {"silence_likelihood": 0.9, "rms_loudness": 0.01, "spectral_entropy": 0.2},
            "connectivity": {"wifi_connected": True, "wifi_fingerprint": "wf_1"},
            "place": {"spatial_cell": "tv58xy"},
        }

        payload, events = self.daemon.process_observation(obs)
        self.assertIn("state_token", payload)
        self.assertTrue(payload["state_token"].startswith("M"))
        self.assertIn("grid_coord", payload)
        self.assertLess(payload["inference_ms"], 10.0)  # Sub-millisecond on most machines

    def test_dwell_alert_trigger(self):
        obs = {
            "timestamp_utc": "2026-09-03T10:00:00Z",
            "observation_id": "test-obs-dwell",
            "motion": {"stationary_likelihood": 0.9, "motion_energy": 0.05},
            "environment": {"ambient_light_lux": 20.0, "barometric_pressure_hpa": 1013.2, "device_temp_c": 30.0},
            "device_relation": {"battery_percentage": 85.0, "plugged": "UNPLUGGED"},
            "audio_context": {"silence_likelihood": 0.9, "rms_loudness": 0.01, "spectral_entropy": 0.2},
            "connectivity": {"wifi_connected": True, "wifi_fingerprint": "wf_1"},
            "place": {"spatial_cell": "tv58xy"},
        }

        triggered = []
        for _ in range(12):
            _, evs = self.daemon.process_observation(obs)
            triggered.extend(evs)

        # Should have fired at least one PROLONGED_DWELL at step 10
        dwell_events = [e for e in triggered if e.event_type == "PROLONGED_DWELL"]
        self.assertEqual(len(dwell_events), 1)
        self.assertEqual(dwell_events[0].metrics["dwell_steps"], 10.0)


if __name__ == "__main__":
    unittest.main()
