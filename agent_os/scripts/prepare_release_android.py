#!/usr/bin/env python3
"""Build, test and verify a same-signer local APK update without deploying it."""
import argparse
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("android-jar", "build-tools", "keystore", "previous-apk", "output-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--key-alias", required=True)
    parser.add_argument("--expected-signer", required=True)
    parser.add_argument("--password-env", default="AGENT_OS_KEYSTORE_PASSWORD")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    if not args.previous_apk.is_file() or args.previous_apk.resolve() == (args.output_dir / "agent-os.apk").resolve():
        parser.error("Keep the previous APK in a separate existing file; the candidate must not overwrite it")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    verification = args.output_dir / "release_verification.json"
    verification.unlink(missing_ok=True)
    builder = [sys.executable, str(root / "agent_os/scripts/build_android.py")]
    for name in ("android-jar", "build-tools", "keystore", "key-alias", "password-env", "output-dir"):
        builder += ["--" + name, str(getattr(args, name.replace("-", "_")))]
    subprocess.run(builder, cwd=root, check=True)
    env = os.environ.copy()
    env["AGENT_OS_BUILD_REPORT"] = str((args.output_dir / "build_report.json").resolve())
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "agent_os/tests", "-p", "test_*.py", "-v"],
                   cwd=root, env=env, check=True)
    subprocess.run([sys.executable, str(root / "agent_os/scripts/verify_release_android.py"),
                    "--apk", str(args.output_dir / "agent-os.apk"), "--report", str(args.output_dir / "build_report.json"),
                    "--build-tools", str(args.build_tools), "--expected-signer", args.expected_signer,
                    "--previous-apk", str(args.previous_apk), "--output", str(verification)], cwd=root, check=True)
    print("Candidate is verified locally. Device acceptance and a data-recovery rehearsal are still required before distribution.")


if __name__ == "__main__":
    main()
