#!/usr/bin/env python3
"""Idempotent periodic storage maintenance and guarded weekly calibration."""

import argparse
from contextlib import closing
import datetime
import fcntl
import json
import sqlite3
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from traceform.timezones import resolve_zone
from traceform.evolution.guarded_calibration import calibrate_candidate, audit_observations
from traceform.evolution.potential_maintenance import persist_latest_potential
from traceform.evolution.model_registry import ModelRegistry, atomic_json
from traceform.intervention.closed_loop import compact_trial_rows
from traceform.streaming.daily_snapshot import snapshot_if_due
from traceform.streaming.tiered_storage import TieredStorage
from traceform.streaming.rolling_ledger import RollingLedger


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--timezone", default="UTC")
    parser.add_argument("--events-file", type=Path)
    parser.add_argument("--physical-ledger-dir", type=Path)
    parser.add_argument("--snapshot-dir", type=Path, help="keep one verified local snapshot of the store per local day")
    parser.add_argument("--snapshot-keep-days", type=int, default=14)
    parser.add_argument("--force-calibration", action="store_true")
    parser.add_argument("--rollback", action="store_true")
    args = parser.parse_args()
    registry = ModelRegistry(args.registry)
    with (args.registry / ".maintenance-lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(json.dumps({"status": "maintenance_already_running"}))
            return
        if args.rollback:
            print(json.dumps({"rolled_back_version": registry.rollback()}))
            return
        now = datetime.datetime.now(datetime.timezone.utc)
        local = now.astimezone(resolve_zone(args.timezone))
        week = f"{local.isocalendar().year}-{local.isocalendar().week}"
        receipt_path = args.registry / "maintenance.json"
        previous = json.loads(receipt_path.read_text()) if receipt_path.exists() else {}
        report = {"timestamp_utc": now.isoformat(), "last_calibration_week": previous.get("last_calibration_week")}
        if args.snapshot_dir is not None:  # before compaction, so the day's raw frames are captured first
            try:
                report["daily_snapshot"] = snapshot_if_due(args.database, args.snapshot_dir, now, resolve_zone(args.timezone),
                                                           args.snapshot_keep_days)
            except Exception as error:
                report["daily_snapshot"] = {"status": "failed", "error": type(error).__name__, "reason": str(error)}
        store = TieredStorage(args.database)
        try:
            report["compaction"] = store.compact(now)
            trials = args.database.parent / "interventions.sqlite"
            if trials.is_file():
                with closing(sqlite3.connect(trials)) as trial_db:
                    report["intervention_compaction"] = compact_trial_rows(trial_db, now.timestamp())
            events = RollingLedger(args.events_file or args.database.parent / "manifold_events.jsonl",
                                   max_bytes=8 * 1024 * 1024, retention_seconds=30 * 86400)
            report["event_segments_deleted"] = events.compact(now)
            if args.physical_ledger_dir is not None:
                report["physical_segments_deleted"] = {}
                for name, field in [("observations", "timestamp_utc"), ("transitions", "timestamp_utc"), ("episodes", "end_time_utc")]:
                    path = args.physical_ledger_dir / f"physical_{name}.jsonl"
                    if path.exists():
                        report["physical_segments_deleted"][name] = RollingLedger(path, timestamp_field=field).compact(now)
            due = local.weekday() == 6 and local.hour < 6 and previous.get("last_calibration_week") != week
            if args.force_calibration or due:
                try:
                    report["full_retained_stream_validation"] = audit_observations(store.iter_observations())
                    if report["full_retained_stream_validation"]["valid"]:
                        try:
                            report["potential_maintenance"] = persist_latest_potential(store)
                        except ValueError as error:
                            report["potential_maintenance"] = {"saved": False, "reason": str(error)}
                        report["calibration"] = calibrate_candidate(registry, store.recent_observations())
                    else:
                        report["calibration"] = {"accepted": False, "reason": "full_retained_stream_validation_failed"}
                except Exception as error:
                    report["calibration"] = {"accepted": False, "error": type(error).__name__, "reason": str(error)}
                report["last_calibration_week"] = week
            report["retained_versions"] = registry.prune()
            with registry.lock():
                bundles = [registry.bundle(version) for version in report["retained_versions"]]
            report["cold_bundle"] = store.export_cold_bundle(args.database.parent / "cold_statistics.json.gz", bundles)
        finally:
            store.close()
        atomic_json(receipt_path, report)
        print(json.dumps(report))


if __name__ == "__main__":
    main()
