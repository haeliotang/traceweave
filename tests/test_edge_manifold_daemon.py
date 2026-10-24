"""
Unit tests for Edge Manifold Daemon (DETM & Online CSD Tipping Detector).
"""

import unittest

from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.manifold_lattice import DynamicalEnergyManifold
from traceform.streaming.edge_manifold_daemon import (
    EdgeManifoldDaemon,
)


class TestEdgeManifoldDaemon(unittest.TestCase):
    def test_v2_provenance_survives_ledger_into_continuous_frame(self):
        import tempfile
        from pathlib import Path
        try:
            from traceform.streaming.physical_observation_v2 import PhysicalObservation
            from traceform.streaming.physical_ledger_v2 import append_observation, read_recent_observations
        except ModuleNotFoundError:
            self.skipTest('physical v2 modules are not present in this checkout')

        evidence = {'motion': {'adapter': 'synthetic_fixture', 'status': 'AVAILABLE',
                              'measurement_scope': 'adapter_return_not_per_field_direct_measurement'}}
        import datetime
        # The rolling ledger drops records older than its 48 h retention, so the fixture must be recent.
        stamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z')
        observation = PhysicalObservation.from_dict({
            'timestamp_utc': stamp, 'observation_id': 'synthetic',
            'motion': {'motion_energy': 0.1}, 'feature_provenance': evidence})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            append_observation(observation, path)
            restored = read_recent_observations(1, path)[0]
        payload, _ = self.daemon.process_observation(restored.to_dict())
        self.assertEqual(payload['upstream_feature_provenance'], evidence)
        self.assertEqual(len(payload['continuous_vector']), 128)
        self.assertEqual(payload['input_inventory']['measurement_status'],
                         'unverified_unless_upstream_provenance_supplied')

    def test_continuous_csd_can_warn_inside_a_constant_token(self):
        import datetime
        import math
        import random
        from unittest.mock import patch

        def run(space, varying):
            daemon = EdgeManifoldDaemon(self.normalizer, self.manifold,
                                        ring_buffer_size=60, dwell_threshold_steps=60,
                                        csd_input_space=space)
            rng = random.Random(42)
            alerts = []
            start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
            for index in range(140):
                value = 0.5 + (rng.uniform(-0.001, 0.001) if index < 100 else 0.3 * math.sin((index - 100) / 4)) if varying else 0.5
                def quantize(*args, **kwargs):
                    self.manifold.last_obs_vector = [value] * 128
                    return "M01", (1, 1), 0.1
                with patch.object(self.manifold, "quantize", side_effect=quantize):
                    frame, events = daemon.process_observation({"timestamp_utc": (start + datetime.timedelta(seconds=30 * index)).isoformat()})
                alerts.extend(event for event in events if event.event_type == "CSD_TIPPING" and frame["dwell_steps"] >= 60)
            return alerts
        self.assertTrue(run("continuous", True), "continuous variation is erased by the constant token coordinates")
        self.assertFalse(run("grid", True), "legacy grid diagnostics should remain unchanged")
        self.assertFalse(run("continuous", False), "constant continuous input must not cause an alert")

    def test_continuous_csd_invalid_input_requires_new_history(self):
        import datetime
        from unittest.mock import patch

        daemon = EdgeManifoldDaemon(self.normalizer, self.manifold, csd_input_space="continuous")
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        with patch.object(self.manifold, "quantize", return_value=("M01", (1, 1), 0.1)):
            for index in range(30):
                self.manifold.last_obs_vector = [0.5] * 128
                daemon.process_observation({"timestamp_utc": (start + datetime.timedelta(seconds=30 * index)).isoformat()})
            for index, vector in enumerate(([float("nan")] * 128, [], [11.0] * 128, [0.5] * 129, [0.5] * 128), 30):
                self.manifold.last_obs_vector = vector
                _, events = daemon.process_observation({"timestamp_utc": (start + datetime.timedelta(seconds=30 * index)).isoformat()})
                self.assertFalse(any(event.event_type == "CSD_TIPPING" for event in events))
            self.assertEqual(len(daemon.coord_buffer), 1)
        daemon.reset_continuity()
        self.assertEqual(len(daemon.coord_buffer), 0)
        with self.assertRaises(ValueError):
            EdgeManifoldDaemon(self.normalizer, self.manifold, csd_input_space="unknown")

    def test_quantizer_receives_actual_frame_interval(self):
        from unittest.mock import patch

        first = {"timestamp_utc": "2026-10-01T02:00:00Z", "motion": {}}
        second = {"timestamp_utc": "2026-10-01T02:00:01Z", "motion": {}}
        with patch.object(self.manifold, "quantize", wraps=self.manifold.quantize) as quantize:
            self.daemon.process_observation(first)
            self.daemon.process_observation(second)
        self.assertEqual(quantize.call_args.kwargs["dt_seconds"], 1.0)

    def test_stream_evidence_and_short_clock_rollback(self):
        first = {"timestamp_utc": "2026-10-01T02:00:02Z", "motion": {}}
        second = {"timestamp_utc": "2026-10-01T02:00:01Z", "motion": {}}
        before, _ = self.daemon.process_observation(first)
        after, _ = self.daemon.process_observation(second)
        self.assertEqual(len(before["model_version"]), 64)
        self.assertEqual(before["model_version"], after["model_version"])
        self.assertGreater(after["continuity_epoch"], before["continuity_epoch"])
        self.assertFalse(after["continuous_from_previous"])
        self.assertIsNone(after["frame_interval_seconds"])
        self.assertEqual(after["dwell_steps"], 1)

    def setUp(self):
        # Create normalizer and DETM model
        self.normalizer = MultimodalNormalizer()
        synthetic_obs = []
        for i in range(60):
            zone = i % 20
            synthetic_obs.append(
                {
                    "timestamp_utc": f"2026-09-02T22:{i//2:02d}:{(i%2)*30:02d}Z",
                    "observation_id": f"obs-{i:03d}",
                    "motion": {
                        "stationary_likelihood": 0.9 if zone < 10 else 0.1,
                        "motion_energy": 0.05 if zone < 10 else 0.8,
                    },
                    "environment": {
                        "ambient_light_lux": 15.0 if zone < 10 else 400.0,
                        "barometric_pressure_hpa": 1013.2,
                        "device_temp_c": 30.0,
                    },
                    "device_relation": {
                        "battery_percentage": 90.0 - i * 0.05,
                        "plugged": "UNPLUGGED",
                    },
                    "audio_context": {
                        "silence_likelihood": 0.9,
                        "rms_loudness": 0.01,
                        "spectral_entropy": 0.2,
                    },
                    "connectivity": {
                        "wifi_connected": True,
                        "wifi_fingerprint": "wf_1",
                    },
                    "place": {"spatial_cell": "tv58xy"},
                }
            )

        self.normalizer.fit_discovery_window(synthetic_obs)
        self.manifold = DynamicalEnergyManifold(
            grid_height=3, grid_width=3, kinetic_weight=0.25
        )
        self.manifold.fit_discovery_window(
            synthetic_obs, self.normalizer, epochs=2, seed=42
        )

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
            "environment": {
                "ambient_light_lux": 20.0,
                "barometric_pressure_hpa": 1013.2,
                "device_temp_c": 30.0,
            },
            "device_relation": {"battery_percentage": 85.0, "plugged": "UNPLUGGED"},
            "audio_context": {
                "silence_likelihood": 0.9,
                "rms_loudness": 0.01,
                "spectral_entropy": 0.2,
            },
            "connectivity": {"wifi_connected": True, "wifi_fingerprint": "wf_1"},
            "place": {"spatial_cell": "tv58xy"},
        }

        payload, events = self.daemon.process_observation(obs)
        self.assertIn("state_token", payload)
        self.assertTrue(payload["state_token"].startswith("M"))
        self.assertIn("grid_coord", payload)
        self.assertLess(
            payload["inference_ms"], 10.0
        )  # Sub-millisecond on most machines

    def test_dwell_alert_trigger(self):
        obs = {
            "timestamp_utc": "2026-09-03T10:00:00Z",
            "observation_id": "test-obs-dwell",
            "motion": {"stationary_likelihood": 0.9, "motion_energy": 0.05},
            "environment": {
                "ambient_light_lux": 20.0,
                "barometric_pressure_hpa": 1013.2,
                "device_temp_c": 30.0,
            },
            "device_relation": {"battery_percentage": 85.0, "plugged": "UNPLUGGED"},
            "audio_context": {
                "silence_likelihood": 0.9,
                "rms_loudness": 0.01,
                "spectral_entropy": 0.2,
            },
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

    def test_inter_frame_outage_gap_reset(self):
        obs1 = {
            "timestamp_utc": "2026-09-03T10:00:00Z",
            "observation_id": "gap-1",
            "motion": {"stationary_likelihood": 0.9, "motion_energy": 0.05},
            "environment": {"ambient_light_lux": 20.0, "device_temp_c": 30.0},
            "device_relation": {"battery_percentage": 85.0, "plugged": "UNPLUGGED"},
            "audio_context": {"silence_likelihood": 0.9},
            "connectivity": {"wifi_connected": True},
            "place": {"spatial_cell": "tv58xy"},
        }
        for _ in range(5):
            self.daemon.process_observation(obs1)
        self.assertEqual(self.daemon.consecutive_dwell_steps, 5)

        # Huge time gap: 4 hours later (e.g. phone ran out of battery and turned off)
        obs2 = dict(obs1)
        obs2["timestamp_utc"] = "2026-09-03T14:00:00Z"
        obs2["observation_id"] = "gap-2"

        self.daemon.process_observation(obs2)
        # Consecutive dwell should have reset to 1 instead of accumulating to 6
        self.assertEqual(self.daemon.consecutive_dwell_steps, 1)

    def test_cross_boot_epoch_reset(self):
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as tmpdir:
            epoch_file = Path(tmpdir) / "boot_epoch.json"
            epoch_file.write_text('{"boot_id": "old-boot-id-1234"}', encoding="utf-8")

            d = EdgeManifoldDaemon(
                normalizer=self.normalizer,
                manifold=self.manifold,
                boot_epoch_file=epoch_file,
            )
            # Daemon detects reboot because current_boot_id != old-boot-id-1234
            self.assertEqual(len(d.coord_buffer), 0)
            self.assertEqual(d.consecutive_dwell_steps, 0)

    def test_negative_clock_jump_continuity_reset(self):
        obs1 = {
            "timestamp_utc": "2026-09-03T10:05:00Z",
            "observation_id": "clock-jump-1",
            "motion": {"stationary_likelihood": 0.9, "motion_energy": 0.05},
        }
        for _ in range(5):
            self.daemon.process_observation(obs1)
        self.assertEqual(self.daemon.consecutive_dwell_steps, 5)

        # NTP clock rollback: -120 seconds backwards
        obs2 = dict(obs1)
        obs2["timestamp_utc"] = "2026-09-03T10:03:00Z"
        obs2["observation_id"] = "clock-jump-2"
        self.daemon.process_observation(obs2)
        # Should reset continuity instead of continuing or computing negative time intervals
        self.assertEqual(self.daemon.consecutive_dwell_steps, 1)


if __name__ == "__main__":
    unittest.main()
