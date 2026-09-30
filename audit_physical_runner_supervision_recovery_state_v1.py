#!/usr/bin/env python3
"""Recovery State Authority Boundary & Failure Semantics Audit for Physical Runner Supervision v1."""

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


def audit_recovery_state() -> int:
    print("========================================================================")
    print("PIXEL SENSORIUM PHYSICAL RUNNER SUPERVISION RECOVERY STATE AUDIT v1")
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

    temp_dir = Path(tempfile.mkdtemp(prefix="rec_state_audit_"))
    recovery_backoff_bypass_count = 0

    try:
        rec_file = temp_dir / sup.RECOVERY_STATE_FILENAME

        # Ensure valid durable RUNNING intent
        with sup.PhysicalSupervisionLock(temp_dir):
            sup.write_intent_atomic(temp_dir, "RUNNING", 1)

        # 1. Missing recovery_state.json: initializes bounded default state under valid RUNNING intent
        state, err = sup.read_recovery_state(temp_dir)
        record("Missing recovery_state.json initializes default bounded state", err is None and state["consecutive_recovery_failures"] == 0)

        # 2. Malformed JSON recovery_state.json fails closed
        rec_file.write_text("{malformed_json")
        state, err = sup.read_recovery_state(temp_dir)
        record("Malformed JSON fails closed with error code", state is None and err is not None and "CORRUPT" in err)

        # Tick under malformed recovery_state MUST fail closed as ATTENTION_REQUIRED and NOT start runner
        with patch.object(sup, "observe_physical_runner", return_value={"status": "STOPPED", "healthy": False}):
            with patch.object(sup, "invoke_physicalctl") as mock_start:
                res = sup.execute_supervision_tick(temp_dir)
                mock_start.assert_not_called()
                if res.get("actuated_recovery_start") is True:
                    recovery_backoff_bypass_count += 1
                record(
                    "Tick under malformed recovery state fails closed as ATTENTION_REQUIRED with recovery_authorized=False",
                    res["supervision_state"] == "ATTENTION_REQUIRED" and res["recovery_authorized"] is False and res["actuated_recovery_start"] is False,
                )
        rec_file.unlink()

        # 3. Truncated recovery_state.json fails closed
        rec_file.write_text('{"protocol": "pixel-sensorium.physical-supervision-recovery-state.v1", "consecutive_recovery_failures":')
        state, err = sup.read_recovery_state(temp_dir)
        record("Truncated recovery_state fails closed", state is None and err is not None)
        rec_file.unlink()

        # 4. Symlinked recovery_state.json is rejected
        target_rec = temp_dir / "target_rec.json"
        target_rec.touch()
        rec_file.symlink_to(target_rec)
        state, err = sup.read_recovery_state(temp_dir)
        record("Symlinked recovery_state.json strictly rejected", state is None and err == "SYMLINK_RECOVERY_STATE_REJECTED")
        rec_file.unlink()
        target_rec.unlink()

        # 5. Invalid failure count (negative or non-integer)
        rec_file.write_text(json.dumps({
            "protocol": sup.RECOVERY_STATE_PROTOCOL,
            "consecutive_recovery_failures": -3,
            "last_recovery_timestamp": time.time(),
        }))
        state, err = sup.read_recovery_state(temp_dir)
        record("Negative failure count rejected", state is None and "INVALID_FAILURE_COUNT" in str(err))
        rec_file.unlink()

        # 6. Future recovery timestamp (detects clock corruption/tampering)
        rec_file.write_text(json.dumps({
            "protocol": sup.RECOVERY_STATE_PROTOCOL,
            "consecutive_recovery_failures": 1,
            "last_recovery_timestamp": time.time() + 50000.0,
        }))
        state, err = sup.read_recovery_state(temp_dir)
        record("Future recovery timestamp rejected", state is None and err == "FUTURE_RECOVERY_TIMESTAMP")
        rec_file.unlink()

        # 7. Unsupported recovery protocol
        rec_file.write_text(json.dumps({
            "protocol": "unsupported.recovery.v99",
            "consecutive_recovery_failures": 1,
            "last_recovery_timestamp": time.time(),
        }))
        state, err = sup.read_recovery_state(temp_dir)
        record("Unsupported recovery protocol rejected", state is None and "UNSUPPORTED_RECOVERY_PROTOCOL" in str(err))
        rec_file.unlink()

        # 8. Recovery state NEVER authorizes start when intent is STOPPED
        with sup.PhysicalSupervisionLock(temp_dir):
            sup.write_intent_atomic(temp_dir, "STOPPED", 2)
        # Even with 0 failures and expired cooldown, desired STOPPED must dominate
        rec_file.write_text(json.dumps({
            "protocol": sup.RECOVERY_STATE_PROTOCOL,
            "consecutive_recovery_failures": 0,
            "last_recovery_timestamp": None,
        }))
        with patch.object(sup, "observe_physical_runner", return_value={"status": "STOPPED", "healthy": False}):
            with patch.object(sup, "invoke_physicalctl") as mock_start:
                res = sup.execute_supervision_tick(temp_dir)
                mock_start.assert_not_called()
                record(
                    "Recovery state NEVER overrides intent: STOPPED intent forbids start",
                    res["desired_physical_state"] == "STOPPED" and res["recovery_authorized"] is False and res["actuated_recovery_start"] is False,
                )

        record("RECOVERY_BACKOFF_BYPASS_COUNT=0 verified", recovery_backoff_bypass_count == 0)

    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)

    print("========================================================================")
    print(f"RECOVERY STATE AUDIT VERDICT: {passed_checks}/{total_checks} PASSED")
    print("========================================================================")

    if passed_checks == total_checks and recovery_backoff_bypass_count == 0:
        print("RECOVERY_STATE_FAIL_CLOSED=PASS")
        print("RECOVERY_BACKOFF_BYPASS_COUNT=0")
        return 0
    else:
        print("RECOVERY_STATE_FAIL_CLOSED=FAIL")
        return 1


if __name__ == "__main__":
    sys.exit(audit_recovery_state())
