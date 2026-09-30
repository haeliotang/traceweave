#!/usr/bin/env python3
"""Clock Adversarial & Monotonic Freshness Audit for Physical Runner Supervision v1."""

from datetime import datetime, timezone, timedelta
import json
import os
from pathlib import Path
import shutil
import tempfile
import time
import sys
from unittest.mock import MagicMock, patch

import pixel_physical_supervision_v1 as sup


def audit_clock_semantics() -> int:
    print("========================================================================")
    print("PIXEL SENSORIUM PHYSICAL RUNNER SUPERVISION CLOCK ADVERSARIAL AUDIT v1")
    print("========================================================================")

    passed_checks = 0
    total_checks = 0

    def record(desc: str, cond: bool, detail: str = ""):
        nonlocal passed_checks, total_checks
        total_checks += 1
        if cond:
            print(f"[{total_checks:02d}] PASS: {desc}")
            passed_checks += 1
        else:
            print(f"[{total_checks:02d}] FAIL: {desc} - {detail}")

    mock_dir = Path(tempfile.mkdtemp(prefix="clock_audit_"))

    try:
        pid_file = mock_dir / "physical_acquisition.pid"
        pid_file.write_text(str(os.getpid()))
        lock_file = mock_dir / "physical_acquisition.lock"
        lock_file.touch()
        obs_file = mock_dir / "physical_observations.jsonl"

        now_mono = time.monotonic_ns()
        now_utc = datetime.now(timezone.utc)

        # Case A: Wall clock jumps forward by +24 hours
        # Monotonic time is 15.0s ago (fresh), but UTC timestamp looks 24h old
        wall_jump_false_stale_count = 0
        obs_a = {
            "timestamp_utc": (now_utc - timedelta(hours=24)).isoformat(),
            "monotonic_ns": now_mono - int(15.0 * 1e9),  # 15s ago
        }
        obs_file.write_text(json.dumps(obs_a) + "\n")

        with patch.object(sup, "DEFAULT_PHYSICAL_DIR", mock_dir):
            with patch("fcntl.flock", side_effect=BlockingIOError):
                obs = sup.observe_physical_runner()
                if not obs["healthy"] or obs.get("health_error") == "OBSERVATION_STALE":
                    wall_jump_false_stale_count += 1
                record(
                    "Case A: Wall clock jump +24h with normal monotonic elapsed MUST NOT falsely classify stale",
                    obs["healthy"] is True and obs["freshness_source"] == "MONOTONIC" and obs["seconds_since_last_observation"] <= 300.0,
                )

        # Case B: Wall clock jumps backward
        # Monotonic time is 400.0s ago (stale), but UTC timestamp looks 5s ago
        obs_b = {
            "timestamp_utc": (now_utc - timedelta(seconds=5)).isoformat(),
            "monotonic_ns": now_mono - int(400.0 * 1e9),  # 400s ago (> 300s)
        }
        obs_file.write_text(json.dumps(obs_b) + "\n")

        with patch.object(sup, "DEFAULT_PHYSICAL_DIR", mock_dir):
            with patch("fcntl.flock", side_effect=BlockingIOError):
                obs = sup.observe_physical_runner()
                record(
                    "Case B: Wall clock backward jump MUST NOT suppress genuine monotonic stale condition",
                    obs["healthy"] is False and obs["freshness_source"] == "MONOTONIC" and obs["health_error"] == "OBSERVATION_STALE",
                )

        # Case C: Monotonic duration > 300s (stale condition detected)
        obs_c = {
            "timestamp_utc": now_utc.isoformat(),
            "monotonic_ns": now_mono - int(350.0 * 1e9),  # 350s ago
        }
        obs_file.write_text(json.dumps(obs_c) + "\n")

        with patch.object(sup, "DEFAULT_PHYSICAL_DIR", mock_dir):
            with patch("fcntl.flock", side_effect=BlockingIOError):
                obs = sup.observe_physical_runner()
                record(
                    "Case C: Monotonic duration > 300.0s correctly identified as stale",
                    obs["healthy"] is False and obs["health_error"] == "OBSERVATION_STALE" and obs["seconds_since_last_observation"] >= 350.0,
                )

        # Case D: Monotonic boot discontinuity (current monotonic LESS than stored monotonic)
        # Stored observation has monotonic_ns from prior boot cycle (e.g. 500 hours uptime)
        obs_d = {
            "timestamp_utc": now_utc.isoformat(),
            "monotonic_ns": now_mono + int(10000.0 * 1e9),  # Far in the future relative to current boot
        }
        obs_file.write_text(json.dumps(obs_d) + "\n")

        with patch.object(sup, "DEFAULT_PHYSICAL_DIR", mock_dir):
            with patch("fcntl.flock", side_effect=BlockingIOError):
                obs = sup.observe_physical_runner()
                record(
                    "Case D.1: Boot discontinuity classified as UNKNOWN_BOOT_EPOCH with no negative freshness",
                    obs["healthy"] is False and obs["boot_epoch_ambiguity"] is True and obs["freshness_source"] == "UNKNOWN_BOOT_EPOCH" and obs["seconds_since_last_observation"] is None,
                )

        # Case D.2: Supervisor tick under boot discontinuity triggers ATTENTION_REQUIRED, NOT restart storm
        sup_temp = Path(tempfile.mkdtemp(prefix="sup_clock_"))
        try:
            with sup.PhysicalSupervisionLock(sup_temp):
                sup.write_intent_atomic(sup_temp, "RUNNING", 1)
            mock_obs_d = {
                "status": "RUNNING",
                "pid": os.getpid(),
                "lock_held": True,
                "boot_epoch_ambiguity": True,
                "healthy": False,
                "health_error": "UNKNOWN_BOOT_EPOCH",
            }
            with patch.object(sup, "observe_physical_runner", return_value=mock_obs_d):
                with patch.object(sup, "invoke_physicalctl") as mock_start:
                    res = sup.execute_supervision_tick(sup_temp)
                    mock_start.assert_not_called()
                    record(
                        "Case D.2: Boot epoch ambiguity yields ATTENTION_REQUIRED and prevents restart storm",
                        res["supervision_state"] == "ATTENTION_REQUIRED" and res["intent_error"] == "UNKNOWN_BOOT_EPOCH" and res["actuated_recovery_start"] is False,
                    )
        finally:
            shutil.rmtree(sup_temp, ignore_errors=True)

        record("WALL_CLOCK_JUMP_FALSE_STALE_COUNT=0 verified", wall_jump_false_stale_count == 0)

    finally:
        shutil.rmtree(mock_dir, ignore_errors=True)

    print("========================================================================")
    print(f"CLOCK ADVERSARIAL AUDIT VERDICT: {passed_checks}/{total_checks} PASSED")
    print("========================================================================")

    if passed_checks == total_checks and wall_jump_false_stale_count == 0:
        print("MONOTONIC_STALE_DETECTION=PASS")
        print("WALL_CLOCK_JUMP_FALSE_STALE_COUNT=0")
        return 0
    else:
        print("MONOTONIC_STALE_DETECTION=FAIL")
        return 1


if __name__ == "__main__":
    sys.exit(audit_clock_semantics())
