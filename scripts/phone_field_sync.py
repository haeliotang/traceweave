#!/usr/bin/env python3
"""
Traceform Field Telemetry & Autonomous Sync Runner (Scheme 1 + Scheme 2).

Executes the combined Edge-Cloud protocol on Pixel 6a:
1. Scheme 1 (On-Device Autonomous Evolution):
   - Computes topological strain on recent frames.
   - Triggers autonomous DETM lattice recalibration if threshold exceeded.
2. Scheme 2 (OTA Git Synchronization):
   - Summarizes daily events and device health in `field_telemetry/pixel6a/`.
   - Commits and pushes changes directly to the private GitHub repository via SSH.
   - Pulls updates/overrides from GitHub with --rebase.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import time

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

# Ensure Termux bin is in PATH
termux_bin = "/data/data/com.termux/files/usr/bin"
if os.path.isdir(termux_bin) and termux_bin not in os.environ.get("PATH", ""):
    os.environ["PATH"] = f"{termux_bin}:{os.environ.get('PATH', '')}"

from traceform.evolution.autonomous_learner import AutonomousLearner


def run_cmd(cmd: list[str], cwd: Path, timeout: float = 45.0) -> tuple[int, str]:
    try:
        res = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
        return res.returncode, res.stdout.strip()
    except subprocess.TimeoutExpired:
        return 124, f"Command timed out after {timeout}s"
    except Exception as e:
        return 1, str(e)


def main():
    parser = argparse.ArgumentParser(description="Field Tester Autonomous Evolution & Git OTA Sync")
    parser.add_argument("--calibrate", action="store_true", help="Force autonomous DETM recalibration")
    parser.add_argument("--push", action="store_true", help="Push changes to GitHub repository via SSH")
    parser.add_argument("--strain-threshold", type=float, default=120.0, help="Topological strain trigger threshold")
    args = parser.parse_args()

    telemetry_dir = PROJECT_ROOT / "field_telemetry" / "pixel6a"
    models_dir = telemetry_dir / "models"
    events_dir = telemetry_dir / "events"
    health_dir = telemetry_dir / "health"

    for d in [models_dir, events_dir, health_dir]:
        d.mkdir(parents=True, exist_ok=True)

    raw_ledger = PROJECT_ROOT / "data" / "raw" / ".pixel_sensorium" / "physical_observations.jsonl"
    if not raw_ledger.is_file():
        # Fallback for phone path
        phone_raw = Path("/data/data/com.termux/files/home/.pixel_sensorium/physical_observations.jsonl")
        if phone_raw.is_file():
            raw_ledger = phone_raw

    spec_file = PROJECT_ROOT / "artifacts" / "representation_v1" / "representation_spec.json"
    model_file = PROJECT_ROOT / "artifacts" / "manifold_dynamics_v1" / "manifold_lattice_model.json"
    calibrated_model_file = models_dir / "manifold_lattice_model.json"

    print("=" * 76)
    print(" Traceform Field Telemetry & Autonomous Sync (Pixel 6a)")
    print(f" Target: haeliotang/traceform | Mode: Scheme 1 + Scheme 2")
    print("=" * 76)

    # 1. Scheme 1: On-Device Autonomous Evolution
    print("\n[*] [Scheme 1] Evaluating On-Device Topological Strain...")
    learner = AutonomousLearner(
        raw_ledger_path=raw_ledger,
        normalizer_path=spec_file,
        current_model_path=model_file,
        output_model_path=calibrated_model_file,
        strain_threshold=args.strain_threshold,
    )

    strain, recent_obs = learner.evaluate_topological_strain(sample_size=100)
    print(f"    - Current Topological Strain: {strain:.2f} (Threshold: {args.strain_threshold:.2f})")

    calib_result = learner.self_calibrate(window_size=1200, force=args.calibrate)
    if calib_result and calib_result.get("calibrated"):
        print(f"    [+] Autonomous Evolution Triggered! Fitted new manifold in {calib_result['training_ms']}ms.")
        print(f"    [+] Output Model: {calibrated_model_file}")
    else:
        print(f"    [*] Evolution Status: {calib_result.get('reason', 'Stable')}")

    # 2. Extract Device Health Status from latest observation
    health_payload = {
        "timestamp_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "topological_strain": round(strain, 4),
        "autonomous_calibration": calib_result,
        "device": "Google Pixel 6a (Tensor G1)",
    }

    if recent_obs:
        latest = recent_obs[-1]
        dev_rel = latest.get("device_relation", {})
        health_payload["battery"] = {
            "percentage": dev_rel.get("battery_percentage", 100),
            "status": dev_rel.get("status", "UNKNOWN"),
            "temp_c": dev_rel.get("battery_temp_c", 0.0),
        }
        health_payload["connectivity"] = latest.get("connectivity", {}).get("wifi_connected", False)

    health_file = health_dir / "device_health.json"
    with health_file.open("w", encoding="utf-8") as f:
        json.dump(health_payload, f, indent=2)
    print(f"\n[*] [Telemetry] Generated Device Health Snapshot -> {health_file}")

    # 3. Export Events Ledger Summary
    events_src = PROJECT_ROOT / "data" / "manifold_events.jsonl"
    events_dest = events_dir / "recent_events.json"
    recent_events = []
    if events_src.is_file():
        with events_src.open("r", encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if s:
                    try:
                        recent_events.append(json.loads(s))
                    except Exception:
                        continue
    with events_dest.open("w", encoding="utf-8") as f:
        json.dump(recent_events[-50:], f, indent=2)
    print(f"[*] [Telemetry] Synced {len(recent_events[-50:])} Recent Events -> {events_dest}")

    # 4. Scheme 2: OTA Git Synchronization
    if args.push:
        print("\n[*] [Scheme 2] Initiating OTA Git Synchronization over SSH...")
        # Check if git is initialized in PROJECT_ROOT
        c_status, out = run_cmd(["git", "status", "--porcelain"], PROJECT_ROOT)
        if c_status != 0:
            print(f"[!] Warning: Git repo not configured at {PROJECT_ROOT}: {out}")
            return

        # Add field_telemetry/pixel6a/
        run_cmd(["git", "add", "field_telemetry/pixel6a/"], PROJECT_ROOT)
        ts_now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        commit_msg = f"telemetry(pixel6a): Autonomous field telemetry sync [{ts_now}]"
        c_commit, commit_out = run_cmd(["git", "commit", "-m", commit_msg], PROJECT_ROOT)
        print(f"    - Git Commit: {commit_out.splitlines()[0] if commit_out else 'No changes'}")

        # Pull rebase
        c_pull, pull_out = run_cmd(["git", "pull", "--rebase", "origin", "main"], PROJECT_ROOT)
        if c_pull != 0:
            print(f"    [!] Git pull --rebase failed: {pull_out.splitlines()[-1] if pull_out else 'Error'}")
            run_cmd(["git", "rebase", "--abort"], PROJECT_ROOT)
        else:
            print(f"    - Git Pull: {pull_out.splitlines()[-1] if pull_out else 'OK'}")

            # Push only if pull was successful
            c_push, push_out = run_cmd(["git", "push", "origin", "main"], PROJECT_ROOT)
            if c_push == 0:
                print(f"    [✓] Successfully pushed field telemetry to GitHub (haeliotang/traceform)")
            else:
                print(f"    [!] Git push error: {push_out}")

    print("\n" + "=" * 76)
    print(" [✓] Field Telemetry & Autonomous Sync Completed.")
    print("=" * 76)


if __name__ == "__main__":
    main()
