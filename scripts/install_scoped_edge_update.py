#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Install a verified, same-signer scoped Edge update without accessing app data."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from build_scoped_edge_update import PACKAGE, manifest_nodes, verify_manifest
from verify_release_android import apk_identity


def run(args, check=True, **kwargs):
    return subprocess.run([str(value) for value in args], check=check, **kwargs)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def verify_candidate(baseline, candidate, report, tools):
    if report.get("scope") != "candidate_only_no_user_database_read_or_device_install":
        raise ValueError("Invalid candidate report")
    if sha256(baseline.read_bytes()) != report.get("baseline_sha256") or sha256(candidate.read_bytes()) != report.get("candidate_sha256"):
        raise ValueError("Candidate or baseline digest changed")
    source = ROOT / "android/src/com/inceptless/traceweave/edge/HistorySummaryService.java"
    manifest = ROOT / "android/AndroidManifest.xml"
    if sha256(source.read_bytes()) != report.get("summary_source_sha256") or sha256(manifest.read_bytes()) != report.get("manifest_source_sha256"):
        raise ValueError("Candidate source changed after build")
    old, new = apk_identity(baseline, tools), apk_identity(candidate, tools)
    if old["package"] != PACKAGE or new["package"] != PACKAGE or old["signer_sha256"] != new["signer_sha256"]:
        raise ValueError("Edge package or signing identity changed")
    if new["version_code"] <= old["version_code"] or new["debuggable"] != old["debuggable"]:
        raise ValueError("Edge version or debug state is not a safe update")
    verify_manifest(manifest_nodes(baseline, tools), manifest_nodes(candidate, tools))
    with zipfile.ZipFile(baseline) as old_zip, zipfile.ZipFile(candidate) as new_zip:
        original = {name for name in old_zip.namelist() if not name.startswith("META-INF/")}
        updated = {name for name in new_zip.namelist() if not name.startswith("META-INF/")}
        if len(new_zip.namelist()) != len(set(new_zip.namelist())) or updated != original | {"classes2.dex"}:
            raise ValueError("Unexpected Edge APK entries")
        for name in old_zip.namelist():
            if name != "AndroidManifest.xml" and not name.startswith("META-INF/"):
                if name not in new_zip.namelist() or sha256(old_zip.read(name)) != sha256(new_zip.read(name)):
                    raise ValueError("An existing Edge APK entry changed")
    return new


def installed_apk(adb):
    result = run(adb + ["shell", "pm", "path", PACKAGE], capture_output=True, text=True).stdout.strip()
    match = re.fullmatch(r"package:(/data/app/[A-Za-z0-9_./~+=-]+/base\.apk)", result)
    if not match:
        raise RuntimeError("The installed Edge APK path is unavailable or ambiguous")
    return run(adb + ["exec-out", "cat", match.group(1)], capture_output=True).stdout


def installed_identity(adb):
    package = run(adb + ["shell", "dumpsys", "package", PACKAGE], capture_output=True, text=True).stdout
    uid = run(adb + ["shell", "pm", "list", "packages", "-U", PACKAGE], capture_output=True, text=True).stdout
    first = re.search(r"^\s*firstInstallTime=(.+)$", package, re.MULTILINE)
    user = re.search(r"^package:" + re.escape(PACKAGE) + r"\s+uid:(\d+)\s*$", uid, re.MULTILINE)
    if not first or not user:
        raise RuntimeError("Edge installation identity is unavailable")
    return first.group(1).strip(), user.group(1)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("baseline-apk", "candidate-apk", "candidate-report", "build-tools", "output"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--adb", default="adb")
    p.add_argument("--serial", required=True)
    args = p.parse_args()
    report = json.loads(args.candidate_report.read_text())
    verify_candidate(args.baseline_apk, args.candidate_apk, report, args.build_tools)
    adb = [args.adb, "-s", args.serial]
    if run(adb + ["shell", "pidof", PACKAGE + ":edge"], capture_output=True, check=False).returncode == 0:
        raise RuntimeError("Edge service is running; stop collection before the update")
    if sha256(installed_apk(adb)) != report["baseline_sha256"]:
        raise RuntimeError("Installed Edge differs from the trusted baseline")
    before = installed_identity(adb)
    run(adb + ["install", "-r", args.candidate_apk])
    if sha256(installed_apk(adb)) != report["candidate_sha256"] or installed_identity(adb) != before:
        raise RuntimeError("Edge installed but post-update identity could not be verified")
    result = {"production_update_installed": True, "same_signer_checked": True,
              "original_installation_time_preserved": True, "uid_preserved": True,
              "baseline_code_assets_and_native_preserved": True, "database_not_read": True,
              "candidate_sha256": report["candidate_sha256"], "debuggable_state_preserved": report["debuggable_preserved"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print("Scoped Edge update installed in place; installation identity preserved")


if __name__ == "__main__":
    main()
