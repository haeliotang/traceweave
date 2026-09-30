#!/usr/bin/env python3
"""Durable Stop & Anti-Resurrection Audit for Physical Runner Supervision v1."""

import os
from pathlib import Path
import sys
import time

import pixel_physical_supervision_v1 as sup


def audit_durable_stop() -> int:
    print("========================================================================")
    print("PIXEL SENSORIUM PHYSICAL RUNNER DURABLE STOP AUDIT v1")
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

    # Step A: Ensure desired RUNNING
    start_res = sup.execute_supervised_start(sup_root)
    record("Step A: Desired RUNNING established", start_res["desired_physical_state"] == "RUNNING")

    # Step B: Prove runner is running
    time.sleep(2.0)
    obs_b = sup.observe_physical_runner()
    record("Step B: Runner verified active", obs_b["status"] == "RUNNING" and obs_b["pid"] is not None)

    # Step C: Execute supervised stop
    print("  Executing physicalsupervisionctl stop...")
    stop_res = sup.execute_supervised_stop(sup_root)
    record("Step C: Supervised stop executed", stop_res["actuated_stop"] is True)

    # Step D: Prove durable STOPPED intent
    desired, gen, raw, err = sup.read_intent(sup_root)
    record("Step D: Intent.json durably stores desired_physical_state=STOPPED", desired == "STOPPED" and err is None)

    # Step E: Prove runner is absent and lock released
    time.sleep(1.0)
    obs_e = sup.observe_physical_runner()
    record("Step E: Physical runner process is stopped and lock released", obs_e["status"] == "STOPPED" and obs_e["lock_held"] is False)

    # Step F & G: Run multiple supervision ticks and prove runner remains stopped
    print("  Executing 5 consecutive supervision ticks under STOPPED intent...")
    ticks_clean = True
    for i in range(5):
        tick_res = sup.execute_supervision_tick(sup_root)
        if tick_res["supervision_state"] != "STOPPED" or tick_res.get("actuated_recovery_start") is True:
            ticks_clean = False
            break

    obs_g = sup.observe_physical_runner()
    still_stopped = (obs_g["status"] == "STOPPED" and obs_g["lock_held"] is False)

    record("Step F & G: PHYSICAL_DURABLE_STOP_PREVENTS_RESURRECTION: Runner remains stopped across ticks", ticks_clean and still_stopped)

    # Post-test: Re-establish RUNNING posture for production operation
    print("  Re-establishing RUNNING posture for production operation...")
    sup.execute_supervised_start(sup_root)
    time.sleep(2.0)

    print("========================================================================")
    print(f"DURABLE STOP AUDIT VERDICT: {passed_checks}/{total_checks} PASSED")
    print("========================================================================")

    if passed_checks == total_checks:
        print("PHYSICAL_DURABLE_STOP_PREVENTS_RESURRECTION=PASS")
        return 0
    else:
        print("PHYSICAL_DURABLE_STOP_PREVENTS_RESURRECTION=FAIL")
        return 1


if __name__ == "__main__":
    sys.exit(audit_durable_stop())
