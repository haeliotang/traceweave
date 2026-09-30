#!/usr/bin/env python3
"""Resource Usage and Battery Audit for Physical Observation Stack v1."""

import json
import os
from pathlib import Path
import subprocess
import sys
import time

from pixel_physical import config, ledger


def audit_resource_usage() -> int:
    print("========================================================================")
    print("PIXEL SENSORIUM PHYSICAL RESOURCE & BATTERY AUDIT v1")
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

    # 1. Storage footprint per frame
    obs_file = config.OBSERVATIONS_LEDGER
    trans_file = config.TRANSITIONS_LEDGER
    ep_file = config.EPISODES_LEDGER

    obs_bytes_per_sample = 0
    if obs_file.exists() and obs_file.stat().st_size > 0:
        with obs_file.open("r", encoding="utf-8") as f:
            lines = [l for l in f if l.strip()]
            if lines:
                obs_bytes_per_sample = sum(len(l.encode("utf-8")) for l in lines) / len(lines)

    trans_bytes_per_sample = 0
    if trans_file.exists() and trans_file.stat().st_size > 0:
        with trans_file.open("r", encoding="utf-8") as f:
            lines = [l for l in f if l.strip()]
            if lines:
                trans_bytes_per_sample = sum(len(l.encode("utf-8")) for l in lines) / len(lines)

    ep_bytes_per_sample = 0
    if ep_file.exists() and ep_file.stat().st_size > 0:
        with ep_file.open("r", encoding="utf-8") as f:
            lines = [l for l in f if l.strip()]
            if lines:
                ep_bytes_per_sample = sum(len(l.encode("utf-8")) for l in lines) / len(lines)

    print(f"Empirical Storage Footprint:")
    print(f"  PhysicalObservation average line size: {obs_bytes_per_sample:.1f} bytes")
    print(f"  PhysicalTransition average line size:  {trans_bytes_per_sample:.1f} bytes")
    print(f"  PhysicalEpisode average line size:     {ep_bytes_per_sample:.1f} bytes")

    # Projections at 1 sample / 30 seconds (2880 samples/day)
    daily_obs_bytes = 2880 * obs_bytes_per_sample
    daily_trans_bytes = 100 * trans_bytes_per_sample
    daily_ep_bytes = 30 * ep_bytes_per_sample
    daily_total_bytes = daily_obs_bytes + daily_trans_bytes + daily_ep_bytes
    daily_mb = daily_total_bytes / (1024 * 1024)
    yearly_gb = (daily_total_bytes * 365.25) / (1024 * 1024 * 1024)

    print()
    print(f"Trajectory Storage Projections (30s cadence):")
    print(f"  Daily storage:   {daily_mb:.2f} MB/day ({int(daily_total_bytes)} bytes)")
    print(f"  Yearly storage:  {yearly_gb:.2f} GB/year")
    print()

    record("Observation frame size within bounds (< 4KB per frame)", obs_bytes_per_sample < 4096)
    record("Daily projected trajectory storage within budget (< 25 MB/day)", daily_mb < 25.0)
    record("Yearly projected trajectory storage within budget (< 10 GB/year)", yearly_gb < 10.0)

    # 2. Raw temporary audio footprint check
    temp_audio_files = list(Path("/data/data/com.termux/files/home").glob("temp_phys_audio_*")) + list(Path("/tmp").glob("temp_phys_audio_*"))
    print(f"Raw Temporary Audio Leakage: {len(temp_audio_files)} files found")
    record("Zero raw audio files leaked in temporary paths", len(temp_audio_files) == 0)

    # 3. Battery status & power telemetry
    battery_res = subprocess.run([config.BATTERY_BIN], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=5.0)
    bat_drain_ua = 0
    bat_pct = 0
    if battery_res.returncode == 0 and battery_res.stdout.strip():
        parsed = json.loads(battery_res.stdout.strip())
        bat_drain_ua = abs(int(parsed.get("current", 0)))
        bat_pct = int(parsed.get("percentage", 0))
        temp_c = parsed.get("temperature", 0.0)
        print(f"Battery Power Telemetry:")
        print(f"  Level:       {bat_pct}%")
        print(f"  Current:     {bat_drain_ua / 1000.0:.1f} mA")
        print(f"  Temperature: {temp_c:.1f} °C")

    record("Battery telemetry successfully probed", bat_pct > 0)
    record("Power consumption within normal active mobile range (< 1500 mA)", (bat_drain_ua / 1000.0) < 1500.0)

    print("========================================================================")
    print(f"RESOURCE & BATTERY AUDIT VERDICT: {passed_checks}/{total_checks} PASSED")
    print("========================================================================")

    if passed_checks == total_checks:
        print("RESOURCE_AUDIT=PASS")
        return 0
    else:
        print("RESOURCE_AUDIT=FAIL")
        return 1


if __name__ == "__main__":
    sys.exit(audit_resource_usage())
