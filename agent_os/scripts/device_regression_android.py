#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Build the validation APK, install it, and run the device regression cases.

Encodes the two pitfalls met in practice: the validation build must use
manifest-documents.xml (manifest-actions.xml lacks the workflow activities),
and the package has to be reinstalled before the cases run.
"""
import argparse
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "agent_os/scripts"
EXTRA_SOURCES = [
    "agent_os/tests/android/DocumentFlowValidationActivity.java",
    "agent_os/tests/android/DocumentFixtureProvider.java",
    "agent_os/tests/android/ActionFlowValidationActivity.java",
    "agent_os/tests/android/DeviceValidationActivity.java",
    "agent_os/tests/android/WorkflowValidationActivity.java",
    "agent_os/tests/android/BackgroundProbeJob.java",
    "agent_os/tests/android/BackgroundProbeActivity.java",
    "agent_os/tests/android/ControlledProbeService.java",
    "agent_os/tests/android/SyncProbe.java",
    "agent_os/tests/android/SyncExchange.java",
]
# Self-contained cases only; UI verification cases need their SEED_* case first.
CASES = ["RUN_SUITE", "TASK_CENTER", "RUN_DOCUMENTS"]

# Order matters: VERIFY_DOCUMENT_RESTART expects the task RUN_DOCUMENTS creates,
# and the workflow UI cases expect the fixture copied by copy_document_validation_fixture.py.
DOCUMENT_CASES = ["RUN_DOCUMENTS", "VERIFY_DOCUMENT_RESTART", "WORKFLOW_GUARD_V4"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--android-jar", required=True)
    parser.add_argument("--build-tools", required=True)
    parser.add_argument("--keystore", required=True)
    parser.add_argument("--key-alias", required=True)
    parser.add_argument("--sync-runtime", required=True)
    parser.add_argument("--output-dir", default="agent_os/build/device-regression")
    parser.add_argument("--case", action="append", dest="cases")
    parser.add_argument("--suite", choices=["quick", "documents"], default="quick")
    args = parser.parse_args()

    output = pathlib.Path(args.output_dir)
    command = [sys.executable, str(SCRIPTS / "build_android.py"),
               "--android-jar", args.android_jar,
               "--build-tools", args.build_tools,
               "--keystore", args.keystore,
               "--key-alias", args.key_alias,
               "--manifest", "agent_os/tests/android/manifest-documents.xml",
               "--native-lib", args.sync_runtime,
               "--output-dir", str(output)]
    for source in EXTRA_SOURCES:
        command += ["--extra-source", source]
    subprocess.run(command, cwd=ROOT, check=True, env=dict(os.environ),
                   stdout=subprocess.DEVNULL)

    apk = output / "agent-os.apk"
    subprocess.run([args.adb, "-s", args.serial, "install", "-r", str(apk)], check=True,
                   stdout=subprocess.DEVNULL)

    if args.cases:
        cases = args.cases
    elif args.suite == "documents":
        cases = DOCUMENT_CASES
    else:
        cases = CASES
    run = [sys.executable, str(SCRIPTS / "validate_actions_android.py"),
           "--adb", args.adb, "--serial", args.serial,
           "--package", "com.inceptless.traceweave.actionsvalidation",
           "--output", str(output / "regression.json")]
    for case in cases:
        run += ["--case", case]
    completed = subprocess.run(run, cwd=ROOT, capture_output=True, text=True)
    print(completed.stdout.strip().splitlines()[-1] if completed.stdout.strip() else "")
    summary = {
        "apk": str(apk),
        "cases": cases,
        "ok": completed.returncode == 0,
        "output_tail": completed.stdout.strip().splitlines()[-6:],
    }
    (output / "device_regression.json").write_text(json.dumps(summary, indent=2) + "\n")
    sys.exit(0 if completed.returncode == 0 else 1)


if __name__ == "__main__":
    main()
