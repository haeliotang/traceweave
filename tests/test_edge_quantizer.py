# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Tests for Edge Stream Quantizer.
"""

import json
from pathlib import Path
import time
import unittest

from traceform.streaming.edge_quantizer import EdgeStreamQuantizer

REPO_ROOT = Path(__file__).resolve().parent.parent


class TestEdgeQuantizer(unittest.TestCase):
    def setUp(self):
        self.spec_path = (
            REPO_ROOT / "artifacts" / "representation_v1" / "representation_spec.json"
        )
        if not self.spec_path.is_file():
            self.skipTest(f"Missing spec file {self.spec_path}")
        self.quantizer = EdgeStreamQuantizer.from_spec_file(self.spec_path)

    def test_process_raw_frame_speed_and_format(self):
        sample_obs = {
            "observation_id": "test-obs-1",
            "timestamp_utc": "2026-09-03T12:00:00Z",
            "motion": {
                "accel_magnitude": 9.8,
                "accel_rms": 5.6,
                "motion_energy": 0.1,
                "stationary_likelihood": 0.95,
            },
            "environment": {
                "ambient_light_lux": 150.0,
                "pressure_hpa": 915.0,
                "device_temp_c": 27.0,
            },
            "device_relation": {
                "plugged": "AC",
                "status": "CHARGING",
                "battery_percentage": 90,
            },
            "connectivity": {
                "wifi_fingerprint": "d6c2cf9dba",
                "wifi_connected": True,
                "wifi_rssi": -50,
            },
            "audio_context": {
                "silence_likelihood": 1.0,
                "rms_loudness": 0.0,
                "spectral_entropy": 0.0,
            },
        }

        t0 = time.time()
        for _ in range(50):
            token, sig, topo = self.quantizer.process_raw_frame(sample_obs)
        elapsed_per_frame_ms = ((time.time() - t0) / 50.0) * 1000.0

        self.assertTrue(token.startswith("S"))
        self.assertIn("WIFI_", sig)
        self.assertLess(elapsed_per_frame_ms, 5.0)  # Sub-5ms latency constraint

    def test_parity_with_offline_sequence(self):
        raw_path = (
            REPO_ROOT
            / "data"
            / "raw"
            / ".pixel_sensorium"
            / "physical_observations.jsonl"
        )
        seq_path = (
            REPO_ROOT
            / "artifacts"
            / "representation_v1"
            / "state_sequence_14d_v1.jsonl"
        )
        if not raw_path.is_file() or not seq_path.is_file():
            self.skipTest("Missing raw ledgers or sequence file")

        # Load first 50 sequence records
        seq_records = []
        with open(seq_path, "r", encoding="utf-8") as f:
            for i, line in enumerate(f):
                if i >= 50:
                    break
                if line.strip():
                    seq_records.append(json.loads(line.strip()))

        needed_ids = {r["observation_id"]: r for r in seq_records}
        matched = 0
        with open(raw_path, "r", encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                raw_obs = json.loads(line.strip())
                oid = raw_obs.get("observation_id")
                if oid in needed_ids:
                    expected_token = needed_ids[oid]["state_token"]
                    expected_sig = needed_ids[oid]["state_signature"]
                    # Edge stream process
                    actual_token, actual_sig, _ = self.quantizer.process_raw_frame(
                        raw_obs
                    )
                    self.assertEqual(actual_token, expected_token)
                    self.assertEqual(actual_sig, expected_sig)
                    matched += 1
                    if matched >= len(needed_ids):
                        break

        self.assertEqual(matched, len(needed_ids))


if __name__ == "__main__":
    unittest.main()
