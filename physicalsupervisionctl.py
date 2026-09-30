#!/usr/bin/env python3
"""Pixel Sensorium — Physical Runner Supervision v1 CLI.

Provides user control and diagnostics for Physical Runner Supervision v1:
  physicalsupervisionctl start   Establish/advance RUNNING intent & actuate
  physicalsupervisionctl stop    Establish/advance STOPPED intent & actuate
  physicalsupervisionctl status  Read-only supervision & runner health
  physicalsupervisionctl tick    Execute exactly one supervision transaction
"""

import argparse
import json
import os
from pathlib import Path
import sys

import pixel_physical_supervision_v1 as sup


def format_human_status(status_data: dict) -> str:
    lines = [
        "========================================================================",
        "PIXEL SENSORIUM PHYSICAL RUNNER SUPERVISION v1",
        "========================================================================",
        f"Supervision State:   {status_data.get('supervision_state')}",
        f"Desired State:       {status_data.get('desired_physical_state')} (Gen: {status_data.get('generation')})",
        f"Intent Valid:        {status_data.get('intent_valid')}",
        f"Recovery Authorized: {status_data.get('recovery_authorized')}",
    ]
    if status_data.get("intent_error"):
        lines.append(f"Intent Error:        {status_data.get('intent_error')}")

    obs = status_data.get("observed_physical_runner", {})
    lines.extend([
        "",
        "--- OBSERVED PHYSICAL RUNNER ---",
        f"Status:              {obs.get('status')}",
        f"PID:                 {obs.get('pid')}",
        f"Owner Lock Held:     {obs.get('lock_held')}",
        f"Kernel Boot ID:      {obs.get('kernel_boot_id')}",
        f"Supervision Boot ID: {obs.get('supervision_boot_id')}",
        f"Freshness Source:    {obs.get('freshness_source')}",
        f"Boot Ambiguity:      {obs.get('boot_epoch_ambiguity')}",
        f"Last Observation:    {obs.get('last_observation_utc')}",
        f"Elapsed Since Obs:   {obs.get('seconds_since_last_observation')}s",
    ])

    rec = status_data.get("recovery_metrics", {})
    lines.extend([
        "",
        "--- RECOVERY METRICS ---",
        f"Consecutive Fails:   {rec.get('consecutive_recovery_failures', 0)}",
        f"Last Attempt:        {rec.get('last_recovery_attempt_utc')}",
        f"Cooldown Remaining:  {rec.get('cooldown_remaining_seconds', 0.0)}s",
        "========================================================================",
    ])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="physicalsupervisionctl",
        description="Physical Runner Supervision Control Client v1",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    p_start = subparsers.add_parser("start", help="Durable RUNNING intent & start runner")
    p_start.add_argument("--json", action="store_true", help="Output JSON format")

    p_stop = subparsers.add_parser("stop", help="Durable STOPPED intent & stop runner")
    p_stop.add_argument("--json", action="store_true", help="Output JSON format")

    p_status = subparsers.add_parser("status", help="Read-only status check")
    p_status.add_argument("--json", action="store_true", help="Output JSON format")

    p_tick = subparsers.add_parser("tick", help="Execute one supervision evaluation transaction")
    p_tick.add_argument("--source", default="CLI", help="Caller source tag (CLI or JOBSCHEDULER)")
    p_tick.add_argument("--json", action="store_true", help="Output JSON format")

    args = parser.parse_args()

    try:
        if args.command == "start":
            res = sup.execute_supervised_start()
        elif args.command == "stop":
            res = sup.execute_supervised_stop()
        elif args.command == "status":
            res = sup.get_supervision_status()
        elif args.command == "tick":
            res = sup.execute_supervision_tick(source=args.source)
        else:
            parser.print_help()
            return 1

        if args.json:
            print(json.dumps(res, indent=2, sort_keys=True))
        else:
            print(format_human_status(res))

        return 0
    except sup.PhysicalSupervisionError as e:
        if args.json:
            print(json.dumps({"ok": False, "error_code": e.code, "error_detail": e.detail}, indent=2))
        else:
            print(f"[ERROR] {e.code}: {e.detail}", file=sys.stderr)
        return 1
    except Exception as exc:
        if args.json:
            print(json.dumps({"ok": False, "error_code": "INTERNAL_ERROR", "error_detail": str(exc)}, indent=2))
        else:
            print(f"[INTERNAL_ERROR] {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
