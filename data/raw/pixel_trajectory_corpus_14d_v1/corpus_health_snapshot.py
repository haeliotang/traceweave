#!/usr/bin/env python3
"""Pixel Sensorium — 14-Day Trajectory Corpus Daily Health Snapshot Tool.

Strictly read-only inspection tool for the 14-day natural trajectory corpus.
Collects and records operational metrics without mutating any frozen system
or authoritative physical ledger.
"""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import time

if "/root" not in sys.path:
    sys.path.insert(0, "/root")

from pixel_physical import config, ledger
import pixel_physical_supervision_v1 as sup

CORPUS_ROOT = Path("/root/pixel_trajectory_corpus_14d_v1")
MANIFEST_PATH = CORPUS_ROOT / "CORPUS_START_MANIFEST.json"
SNAPSHOT_LOG = CORPUS_ROOT / "corpus_daily_snapshots.jsonl"


def count_lines(path: Path) -> int:
    if not path.exists():
        return 0
    cnt = 0
    with path.open("rb") as f:
        for _ in f:
            cnt += 1
    return cnt


def take_snapshot() -> dict:
    now_utc = datetime.now(timezone.utc).isoformat()
    now_mono_ns = time.monotonic_ns()

    # 1. Read Manifest
    manifest = {}
    if MANIFEST_PATH.exists():
        try:
            manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        except Exception:
            pass

    start_boot_id = manifest.get("kernel_boot_id")

    # 2. Kernel & Process State
    current_boot_id = sup.get_kernel_boot_id()
    sup_status = sup.get_supervision_status()
    obs_runner = sup_status.get("observed_physical_runner", {})
    sup_state = sup_status.get("supervision_state")
    desired_state = sup_status.get("desired_physical_state")

    # 3. Ledger Metrics
    obs_path = config.OBSERVATIONS_LEDGER
    trans_path = config.TRANSITIONS_LEDGER
    ep_path = config.EPISODES_LEDGER

    obs_count = count_lines(obs_path)
    trans_count = count_lines(trans_path)
    ep_count = count_lines(ep_path)

    obs_bytes = obs_path.stat().st_size if obs_path.exists() else 0
    trans_bytes = trans_path.stat().st_size if trans_path.exists() else 0
    ep_bytes = ep_path.stat().st_size if ep_path.exists() else 0

    latest_obs = ledger.read_recent_observations(limit=1)
    newest_obs_id = latest_obs[0].observation_id if latest_obs else None
    newest_obs_utc = latest_obs[0].timestamp_utc if latest_obs else None
    newest_obs_mono = latest_obs[0].monotonic_ns if latest_obs else None

    # Battery & Device Temp from newest observation if available
    battery_level = None
    device_temp = None
    if latest_obs:
        dev_rel = latest_obs[0].device_relation or {}
        battery_level = dev_rel.get("battery_percentage")
        env = latest_obs[0].environment or {}
        device_temp = env.get("device_temp_c")

    # Owner present
    owner_present = None
    live_state = ledger.read_live_state()
    if live_state:
        owner_present = live_state.get("owner_present")

    # 4. Job 7702 Registration Status
    job_7702_registered = False
    try:
        res = subprocess.run(["termux-job-scheduler", "-p"], stdout=subprocess.PIPE, text=True)
        job_7702_registered = "7702" in res.stdout
    except Exception:
        pass

    # 5. Recovery Attempts
    rec_metrics = sup_status.get("recovery_metrics", {})
    recovery_failures = rec_metrics.get("consecutive_recovery_failures", 0)
    last_recovery_attempt = rec_metrics.get("last_recovery_attempt_utc")

    # 6. Corpus Validity Flags
    # Verify frozen instrument invariance read-only
    res_obs = subprocess.run(["/root/verify_physical_observation_stack_v1_frozen.sh"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    res_sup = subprocess.run(["/root/verify_physical_runner_supervision_v1_frozen.sh"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    instrument_hash_changed = (res_obs.returncode != 0 or res_sup.returncode != 0)

    runner_gap = (obs_runner.get("status") != "RUNNING" or not obs_runner.get("healthy"))
    boot_occurred = (start_boot_id is not None and current_boot_id != start_boot_id)
    force_stop_occurred = (obs_runner.get("status") != "RUNNING" and desired_state == "RUNNING")
    supervision_attention = (sup_state == "ATTENTION_REQUIRED")

    # Check parse integrity of tail lines
    ledger_parse_failure = False
    for p in (obs_path, trans_path, ep_path):
        if p.exists() and p.stat().st_size > 0:
            try:
                with p.open("rb") as f:
                    f.seek(max(0, p.stat().st_size - 8192))
                    raw_lines = f.read().decode("utf-8", errors="ignore").splitlines()
                    # First line after seek is potentially partial/cut, skip it if we sought into middle
                    test_lines = raw_lines[1:] if p.stat().st_size > 8192 else raw_lines
                    for line in test_lines[-5:]:
                        line = line.strip()
                        if line:
                            json.loads(line)
            except Exception:
                ledger_parse_failure = True

    snapshot = {
        "protocol": "pixel-sensorium.natural-trajectory-corpus-snapshot.v1",
        "snapshot_utc": now_utc,
        "monotonic_ns": now_mono_ns,
        "kernel_boot_id": current_boot_id,
        "observation_count": obs_count,
        "transition_count": trans_count,
        "episode_count": ep_count,
        "newest_observation_id": newest_obs_id,
        "newest_observation_utc": newest_obs_utc,
        "newest_observation_monotonic_ns": newest_obs_mono,
        "physical_runner_state": obs_runner.get("status"),
        "desired_physical_state": desired_state,
        "owner_present": owner_present,
        "job_7702_registered": job_7702_registered,
        "supervision_state": sup_state,
        "recovery_failures": recovery_failures,
        "last_recovery_attempt_utc": last_recovery_attempt,
        "ledger_sizes_bytes": {
            "observations": obs_bytes,
            "transitions": trans_bytes,
            "episodes": ep_bytes,
        },
        "battery_level_percent": battery_level,
        "device_temp_c": device_temp,
        "validity_flags": {
            "INSTRUMENT_HASH_CHANGED": instrument_hash_changed,
            "RUNNER_GAP": runner_gap,
            "BOOT_OCCURRED": boot_occurred,
            "FORCE_STOP_OCCURRED": force_stop_occurred,
            "SUPERVISION_ATTENTION_REQUIRED": supervision_attention,
            "LEDGER_PARSE_FAILURE": ledger_parse_failure,
        },
    }

    # Append to snapshot log
    CORPUS_ROOT.mkdir(parents=True, exist_ok=True)
    with SNAPSHOT_LOG.open("a", encoding="utf-8") as f:
        f.write(json.dumps(snapshot) + "\n")

    return snapshot


def main():
    snap = take_snapshot()
    if "--json" in sys.argv:
        print(json.dumps(snap, indent=2))
    else:
        print("========================================================================")
        print("PIXEL SENSORIUM 14-DAY TRAJECTORY CORPUS HEALTH SNAPSHOT")
        print("========================================================================")
        print(f"Snapshot UTC:        {snap['snapshot_utc']}")
        print(f"Kernel Boot ID:      {snap['kernel_boot_id']}")
        print(f"Supervision State:   {snap['supervision_state']}")
        print(f"Runner Status:       {snap['physical_runner_state']}")
        print(f"Job 7702 Registered:{snap['job_7702_registered']}")
        print(f"Observations Count:  {snap['observation_count']}")
        print(f"Transitions Count:   {snap['transition_count']}")
        print(f"Episodes Count:      {snap['episode_count']}")
        print(f"Latest Obs UTC:      {snap['newest_observation_utc']}")
        print(f"Battery:             {snap['battery_level_percent']}%")
        print(f"Device Temp:         {snap['device_temp_c']} °C")
        print("--- VALIDITY FLAGS ---")
        for k, v in snap["validity_flags"].items():
            print(f"  {k:32s}: {v}")
        print("========================================================================")


if __name__ == "__main__":
    main()
