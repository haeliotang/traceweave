#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Traceform Field Telemetry & Autonomous Evolution Runner.

Runs local autonomous evaluation on the device:
1. Scheme 1 (On-Device Autonomous Evolution):
   - Computes topological strain on recent frames.
   - Triggers autonomous DETM lattice recalibration if threshold exceeded.
2. Writes device-health and event summaries locally under `field_telemetry/`.
   Device telemetry is not synchronized to Git or a remote service.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
from pathlib import Path
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


def main():
    parser = argparse.ArgumentParser(description="Field Tester Autonomous Evolution and Local Telemetry")
    parser.add_argument("--calibrate", action="store_true", help="Force autonomous DETM recalibration")
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
    print(" Traceform Field Telemetry & Autonomous Evolution")
    print(" Mode: local device processing and local telemetry")
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

    print("\n" + "=" * 76)
    print(" [✓] Local Field Telemetry & Autonomous Evolution Completed.")
    print("=" * 76)


if __name__ == "__main__":
    main()
