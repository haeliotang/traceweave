#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Run the local release preflight: tests, build, verification, release notes.

Every step is recorded in a JSON report; the script exits non-zero if any step
fails so a release cannot be published by accident.
"""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "agent_os/scripts"


def run(name, command, cwd=ROOT, env=None):
    started = time.time()
    completed = subprocess.run(command, cwd=cwd, env=env, capture_output=True, text=True)
    return {
        "name": name,
        "command": " ".join(str(part) for part in command),
        "ok": completed.returncode == 0,
        "seconds": round(time.time() - started, 1),
        "stdout_tail": completed.stdout.strip().splitlines()[-3:],
        "stderr_tail": completed.stderr.strip().splitlines()[-3:],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-jar", required=True)
    parser.add_argument("--build-tools", required=True)
    parser.add_argument("--keystore", required=True)
    parser.add_argument("--key-alias", required=True)
    parser.add_argument("--expected-signer", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--model-runtime")
    parser.add_argument("--sync-runtime")
    parser.add_argument("--previous-apk", help="Earlier same-signer APK for the upgrade check")
    parser.add_argument("--skip-tests", action="store_true")
    args = parser.parse_args()

    output = pathlib.Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    password = os.environ.get("AGENT_OS_KEYSTORE_PASSWORD", "")
    env = dict(os.environ)
    if password:
        env["AGENT_OS_KEYSTORE_PASSWORD"] = password

    steps = []

    build_command = [sys.executable, str(SCRIPTS / "build_android.py"),
                     "--android-jar", args.android_jar,
                     "--build-tools", args.build_tools,
                     "--keystore", args.keystore,
                     "--key-alias", args.key_alias,
                     "--output-dir", str(output)]
    if args.model_runtime:
        build_command += ["--native-lib", args.model_runtime]
    if args.sync_runtime:
        build_command += ["--native-lib", args.sync_runtime]
    steps.append(run("build", build_command, env=env))

    apk = output / "agent-os.apk"
    report = output / "build_report.json"
    verification = output / "release_verification.json"
    verify_command = [sys.executable, str(SCRIPTS / "verify_release_android.py"),
                      "--apk", str(apk), "--report", str(report),
                      "--build-tools", args.build_tools,
                      "--expected-signer", args.expected_signer,
                      "--output", str(verification)]
    if args.previous_apk:
        verify_command += ["--previous-apk", args.previous_apk]
    if args.model_runtime:
        verify_command.append("--require-model-runtime")
    if args.sync_runtime:
        verify_command.append("--require-sync-runtime")
    if apk.exists() and report.exists():
        steps.append(run("verify", verify_command, env=env))
    else:
        steps.append({"name": "verify", "ok": False, "error": "build did not produce an APK and report"})

    if not args.skip_tests:
        test_env = dict(env)
        if report.exists():
            test_env["AGENT_OS_BUILD_REPORT"] = str(report)
        steps.append(run("host_tests", [sys.executable, "-m", "unittest", "discover",
                                        "-s", "agent_os/tests", "-p", "test_*.py"], env=test_env))

    notes = output / "release_notes.md"
    if verification.exists():
        steps.append(run("release_notes", [sys.executable, str(SCRIPTS / "release_notes_android.py"),
                                           "--report", str(report), "--verification", str(verification),
                                           "--output", str(notes)], env=env))
    else:
        steps.append({"name": "release_notes", "ok": False, "error": "verification missing"})

    summary = {
        "preflight": "release",
        "ok": all(step.get("ok") for step in steps),
        "output_dir": str(output),
        "skipped": ([] if args.previous_apk else ["upgrade_from_previous_apk (needs --previous-apk)"])
            + (["host_tests"] if args.skip_tests else []),
        "steps": steps,
    }
    (output / "preflight.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"ok": summary["ok"], "failed": [s["name"] for s in steps if not s.get("ok")]}))
    sys.exit(0 if summary["ok"] else 1)


if __name__ == "__main__":
    main()
