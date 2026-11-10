#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Synthetic fixed-package Doze/restricted-bucket, recovery and offline checks.

Temporarily changes screen, airplane/Wi-Fi and power settings, restoring them in
finally. Does not reset global battery history or touch a production database.
"""
import argparse
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.inceptless.traceweave.actionsvalidation"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]

    def run(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=40)

    def state(task_id):
        # Only the fixed isolated application's synthetic DB is copied. Its task
        # status is not encrypted; no Keystore key or content is extracted.
        with tempfile.TemporaryDirectory(prefix="traceform-status-") as folder:
            database = Path(folder) / "agent_os.db"
            for suffix in ("", "-wal"):
                result = subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "cat", "databases/agent_os.db" + suffix], capture_output=True, timeout=20)
                if result.returncode == 0:
                    Path(str(database) + suffix).write_bytes(result.stdout)
                elif not suffix:
                    raise RuntimeError("Synthetic DB status unavailable")
            with sqlite3.connect("file:" + str(database) + "?mode=ro", uri=True) as db:
                row = db.execute("SELECT status, execution_step FROM tasks WHERE id=?", (task_id,)).fetchone()
                if row is None:
                    raise RuntimeError("Synthetic task missing")
                return {"status": row[0], "executionStep": row[1]}

    original = {key: run("shell", "settings", "get", "global", setting).strip()
                for key, setting in (("power", "low_power"), ("airplane", "airplane_mode_on"), ("wifi", "wifi_on"))}
    original["bucket"] = run("shell", "am", "get-standby-bucket", PACKAGE).strip()
    if any(original[key] not in ("0", "1") for key in ("power", "airplane", "wifi")):
        raise SystemExit("Unsupported original power/network setting; no settings changed")
    whitelist = run("shell", "dumpsys", "deviceidle", "whitelist")
    original["whitelisted"] = any(len(parts := line.split(",")) > 1 and parts[1] == PACKAGE for line in whitelist.splitlines())
    receipt = {"synthetic_only": True, "package": PACKAGE, "original_settings": original,
               "simulated_unplug_for_restriction_test_not_energy_measurement": True, "cases": []}
    try:
        if original["whitelisted"]:
            run("shell", "dumpsys", "deviceidle", "whitelist", "-" + PACKAGE)
        run("shell", "am", "start", "-W", "-S", "-n", PACKAGE + "/com.inceptless.traceweave.BackgroundProbeActivity", "-a", "ARM_RESTRICTED_JOB")
        prefs = ET.fromstring(run("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/background_probe.xml"))
        task_id = next(n.text for n in prefs if n.attrib.get("name") == "restrictedTask")
        job_id = next(n.attrib["value"] for n in prefs if n.attrib.get("name") == "restrictedJob")
        run("shell", "input", "keyevent", "3"); run("shell", "input", "keyevent", "223")
        run("shell", "dumpsys", "battery", "unplug")
        run("shell", "am", "set-standby-bucket", PACKAGE, "restricted")
        run("shell", "cmd", "power", "set-mode", "1")
        run("shell", "dumpsys", "deviceidle", "force-idle")
        started = time.monotonic(); time.sleep(40)
        deferred = state(task_id)
        receipt["cases"].append({"case": "doze_restricted_power_save", "elapsed_seconds": round(time.monotonic() - started, 2),
            "power_save": run("shell", "settings", "get", "global", "low_power").strip(),
            "bucket": run("shell", "am", "get-standby-bucket", PACKAGE).strip(),
            "deep_idle": run("shell", "dumpsys", "deviceidle", "get", "deep").strip(),
            "job_state": run("shell", "cmd", "jobscheduler", "get-job-state", PACKAGE, job_id).strip(),
            "task": deferred, "passed": deferred["status"] == "active" and deferred["executionStep"] == 0})
        controlled = receipt["cases"][-1]
        controlled["passed"] &= controlled["power_save"] == "1" and controlled["bucket"] == "45" and controlled["deep_idle"] == "IDLE"
        run("shell", "dumpsys", "deviceidle", "unforce")
        run("shell", "dumpsys", "battery", "reset")
        run("shell", "cmd", "power", "set-mode", original["power"])
        run("shell", "am", "set-standby-bucket", PACKAGE, "active")
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline and state(task_id)["status"] != "completed":
            time.sleep(2)
        recovered = state(task_id)
        receipt["cases"].append({"case": "automatic_recovery_after_restrictions_removed", "task": recovered,
                                 "no_forced_job_run": True, "passed": recovered["status"] == "completed" and recovered["executionStep"] == 1})
        run("shell", "cmd", "connectivity", "airplane-mode", "enable")
        run("shell", "svc", "wifi", "disable")
        time.sleep(3)
        run("shell", "am", "start", "-W", "-S", "-n", PACKAGE + "/com.inceptless.traceweave.BackgroundProbeActivity", "-a", "VERIFY_OFFLINE_LOCAL")
        offline = json.loads(run("exec-out", "run-as", PACKAGE, "cat", "files/offline-result.json"))
        receipt["cases"].append({"case": "offline_local_execution", **offline, "passed": all(offline.values())})
    finally:
        run("shell", "dumpsys", "deviceidle", "unforce")
        run("shell", "dumpsys", "battery", "reset")
        run("shell", "cmd", "power", "set-mode", original["power"])
        run("shell", "am", "set-standby-bucket", PACKAGE, original["bucket"])
        run("shell", "cmd", "connectivity", "airplane-mode", "enable" if original["airplane"] == "1" else "disable")
        run("shell", "svc", "wifi", "enable" if original["wifi"] == "1" else "disable")
        if original["whitelisted"]:
            run("shell", "dumpsys", "deviceidle", "whitelist", "+" + PACKAGE)
        time.sleep(2)
        restored = {key: run("shell", "settings", "get", "global", setting).strip()
                    for key, setting in (("power", "low_power"), ("airplane", "airplane_mode_on"), ("wifi", "wifi_on"))}
        restored["bucket"] = run("shell", "am", "get-standby-bucket", PACKAGE).strip()
        receipt["restored_settings"] = restored
        receipt["settings_restored"] = all(restored[key] == original[key] for key in restored)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(receipt, indent=2) + "\n")
    if not all(c["passed"] for c in receipt["cases"]) or len(receipt["cases"]) != 3 or not receipt["settings_restored"]:
        raise SystemExit("Background checks did not all pass; inspect receipt")
    print("Doze/restricted deferral, automatic recovery and offline local execution passed")


if __name__ == "__main__":
    main()
