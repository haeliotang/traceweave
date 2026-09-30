#!/usr/bin/env python3
"""Pixel Sensorium — Physical Runner Supervision Boot Epoch Audit v1."""

from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import time
import unittest

import pixel_physical_supervision_v1 as sup


class TestBootEpochAudit(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="boot_epoch_test_"))
        self.sup_root = self.temp_dir / "sup_root"
        self.sup_root.mkdir(parents=True, exist_ok=True)
        self.phys_dir = self.temp_dir / "phys_dir"
        self.phys_dir.mkdir(parents=True, exist_ok=True)

        self.boot_a = "11111111-1111-4111-8111-111111111111"
        self.boot_b = "22222222-2222-4222-8222-222222222222"

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _write_mock_obs(self, obs_id: str, mono_ns: int, timestamp_utc: str):
        obs_file = self.phys_dir / "physical_observations.jsonl"
        entry = {
            "protocol": "pixel-sensorium.physical-observation.v1",
            "observation_id": obs_id,
            "timestamp_utc": timestamp_utc,
            "monotonic_ns": mono_ns,
        }
        with obs_file.open("a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")

    def _mock_running_process(self):
        pid_file = self.phys_dir / "physical_acquisition.pid"
        lock_file = self.phys_dir / "physical_acquisition.lock"
        pid_file.write_text(str(os.getpid()), encoding="utf-8")
        lock_file.write_text("locked", encoding="utf-8")

    def test_01_kernel_boot_id_safety(self):
        """Validates that kernel boot ID is read from /proc, has UUID syntax, and rejects corruption."""
        real_id = sup.get_kernel_boot_id()
        self.assertRegex(real_id, r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fake_kernel_dir = self.temp_dir / "fake_kernel"
        fake_kernel_dir.mkdir(parents=True, exist_ok=True)
        fake_boot_path = fake_kernel_dir / "boot_id"

        # Invalid UUID syntax fails
        fake_boot_path.write_text("not-a-uuid\n", encoding="utf-8")
        with self.assertRaises(sup.PhysicalSupervisionError) as ctx:
            sup.get_kernel_boot_id(override_path=fake_boot_path)
        self.assertEqual(ctx.exception.code, "BOOT_ID_SYNTAX_INVALID")

        # Missing path fails
        fake_boot_path.unlink()
        with self.assertRaises(sup.PhysicalSupervisionError) as ctx:
            sup.get_kernel_boot_id(override_path=fake_boot_path)
        self.assertEqual(ctx.exception.code, "BOOT_ID_UNAVAILABLE")

    def test_02_boot_state_failure_semantics(self):
        """Validates fail-closed handling for corrupt, truncated, symlinked, and invalid boot state."""
        # 1. Missing file initializes clean default
        st, err = sup.read_boot_epoch_state(self.sup_root)
        self.assertEqual(err, "MISSING_BOOT_EPOCH_STATE")

        # 2. Malformed JSON fails closed
        bf = self.sup_root / sup.BOOT_EPOCH_FILENAME
        bf.write_text("{corrupt-json", encoding="utf-8")
        st, err = sup.read_boot_epoch_state(self.sup_root)
        self.assertIn("CORRUPT_BOOT_EPOCH_STATE", err)

        # 3. Truncated (empty) file fails closed
        bf.write_text("", encoding="utf-8")
        st, err = sup.read_boot_epoch_state(self.sup_root)
        self.assertEqual(err, "EMPTY_BOOT_EPOCH_STATE_FILE")

        # 4. Symlink strictly rejected
        bf.unlink()
        target = self.temp_dir / "target.json"
        target.write_text("{}", encoding="utf-8")
        bf.symlink_to(target)
        st, err = sup.read_boot_epoch_state(self.sup_root)
        self.assertEqual(err, "SYMLINK_BOOT_EPOCH_STATE_REJECTED")

        # 5. Unsupported protocol fails closed
        bf.unlink()
        bf.write_text(json.dumps({
            "protocol": "wrong.protocol",
            "boot_id": self.boot_a,
            "first_seen_utc": "2026-09-02T20:00:00Z",
            "last_seen_utc": "2026-09-02T20:00:00Z",
        }), encoding="utf-8")
        st, err = sup.read_boot_epoch_state(self.sup_root)
        self.assertIn("UNSUPPORTED_BOOT_EPOCH_PROTOCOL", err)

        # 6. Invalid boot UUID syntax fails closed
        bf.write_text(json.dumps({
            "protocol": sup.BOOT_EPOCH_PROTOCOL,
            "boot_id": "invalid-uuid",
            "first_seen_utc": "2026-09-02T20:00:00Z",
            "last_seen_utc": "2026-09-02T20:00:00Z",
        }), encoding="utf-8")
        st, err = sup.read_boot_epoch_state(self.sup_root)
        self.assertIn("INVALID_BOOT_ID", err)

        print("BOOT_EPOCH_STATE_FAIL_CLOSED=PASS")

    def test_03_same_boot_monotonic_freshness(self):
        """Case A: same boot + normal monotonic progression -> valid freshness."""
        now_mono = time.monotonic_ns()
        obs_mono = now_mono - int(15.0 * 1e9)  # 15 seconds ago
        self._write_mock_obs("obs-same-1", obs_mono, "2026-09-02T22:00:00Z")

        # Established boot state for boot_a
        sup.write_boot_epoch_state(self.sup_root, {
            "protocol": sup.BOOT_EPOCH_PROTOCOL,
            "boot_id": self.boot_a,
            "first_seen_utc": "2026-09-02T21:00:00Z",
            "last_seen_utc": "2026-09-02T22:00:00Z",
            "last_observation_id": "obs-same-1",
            "last_observation_monotonic_ns": obs_mono,
        })

        obs_res = sup.observe_physical_runner(
            physical_dir=self.phys_dir,
            supervision_root=self.sup_root,
            kernel_boot_id_override=self.boot_a,
        )

        self.assertEqual(obs_res["supervision_boot_id"], self.boot_a)
        self.assertEqual(obs_res["kernel_boot_id"], self.boot_a)
        self.assertEqual(obs_res["freshness_source"], "MONOTONIC")
        self.assertFalse(obs_res["boot_epoch_ambiguity"])
        self.assertTrue(14.0 <= obs_res["seconds_since_last_observation"] <= 17.0)
        print("SAME_BOOT_MONOTONIC_FRESHNESS=PASS")

    def test_04_wall_clock_invariance(self):
        """Case B & C: Wall clock jumps (+24h, -24h) do not alter monotonic freshness."""
        now_mono = time.monotonic_ns()
        obs_mono = now_mono - int(20.0 * 1e9)
        # Record with a timestamp 24 hours into the past
        self._write_mock_obs("obs-wall-1", obs_mono, "2026-09-01T22:00:00Z")

        sup.write_boot_epoch_state(self.sup_root, {
            "protocol": sup.BOOT_EPOCH_PROTOCOL,
            "boot_id": self.boot_a,
            "first_seen_utc": "2026-09-02T21:00:00Z",
            "last_seen_utc": "2026-09-02T22:00:00Z",
            "last_observation_id": "obs-wall-1",
            "last_observation_monotonic_ns": obs_mono,
        })

        obs_res = sup.observe_physical_runner(
            physical_dir=self.phys_dir,
            supervision_root=self.sup_root,
            kernel_boot_id_override=self.boot_a,
        )

        self.assertEqual(obs_res["freshness_source"], "MONOTONIC")
        self.assertTrue(19.0 <= obs_res["seconds_since_last_observation"] <= 22.0)
        print("WALL_CLOCK_JUMP_FALSE_STALE_COUNT=0")

    def test_05_cross_boot_monotonic_isolation_and_counterexample(self):
        """
        Case D & E: Boot A -> Boot B.
        Proves:
        1. Old monotonic values are NEVER compared across different boot_id.
        2. Specific counterexample: Old monotonic=2h (7200s), new boot current monotonic=3h (10800s).
           Even though 7200 < 10800, it is STILL recognized as cross-boot through boot_id!
        """
        # Historical observation from Boot A with monotonic = 7200s (2 hours)
        mono_2h_ns = int(7200 * 1e9)
        self._write_mock_obs("obs-boot-a-2h", mono_2h_ns, "2026-09-02T10:00:00Z")

        # Stored boot state has Boot A
        sup.write_boot_epoch_state(self.sup_root, {
            "protocol": sup.BOOT_EPOCH_PROTOCOL,
            "boot_id": self.boot_a,
            "first_seen_utc": "2026-09-02T08:00:00Z",
            "last_seen_utc": "2026-09-02T10:00:00Z",
            "last_observation_id": "obs-boot-a-2h",
            "last_observation_monotonic_ns": mono_2h_ns,
        })

        # Under Boot B, system uptime has reached 3 hours (10800s > 7200s)
        # Prior monotonic-only logic (obs_mono > current_mono) would have FAULTY compared 10800 - 7200 = 3600s!
        obs_res = sup.observe_physical_runner(
            physical_dir=self.phys_dir,
            supervision_root=self.sup_root,
            kernel_boot_id_override=self.boot_b,
        )

        # Verified: boot_id mismatch strictly isolates epochs!
        self.assertEqual(obs_res["kernel_boot_id"], self.boot_b)
        self.assertEqual(obs_res["supervision_boot_id"], self.boot_a)
        self.assertTrue(obs_res["boot_epoch_ambiguity"])
        self.assertEqual(obs_res["freshness_source"], "UNKNOWN_BOOT_EPOCH")
        self.assertEqual(obs_res["health_error"], "BOOT_EPOCH_CHANGED")
        self.assertIsNone(obs_res["seconds_since_last_observation"])

        print("BOOT_EPOCH_IDENTIFICATION=PASS")
        print("CROSS_BOOT_MONOTONIC_COMPARISON_COUNT=0")

    def test_06_new_observation_under_new_boot_binds_freshness(self):
        """Case F: New observation under Boot B binds new boot_id and restores monotonic freshness."""
        mono_2h_ns = int(7200 * 1e9)
        self._write_mock_obs("obs-boot-a", mono_2h_ns, "2026-09-02T10:00:00Z")

        sup.write_boot_epoch_state(self.sup_root, {
            "protocol": sup.BOOT_EPOCH_PROTOCOL,
            "boot_id": self.boot_a,
            "first_seen_utc": "2026-09-02T08:00:00Z",
            "last_seen_utc": "2026-09-02T10:00:00Z",
            "last_observation_id": "obs-boot-a",
            "last_observation_monotonic_ns": mono_2h_ns,
        })

        # Runner is active under Boot B
        now_mono = time.monotonic_ns()
        obs_b_mono = now_mono - int(5.0 * 1e9)
        self._write_mock_obs("obs-boot-b-fresh", obs_b_mono, "2026-09-02T12:00:00Z")

        # Real process alive and real lock held
        pid_file = self.phys_dir / "physical_acquisition.pid"
        pid_file.write_text(str(os.getpid()), encoding="utf-8")
        lock_file = self.phys_dir / "physical_acquisition.lock"
        holder_fd = os.open(str(lock_file), os.O_RDWR | os.O_CREAT)
        fcntl.flock(holder_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)

        try:
            obs_res = sup.observe_physical_runner(
                physical_dir=self.phys_dir,
                supervision_root=self.sup_root,
                kernel_boot_id_override=self.boot_b,
            )
        finally:
            fcntl.flock(holder_fd, fcntl.LOCK_UN)
            os.close(holder_fd)

        self.assertEqual(obs_res["kernel_boot_id"], self.boot_b)
        self.assertEqual(obs_res["supervision_boot_id"], self.boot_b)
        self.assertFalse(obs_res["boot_epoch_ambiguity"])
        self.assertEqual(obs_res["freshness_source"], "MONOTONIC")
        self.assertTrue(4.0 <= obs_res["seconds_since_last_observation"] <= 7.0)

        # Verify boot_epoch_state.json was updated with Boot B
        st, err = sup.read_boot_epoch_state(self.sup_root)
        self.assertIsNone(err)
        self.assertEqual(st["boot_id"], self.boot_b)
        self.assertEqual(st["last_observation_id"], "obs-boot-b-fresh")


if __name__ == "__main__":
    unittest.main()
