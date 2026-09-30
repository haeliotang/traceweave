#!/usr/bin/env python3
"""24-Hour Live Soak & Trajectory Quality Audit for Physical Observation Stack v1.

Evaluates the real 24-hour physical observation trajectory collected on Pixel 6a:
  - Duration >= 86,400 seconds (24.0 hours)
  - Observations/day, transitions/day, episodes/day
  - Transition density / hour
  - Episode duration statistics (median, P90)
  - Modality participation & transition rates
  - Absence of pathologies (storms, A/B/A flapping, repeated duplicates)
  - Real battery drain analysis over unplugged intervals
  - Exact 24h persistent storage footprint and multi-year projection.
"""

from collections import Counter
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import sys

from pixel_physical import config, ledger, location
from pixel_physical.projection import iso_to_unix


def audit_24h_soak() -> int:
    print("========================================================================")
    print("PIXEL SENSORIUM 24-HOUR PHYSICAL SOAK & QUALITY AUDIT v1")
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

    obs_list = ledger.read_recent_observations(limit=100000)
    trans_list = ledger.read_recent_transitions(limit=100000)
    ep_list = ledger.read_recent_episodes(limit=100000)

    total_obs = len(obs_list)
    total_trans = len(trans_list)
    total_ep = len(ep_list)

    if total_obs == 0:
        print("[audit_24h_soak] FAIL: No observations found in ledger!")
        return 1

    # 1. Soak Monotonic Duration & Wall UTC
    t_start_iso = obs_list[0].timestamp_utc
    t_end_iso = obs_list[-1].timestamp_utc
    t_start = iso_to_unix(t_start_iso)
    t_end = iso_to_unix(t_end_iso)
    soak_duration_s = max(0.0, t_end - t_start)
    soak_hours = soak_duration_s / 3600.0

    print(f"Authoritative Soak Horizon:")
    print(f"  Wall UTC Start:   {t_start_iso}")
    print(f"  Wall UTC End:     {t_end_iso}")
    print(f"  Duration:         {soak_duration_s:.1f} seconds ({soak_hours:.2f} hours)")
    print(f"  Observations:     {total_obs}")
    print(f"  Transitions:      {total_trans}")
    print(f"  Episodes:         {total_ep}")
    print()

    # Check 1: Soak Duration Requirement (>= 86,400s)
    record(
        f"SOAK_DURATION_SECONDS >= 86400 ({soak_duration_s:.1f}s)",
        soak_duration_s >= 86400.0,
        f"Duration only {soak_duration_s:.1f}s",
    )

    # 2. Daily Rates & Density
    norm_factor = 86400.0 / max(soak_duration_s, 1.0)
    obs_per_day = total_obs * norm_factor
    trans_per_day = total_trans * norm_factor
    ep_per_day = total_ep * norm_factor
    trans_density_hour = total_trans / max(soak_hours, 1.0)

    print(f"Trajectory Density Metrics:")
    print(f"  Observations/Day:        {obs_per_day:.1f}")
    print(f"  Transitions/Day:         {trans_per_day:.1f}")
    print(f"  Episodes/Day:            {ep_per_day:.1f}")
    print(f"  Transition Density/Hour: {trans_density_hour:.2f}")

    # Check 2: Bounded Transition Rate (Absence of transition storms)
    record(
        "Transition density is non-pathological (< 20 transitions/hour)",
        trans_density_hour < 20.0,
        f"Density={trans_density_hour:.2f}/hr",
    )

    # 3. Episode Duration Distribution
    ep_durations = []
    for ep in ep_list:
        d = iso_to_unix(ep.end_time_utc) - iso_to_unix(ep.start_time_utc)
        if d >= 0:
            ep_durations.append(d)

    ep_durations.sort()
    median_dur_s = ep_durations[len(ep_durations) // 2] if ep_durations else 0.0
    p90_dur_s = ep_durations[int(len(ep_durations) * 0.9)] if ep_durations else 0.0

    print(f"Episode Durations:")
    print(f"  Median Duration: {median_dur_s:.1f} s ({median_dur_s/60.0:.1f} min)")
    print(f"  P90 Duration:    {p90_dur_s:.1f} s ({p90_dur_s/60.0:.1f} min)")

    # Check 3: Bounded Episode Durations (No micro-episode explosion)
    record("Median episode duration is substantial (>= 5 minutes)", median_dur_s >= 300.0)

    # 4. Modality Participation
    mod_counts = Counter()
    for t in trans_list:
        for m in t.dominant_changes:
            mod_counts[m] += 1
    print(f"Modality Participation across transitions: {dict(mod_counts)}")

    # Check 4: Modality coverage
    record("Transitions exhibit multi-modal physical participation", len(mod_counts) >= 2)

    # 5. Absence of A/B/A spatial flapping or duplicate transitions
    dup_trans = 0
    spatial_flapping = 0
    for i in range(1, len(trans_list)):
        t1 = trans_list[i - 1]
        t2 = trans_list[i]
        if t1.transition_summary == t2.transition_summary and (iso_to_unix(t2.timestamp_utc) - iso_to_unix(t1.timestamp_utc)) < 60.0:
            dup_trans += 1
        if "SPATIAL" in t1.transition_summary and "SPATIAL" in t2.transition_summary:
            if (iso_to_unix(t2.timestamp_utc) - iso_to_unix(t1.timestamp_utc)) < 300.0:
                spatial_flapping += 1

    print(f"Pathology Checks:")
    print(f"  Duplicate transitions within 60s: {dup_trans}")
    print(f"  Spatial flapping within 300s:      {spatial_flapping}")

    record("Zero duplicate transition bursts", dup_trans == 0)
    record("Zero A/B/A spatial flapping", spatial_flapping == 0)

    # 6. Real Resource & Battery Analysis
    # Analyze unplugged discharge intervals
    unplugged_windows_s = 0.0
    start_pct = None
    end_pct = None
    total_pct_drain = 0.0

    for i in range(1, len(obs_list)):
        o1 = obs_list[i - 1]
        o2 = obs_list[i]
        p1 = o1.device_relation.get("plugged")
        p2 = o2.device_relation.get("plugged")
        b1 = o1.device_relation.get("battery_percentage")
        b2 = o2.device_relation.get("battery_percentage")
        dt = iso_to_unix(o2.timestamp_utc) - iso_to_unix(o1.timestamp_utc)

        if p1 == "UNPLUGGED" and p2 == "UNPLUGGED" and b1 is not None and b2 is not None:
            unplugged_windows_s += dt
            if b1 > b2:
                total_pct_drain += (b1 - b2)

    unplugged_hours = unplugged_windows_s / 3600.0
    drain_pct_hour = (total_pct_drain / unplugged_hours) if unplugged_hours > 0 else 0.0
    
    # Pixel 6a battery capacity is 4410 mAh. 1% is 44.1 mAh.
    avg_current_ma = drain_pct_hour * 44.1

    print()
    print(f"Battery Drain Analysis over Unplugged Windows:")
    print(f"  Total Unplugged Duration:       {unplugged_windows_s:.1f} s ({unplugged_hours:.2f} hours)")
    print(f"  Total Battery Drain:            {total_pct_drain:.1f}%")
    print(f"  BATTERY_DRAIN_PERCENT_PER_HOUR: {drain_pct_hour:.2f}%/hr")
    print(f"  BATTERY_AVERAGE_CURRENT_MA:     {avg_current_ma:.1f} mA")
    print(f"  BATTERY_MEASUREMENT_WINDOW_S:   {unplugged_windows_s:.1f} s")

    # Check 7: Battery Drain within normal development device bounds (< 6.0%/hr)
    record("Battery discharge rate is stable (< 6.0%/hr unplugged)", drain_pct_hour < 6.0)

    # 7. Actual 24-Hour Storage Footprint
    obs_bytes = config.OBSERVATIONS_LEDGER.stat().st_size
    trans_bytes = config.TRANSITIONS_LEDGER.stat().st_size
    ep_bytes = config.EPISODES_LEDGER.stat().st_size
    total_bytes_24h = obs_bytes + trans_bytes + ep_bytes
    bytes_per_hour = total_bytes_24h / max(soak_hours, 1.0)
    daily_mb = total_bytes_24h / (1024 * 1024)
    projected_gb_year = (total_bytes_24h * 365.25) / (1024 * 1024 * 1024)

    print()
    print(f"Actual 24-Hour Storage Footprint:")
    print(f"  Observations ledger: {obs_bytes} bytes")
    print(f"  Transitions ledger:  {trans_bytes} bytes")
    print(f"  Episodes ledger:     {ep_bytes} bytes")
    print(f"  Total 24h Bytes:     {total_bytes_24h} bytes ({daily_mb:.2f} MB)")
    print(f"  Storage Rate:        {bytes_per_hour/1024:.1f} KB/hour")
    print(f"  Projected Yearly:    {projected_gb_year:.2f} GB/year")

    # Check 8: Storage Budget (< 20 MB/day and < 10 GB/year)
    record("24-Hour persistent storage within budget (< 20 MB/day)", daily_mb < 20.0)
    record("Projected yearly storage within budget (< 10 GB/year)", projected_gb_year < 10.0)

    print("========================================================================")
    print(f"24-HOUR SOAK AUDIT VERDICT: {passed_checks}/{total_checks} PASSED")
    print("========================================================================")

    if passed_checks == total_checks:
        print("24H_TRAJECTORY_QUALITY=PASS")
        return 0
    else:
        print("24H_TRAJECTORY_QUALITY=FAIL")
        return 1


if __name__ == "__main__":
    sys.exit(audit_24h_soak())
