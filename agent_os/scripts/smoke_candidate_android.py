#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Install a candidate APK and check that it launches with the expected UI.

This is the device half of the release gate: the preflight proves the artifact is
correct on disk, this proves the artifact actually runs.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import time

# Markers must be visible without scrolling: the navigation dock sits below the
# first screen, so uiautomator does not expose it until the page is scrolled.
MARKERS = {
    "header": ["Traceweave"],
    "composer": ["AI tasks", "智能体任务"],
    "tiles": ["Local Planner", "本地规划"],
}


def adb_run(adb, serial, *args, check=True):
    return subprocess.run([adb, "-s", serial, *args], capture_output=True, text=True, check=check)


def dump_ui(adb, serial):
    adb_run(adb, serial, "shell", "uiautomator", "dump", "/sdcard/smoke.xml", check=False)
    text = adb_run(adb, serial, "shell", "cat", "/sdcard/smoke.xml", check=False).stdout
    adb_run(adb, serial, "shell", "rm", "-f", "/sdcard/smoke.xml", check=False)
    return " ".join(re.findall(r'(?:text|content-desc)="([^"]+)"', text))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default="com.inceptless.traceweave")
    parser.add_argument("--activity", default=".MainActivity")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    checks = {}
    details = {}
    install = adb_run(args.adb, args.serial, "install", "-r", args.apk, check=False)
    checks["install"] = install.returncode == 0
    details["install_output"] = (install.stdout or install.stderr).strip().splitlines()[-1:]

    version = adb_run(args.adb, args.serial, "shell", "dumpsys", "package", args.package).stdout
    match = re.search(r"versionName=([^\s]+)", version)
    details["version"] = match.group(1) if match else None

    # A fresh install shows the notification prompt, which would otherwise be the
    # resumed activity and make the launch check fail.
    adb_run(args.adb, args.serial, "shell", "pm", "grant", args.package,
            "android.permission.POST_NOTIFICATIONS", check=False)
    adb_run(args.adb, args.serial, "shell", "am", "force-stop", args.package, check=False)
    adb_run(args.adb, args.serial, "shell", "am", "start",
            "-n", f"{args.package}/{args.activity}", check=False)
    time.sleep(4)
    resumed = adb_run(args.adb, args.serial, "shell", "dumpsys", "activity", "activities").stdout
    checks["launch"] = bool(re.search(r"topResumedActivity=.*" + re.escape(args.package), resumed))

    screen = dump_ui(args.adb, args.serial)
    for name, markers in MARKERS.items():
        checks[name] = any(marker in screen for marker in markers)

    result = {
        "apk": str(pathlib.Path(args.apk).resolve()),
        "package": args.package,
        "version": details["version"],
        "checks": checks,
        "details": details,
        "ok": all(checks.values()),
    }
    pathlib.Path(args.output).write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"ok": result["ok"], "checks": checks, "version": result["version"]}))
    sys.exit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
