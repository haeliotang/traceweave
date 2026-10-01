"""
Unit and integration tests for Phone Edge Stream Daemon.
"""

from collections import deque
from pathlib import Path
import tempfile
import unittest

from traceform.streaming.edge_quantizer import EdgeStreamQuantizer
from traceform.intervention.resonance_hooks import AmbientResonanceEngine
from scripts.phone_edge_stream_daemon import compute_sliding_entropy, EdgeDaemon

REPO_ROOT = Path(__file__).resolve().parent.parent


class TestPhoneDaemon(unittest.TestCase):
    def setUp(self):
        self.spec_file = (
            REPO_ROOT / "artifacts" / "representation_v1" / "representation_spec.json"
        )
        self.quantizer = EdgeStreamQuantizer.from_spec_file(self.spec_file)
        self.resonance_engine = AmbientResonanceEngine(
            max_regime_dwell_steps=5, entropy_spike_threshold=1.5
        )

    def test_sliding_entropy_computation(self):
        # All identical: entropy = 0.0
        q_static = deque(["S01"] * 10)
        self.assertAlmostEqual(compute_sliding_entropy(q_static), 0.0, places=3)

        # Uniform alternating: entropy > 0.9
        q_dynamic = deque(["S01", "S02"] * 5)
        self.assertGreater(compute_sliding_entropy(q_dynamic), 0.9)

    def test_daemon_process_observation(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            out_stream = Path(tmp_dir) / "stream.jsonl"
            evt_file = Path(tmp_dir) / "events.jsonl"

            daemon = EdgeDaemon(
                quantizer=self.quantizer,
                resonance_engine=self.resonance_engine,
                output_stream_path=out_stream,
                events_path=evt_file,
                buffer_size=10,
            )

            raw_obs = {
                "timestamp_utc": "2026-09-02T22:48:12Z",
                "observation_id": "test-obs-01",
                "motion": {"stationary_likelihood": 0.9, "motion_energy": 0.05},
                "environment": {
                    "ambient_light_lux": 1.0,
                    "barometric_pressure_hpa": 1013.25,
                    "device_temp_c": 28.0,
                },
                "device_relation": {
                    "battery_percentage": 85.0,
                    "plugged": "UNPLUGGED",
                    "status": "DISCHARGING",
                },
                "audio_context": {
                    "silence_likelihood": 0.95,
                    "rms_loudness": 0.01,
                    "spectral_entropy": 0.1,
                },
                "connectivity": {
                    "wifi_fingerprint": "wifi_home_1",
                    "wifi_connected": True,
                    "wifi_rssi": -55.0,
                },
                "place": {"spatial_cell": "tv58qxy"},
            }

            rec, evt = daemon.process_observation(raw_obs)
            self.assertEqual(rec["observation_id"], "test-obs-01")
            self.assertIn("state_token", rec)
            self.assertIn("latency_ms", rec)
            self.assertLess(rec["latency_ms"], 5.0)  # Sub-millisecond to low ms

    def test_daemon_dwell_trigger(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            out_stream = Path(tmp_dir) / "stream.jsonl"
            evt_file = Path(tmp_dir) / "events.jsonl"

            daemon = EdgeDaemon(
                quantizer=self.quantizer,
                resonance_engine=self.resonance_engine,  # dwell threshold = 5
                output_stream_path=out_stream,
                events_path=evt_file,
            )

            raw_obs = {
                "timestamp_utc": "2026-09-02T22:48:12Z",
                "observation_id": "test-obs",
                "motion": {"stationary_likelihood": 0.9, "motion_energy": 0.05},
                "environment": {"ambient_light_lux": 1.0},
                "device_relation": {"plugged": "UNPLUGGED"},
                "connectivity": {"wifi_fingerprint": "wifi_home_1"},
            }

            # Feed 4 times: no event
            for _ in range(4):
                _, evt = daemon.process_observation(raw_obs)
                self.assertIsNone(evt)

            # 5th time: triggers REGIME_PROLONGED_DWELL
            _, evt = daemon.process_observation(raw_obs)
            self.assertIsNotNone(evt)
            self.assertEqual(evt.trigger_type.value, "REGIME_PROLONGED_DWELL")


if __name__ == "__main__":
    unittest.main()
