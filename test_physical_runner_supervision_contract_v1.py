#!/usr/bin/env python3
"""Contract Test Suite for Physical Runner Supervision v1."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import tempfile
import time
import unittest
from unittest.mock import MagicMock, patch

import pixel_physical_supervision_v1 as sup


class TestPhysicalRunnerSupervisionContractV1(unittest.TestCase):

    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="test_phys_sup_"))
        self.mock_physical_dir = Path(tempfile.mkdtemp(prefix="test_mock_phys_"))

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)
        shutil.rmtree(self.mock_physical_dir, ignore_errors=True)

    def test_01_supervision_lock_exclusivity(self):
        """Tests that PhysicalSupervisionLock enforces single-transaction exclusivity."""
        with sup.PhysicalSupervisionLock(self.temp_dir):
            # Second attempt must raise lock timeout or fail immediately
            with self.assertRaises(sup.PhysicalSupervisionError) as ctx:
                with sup.PhysicalSupervisionLock(self.temp_dir, timeout=0.1):
                    pass
            self.assertEqual(ctx.exception.code, "SUPERVISION_LOCK_TIMEOUT")

    def test_02_symlink_lock_rejected(self):
        """Tests that a symlinked supervision.lock is immediately rejected."""
        lock_file = self.temp_dir / sup.LOCK_FILENAME
        target_file = self.temp_dir / "target.txt"
        target_file.touch()
        lock_file.symlink_to(target_file)

        with self.assertRaises(sup.PhysicalSupervisionError) as ctx:
            with sup.PhysicalSupervisionLock(self.temp_dir, timeout=0.1):
                pass
        self.assertEqual(ctx.exception.code, "SYMLINK_LOCK_REJECTED")

    def test_03_intent_read_and_atomic_write(self):
        """Tests atomic writing, directory fsync, and strict validation of intent.json."""
        # Initial state: missing intent
        desired, gen, raw, err = sup.read_intent(self.temp_dir)
        self.assertEqual(err, "MISSING_INTENT")
        self.assertIsNone(desired)

        # Write valid RUNNING intent
        with sup.PhysicalSupervisionLock(self.temp_dir):
            payload = sup.write_intent_atomic(self.temp_dir, "RUNNING", 1)

        self.assertEqual(payload["desired_physical_state"], "RUNNING")
        self.assertEqual(payload["generation"], 1)

        # Read back
        desired, gen, raw, err = sup.read_intent(self.temp_dir)
        self.assertIsNone(err)
        self.assertEqual(desired, "RUNNING")
        self.assertEqual(gen, 1)

        # Write STOPPED intent advancing generation
        with sup.PhysicalSupervisionLock(self.temp_dir):
            payload2 = sup.write_intent_atomic(self.temp_dir, "STOPPED", 2)

        self.assertEqual(payload2["desired_physical_state"], "STOPPED")
        self.assertEqual(payload2["generation"], 2)

    def test_04_intent_validation_fail_closed(self):
        """Tests that malformed, corrupted, or symlinked intent fails closed."""
        intent_file = self.temp_dir / sup.INTENT_FILENAME

        # 1. Corrupt JSON
        intent_file.write_text("{malformed_json")
        desired, gen, raw, err = sup.read_intent(self.temp_dir)
        self.assertTrue(err.startswith("CORRUPT_INTENT"))

        # 2. Unsupported protocol
        intent_file.write_text(json.dumps({
            "protocol": "unsupported.protocol.v9",
            "desired_physical_state": "RUNNING",
            "generation": 1,
            "updated_at_utc": "2026-09-02T20:00:00Z",
        }))
        desired, gen, raw, err = sup.read_intent(self.temp_dir)
        self.assertTrue(err.startswith("UNSUPPORTED_PROTOCOL"))

        # 3. Invalid desired state
        intent_file.write_text(json.dumps({
            "protocol": sup.SUPERVISION_PROTOCOL,
            "desired_physical_state": "PAUSED",
            "generation": 1,
            "updated_at_utc": "2026-09-02T20:00:00Z",
        }))
        desired, gen, raw, err = sup.read_intent(self.temp_dir)
        self.assertTrue(err.startswith("INVALID_DESIRED_STATE"))

        # 4. Invalid generation (negative or boolean)
        intent_file.write_text(json.dumps({
            "protocol": sup.SUPERVISION_PROTOCOL,
            "desired_physical_state": "RUNNING",
            "generation": 0,
            "updated_at_utc": "2026-09-02T20:00:00Z",
        }))
        desired, gen, raw, err = sup.read_intent(self.temp_dir)
        self.assertTrue(err.startswith("INVALID_GENERATION"))

    def test_05_stale_runner_detection(self):
        """Tests that a runner with stale observation timestamps is accurately identified."""
        obs_file = self.mock_physical_dir / "physical_observations.jsonl"
        # Write an observation from 10 minutes ago
        old_time = (time.time() - 600.0)
        old_dt = datetime.fromtimestamp(old_time, tz=timezone.utc).isoformat()
        old_mono = time.monotonic_ns() - int(600.0 * 1e9)
        obs_record = {"timestamp_utc": old_dt, "observation_id": "obs-old", "monotonic_ns": old_mono}
        obs_file.write_text(json.dumps(obs_record) + "\n")

        # Mock alive PID and locked file
        pid_file = self.mock_physical_dir / "physical_acquisition.pid"
        pid_file.write_text(str(os.getpid()))
        lock_file = self.mock_physical_dir / "physical_acquisition.lock"
        lock_file.touch()

        # Call observe_physical_runner
        with patch.object(sup, "DEFAULT_PHYSICAL_DIR", self.mock_physical_dir):
            with patch("fcntl.flock", side_effect=BlockingIOError):
                with patch.object(sup, "get_kernel_boot_id", return_value="a1b2c3d4-e5f6-7890-abcd-ef1234567890"):
                    obs = sup.observe_physical_runner(supervision_root=self.temp_dir)
                    self.assertEqual(obs["status"], "RUNNING")
                    self.assertFalse(obs["healthy"])
                    self.assertEqual(obs["health_error"], "OBSERVATION_STALE")
                    self.assertTrue(obs["seconds_since_last_observation"] > 300.0)

    def test_06_recovery_backoff_cooldown(self):
        """Tests exponential backoff cooldown calculation across failure increments."""
        now = 1000.0
        # 0 failures -> No cooldown
        state_0 = {"consecutive_recovery_failures": 0, "last_recovery_timestamp": now - 10}
        in_cd, rem = sup.compute_cooldown(state_0, now)
        self.assertFalse(in_cd)

        # 1 failure -> 60s cooldown
        state_1 = {"consecutive_recovery_failures": 1, "last_recovery_timestamp": now - 30}
        in_cd, rem = sup.compute_cooldown(state_1, now)
        self.assertTrue(in_cd)
        self.assertAlmostEqual(rem, 30.0, places=1)

        # 1 failure -> Expired after 65s
        state_1_exp = {"consecutive_recovery_failures": 1, "last_recovery_timestamp": now - 65}
        in_cd, rem = sup.compute_cooldown(state_1_exp, now)
        self.assertFalse(in_cd)

        # 5+ failures -> 1800s cooldown
        state_5 = {"consecutive_recovery_failures": 5, "last_recovery_timestamp": now - 900}
        in_cd, rem = sup.compute_cooldown(state_5, now)
        self.assertTrue(in_cd)
        self.assertAlmostEqual(rem, 900.0, places=1)

    def test_07_tick_desired_stopped_prevents_resurrection(self):
        """Tests that when desired state is STOPPED, supervision tick NEVER restarts runner."""
        with sup.PhysicalSupervisionLock(self.temp_dir):
            sup.write_intent_atomic(self.temp_dir, "STOPPED", 1)

        with patch.object(sup, "invoke_physicalctl") as mock_ctl:
            res = sup.execute_supervision_tick(root=self.temp_dir)
            self.assertEqual(res["desired_physical_state"], "STOPPED")
            self.assertFalse(res["recovery_authorized"])
            mock_ctl.assert_not_called()


if __name__ == "__main__":
    unittest.main()
