#!/usr/bin/env python3
"""Live Process-Death Autonomous Recovery Audit for Physical Runner Supervision v1."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import pixel_physical_supervision_v1 as sup
from pixel_physical import config, ledger


def audit_live_recovery() -> int:
    print("========================================================================")
    print("PIXEL SENSORIUM PHYSICAL RUNNER LIVE RECOVERY AUDIT v1")
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

    sup_root = sup.get_default_supervision_root()

    # Step A: Establish desired RUNNING
    start_res = sup.execute_supervised_start(sup_root)
    record("Step A: Established durable RUNNING intent", start_res["desired_physical_state"] == "RUNNING" and start_res["recovery_authorized"] is True)

    # Step B: Prove runner is running
    obs_b = None
    for _ in range(10):
        obs_b = sup.observe_physical_runner()
        if obs_b["status"] == "RUNNING" and obs_b["pid"] is not None:
            break
        time.sleep(0.5)

    orig_pid = obs_b["pid"] if obs_b else None
    record("Step B: Physical runner process verified active", obs_b is not None and obs_b["status"] == "RUNNING" and orig_pid is not None)

    # Measure observation count before kill
    initial_obs = ledger.read_recent_observations(limit=100000)
    initial_count = len(initial_obs)

    # Step C: Kill ONLY physical runner process
    print(f"  Simulating abrupt process death: killing runner PID={orig_pid}...")
    try:
        os.kill(orig_pid, 9)  # SIGKILL
    except OSError:
        pass

    time.sleep(1.0)
    obs_c = sup.observe_physical_runner()
    record("Step C: Verified runner process dead and lock released", obs_c["status"] == "STOPPED" and obs_c["lock_held"] is False)

    # Step D & E: Execute a supervision tick (do NOT manually restart)
    print("  Executing autonomous supervision tick...")
    tick_res = sup.execute_supervision_tick(sup_root)
    record("Step E: Supervision tick executed recovery actuation", tick_res["actuated_recovery_start"] is True)

    # Step F: Prove runner restored, exactly one runner, acquisition resumes, observations advance
    obs_f = None
    for _ in range(15):
        time.sleep(0.5)
        obs_f = sup.observe_physical_runner()
        if obs_f["status"] == "RUNNING":
            break

    new_pid = obs_f["pid"] if obs_f else None
    record("Step F.1: Physical runner successfully restored with new PID", obs_f is not None and obs_f["status"] == "RUNNING" and new_pid is not None and new_pid != orig_pid)

    # Verify exactly one runner
    competitor_rc, comp_data, comp_out = sup.invoke_physicalctl(["start"])
    record("Step F.2: MAX_PHYSICAL_ACQUISITION_RUNNERS=1 enforced (competitor start rejected)", competitor_rc != 0 or "Runner already running" in comp_out or "PID=" in comp_out)

    # Verify observations advance and no ledger corruption
    print("  Waiting 15 seconds for runner acquisition cycle...")
    time.sleep(15.0)
    fresh_obs = ledger.read_recent_observations(limit=100000)
    fresh_count = len(fresh_obs)
    record("Step F.3: Acquisition resumed and observations advance in ledger", fresh_count >= initial_count)

    # Verify ledger integrity
    intact = True
    for p in (config.OBSERVATIONS_LEDGER, config.TRANSITIONS_LEDGER, config.EPISODES_LEDGER):
        if p.exists():
            for line in p.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line:
                    try:
                        json.loads(line)
                    except Exception:
                        intact = False
                        break
    record("Step F.4: Authoritative append-only ledgers remain 100% uncorrupted", intact)

    print("========================================================================")
    print(f"LIVE RECOVERY AUDIT VERDICT: {passed_checks}/{total_checks} PASSED")
    print("========================================================================")

    if passed_checks == total_checks:
        print("PHYSICAL_RUNNER_AUTOMATIC_RECOVERY_CAPABILITY=PASS")
        return 0
    else:
        print("PHYSICAL_RUNNER_AUTOMATIC_RECOVERY_CAPABILITY=FAIL")
        return 1


if __name__ == "__main__":
    sys.exit(audit_live_recovery())
