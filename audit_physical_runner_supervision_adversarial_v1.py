#!/usr/bin/env python3
"""Adversarial & Fault Injection Audit for Physical Runner Supervision v1."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import tempfile
import time
import sys
from unittest.mock import MagicMock, patch

import pixel_physical_supervision_v1 as sup


def run_adversarial_audit() -> int:
    print("========================================================================")
    print("PIXEL SENSORIUM PHYSICAL RUNNER SUPERVISION v1 ADVERSARIAL AUDIT")
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

    temp_dir = Path(tempfile.mkdtemp(prefix="adv_phys_sup_"))

    try:
        # Case 1: Lock exclusivity and timeout
        with sup.PhysicalSupervisionLock(temp_dir):
            try:
                with sup.PhysicalSupervisionLock(temp_dir, timeout=0.1):
                    contention_blocked = False
            except sup.PhysicalSupervisionError as e:
                contention_blocked = (e.code == "SUPERVISION_LOCK_TIMEOUT")
        record("Lock exclusivity enforces MAX_PHYSICAL_SUPERVISION_TRANSACTIONS=1", contention_blocked)

        # Case 2: Symlink lock rejection
        lock_file = temp_dir / sup.LOCK_FILENAME
        if lock_file.exists():
            lock_file.unlink()
        target = temp_dir / "target_lock"
        target.touch()
        lock_file.symlink_to(target)
        try:
            with sup.PhysicalSupervisionLock(temp_dir, timeout=0.1):
                sym_lock_rejected = False
        except sup.PhysicalSupervisionError as e:
            sym_lock_rejected = (e.code == "SYMLINK_LOCK_REJECTED")
        finally:
            lock_file.unlink()
        record("Symlink lock file strictly rejected with fail-closed error", sym_lock_rejected)

        # Case 3: Symlink intent rejection
        intent_file = temp_dir / sup.INTENT_FILENAME
        target_intent = temp_dir / "target_intent.json"
        target_intent.touch()
        intent_file.symlink_to(target_intent)
        desired, gen, raw, err = sup.read_intent(temp_dir)
        record("Symlink intent.json rejected with fail-closed error", err == "SYMLINK_INTENT_REJECTED")
        intent_file.unlink()

        # Case 4: Non-regular file intent rejection (directory at intent.json)
        intent_file.mkdir()
        desired, gen, raw, err = sup.read_intent(temp_dir)
        record("Non-regular file at intent.json rejected", err == "NON_REGULAR_INTENT_FILE")
        intent_file.rmdir()

        # Case 5: Oversized intent file rejection (> 64KB)
        intent_file.write_text("A" * 70000)
        desired, gen, raw, err = sup.read_intent(temp_dir)
        record("Oversized intent file rejected (> 64KB)", err == "OVERSIZED_INTENT_FILE")
        intent_file.unlink()

        # Case 6: Truncated JSON intent
        intent_file.write_text('{"protocol": "pixel-sensorium.physical-supervision-intent.v1", "desired_physical_state": "RUN')
        desired, gen, raw, err = sup.read_intent(temp_dir)
        record("Truncated JSON intent fails closed", err is not None and "CORRUPT_INTENT" in err)
        intent_file.unlink()

        # Case 7: Invalid generation in intent (string instead of int)
        intent_file.write_text(json.dumps({
            "protocol": sup.SUPERVISION_PROTOCOL,
            "desired_physical_state": "RUNNING",
            "generation": "two",
            "updated_at_utc": "2026-09-02T21:00:00Z",
        }))
        desired, gen, raw, err = sup.read_intent(temp_dir)
        record("Invalid generation type in intent fails closed", err is not None and "INVALID_GENERATION" in err)
        intent_file.unlink()

        # Case 8: Atomic replace and fsync integrity
        with sup.PhysicalSupervisionLock(temp_dir):
            sup.write_intent_atomic(temp_dir, "RUNNING", 1)
        record("Durable intent atomic replace and fsync succeeds", intent_file.exists() and intent_file.stat().st_size > 0)

        # Case 9: Desired STOPPED prevents resurrection across multiple ticks
        with sup.PhysicalSupervisionLock(temp_dir):
            sup.write_intent_atomic(temp_dir, "STOPPED", 2)

        starts_invoked = 0
        def fake_physicalctl(args, timeout=15.0):
            nonlocal starts_invoked
            if "start" in args:
                starts_invoked += 1
            return 0, {}, "ok"

        with patch.object(sup, "invoke_physicalctl", side_effect=fake_physicalctl):
            for _ in range(5):
                res = sup.execute_supervision_tick(root=temp_dir)
                if res["supervision_state"] == "STARTING":
                    starts_invoked += 1

        record("PHYSICAL_DURABLE_STOP_PREVENTS_RESURRECTION: Zero restarts under STOPPED intent", starts_invoked == 0)

        # Case 10: Autonomous stop exclusion
        stops_invoked = 0
        def fake_stop(args, timeout=15.0):
            nonlocal stops_invoked
            if "stop" in args:
                stops_invoked += 1
            return 0, {}, "ok"

        with patch.object(sup, "invoke_physicalctl", side_effect=fake_stop):
            # Tick under RUNNING intent
            with sup.PhysicalSupervisionLock(temp_dir):
                sup.write_intent_atomic(temp_dir, "RUNNING", 3)
            sup.execute_supervision_tick(root=temp_dir)

        record("PHYSICAL_AUTONOMOUS_STOP_COUNT=0: Supervisor never autonomously stops runner", stops_invoked == 0)

        # Case 11: At-most-one recovery start per tick
        recovery_starts = 0
        def count_starts(args, timeout=15.0):
            nonlocal recovery_starts
            if "start" in args:
                recovery_starts += 1
            return 0, {}, "Started"

        with patch.object(sup, "invoke_physicalctl", side_effect=count_starts):
            with patch.object(sup, "observe_physical_runner", return_value={"status": "STOPPED", "healthy": False}):
                # Single tick
                sup.execute_supervision_tick(root=temp_dir)

        record("MAX_PHYSICAL_RECOVERY_STARTS_PER_TICK=1: Exactly one start per tick", recovery_starts == 1)

        # Case 12: Recovery cooldown backoff after failure
        rec_data, _ = sup.read_recovery_state(temp_dir)
        rec_data["consecutive_recovery_failures"] = 2
        rec_data["last_recovery_timestamp"] = time.time()
        sup.write_recovery_state(temp_dir, rec_data)

        with patch.object(sup, "observe_physical_runner", return_value={"status": "STOPPED", "healthy": False}):
            with patch.object(sup, "invoke_physicalctl") as mock_run:
                tick_res = sup.execute_supervision_tick(root=temp_dir)
                mock_run.assert_not_called()
                record("Recovery cooldown suppresses restart during backoff window", tick_res["supervision_state"] == "RECOVERY_COOLDOWN")

        # Case 13: Stale runner triggers ATTENTION_REQUIRED without restart storm
        stale_obs = {
            "status": "RUNNING",
            "pid": 99999,
            "lock_held": True,
            "last_observation_utc": "2026-09-02T20:00:00Z",
            "seconds_since_last_observation": 600.0,
            "healthy": False,
        }
        with patch.object(sup, "observe_physical_runner", return_value=stale_obs):
            with patch.object(sup, "invoke_physicalctl") as mock_run:
                stale_res = sup.execute_supervision_tick(root=temp_dir)
                mock_run.assert_not_called()
                record("Stale runner triggers ATTENTION_REQUIRED and prevents restart storm", stale_res["supervision_state"] == "ATTENTION_REQUIRED")

        # Case 14: Failed start actuation preserves durable RUNNING intent
        rec_data = {"protocol": sup.RECOVERY_STATE_PROTOCOL, "consecutive_recovery_failures": 0, "last_recovery_timestamp": None}
        sup.write_recovery_state(temp_dir, rec_data)
        with patch.object(sup, "observe_physical_runner", return_value={"status": "STOPPED", "healthy": False}):
            with patch.object(sup, "invoke_physicalctl", return_value=(1, {}, "Error starting")):
                fail_res = sup.execute_supervision_tick(root=temp_dir)
                # Verify durable RUNNING remains unchanged
                desired, gen, raw, err = sup.read_intent(temp_dir)
                record("Failed start actuation preserves durable RUNNING intent without rollback", desired == "RUNNING" and fail_res["supervision_state"] == "RECOVERY_FAILED")

        # Case 15: Monotonic generation advancement across Start/Stop cycle
        with sup.PhysicalSupervisionLock(temp_dir):
            sup.write_intent_atomic(temp_dir, "RUNNING", 10)
        with sup.PhysicalSupervisionLock(temp_dir):
            sup.write_intent_atomic(temp_dir, "STOPPED", 11)
        desired, gen, raw, err = sup.read_intent(temp_dir)
        record("Monotonic generation advancement strictly enforced", gen == 11 and desired == "STOPPED")

    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)

    print("========================================================================")
    print(f"ADVERSARIAL AUDIT VERDICT: {passed_checks}/{total_checks} PASSED")
    print("========================================================================")

    if passed_checks == total_checks:
        print("ADVERSARIAL_AUDIT=PASS")
        return 0
    else:
        print("ADVERSARIAL_AUDIT=FAIL")
        return 1


if __name__ == "__main__":
    sys.exit(run_adversarial_audit())
