#!/usr/bin/env python3
"""Build, test and verify a same-signer local APK update without deploying it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def validate_model_runtime(runtime, root):
    report = json.loads((runtime.parent / "experimental_model_build.json").read_text())
    source = root / "agent_os/android/native/experimental_local_model.cpp"
    if (report.get("llama_cpp_commit") != "d78f40d9d34d7a72ecdcd2e854f75ff86afc8ca3"
            or report.get("android_abi") != "arm64-v8a" or report.get("android_api") != 29
            or report.get("native_source_sha256") != hashlib.sha256(source.read_bytes()).hexdigest()
            or report.get("runtime_sha256") != hashlib.sha256(runtime.read_bytes()).hexdigest()):
        raise ValueError("Local model runtime provenance does not match the pinned source and build")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("android-jar", "build-tools", "keystore", "previous-apk", "output-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--key-alias", required=True)
    parser.add_argument("--expected-signer", required=True)
    parser.add_argument("--password-env", default="AGENT_OS_KEYSTORE_PASSWORD")
    parser.add_argument("--model-runtime", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    if not args.previous_apk.is_file() or args.previous_apk.resolve() == (args.output_dir / "agent-os.apk").resolve():
        parser.error("Keep the previous APK in a separate existing file; the candidate must not overwrite it")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    if args.model_runtime:
        validate_model_runtime(args.model_runtime, root)
    verification = args.output_dir / "release_verification.json"
    verification.unlink(missing_ok=True)
    builder = [sys.executable, str(root / "agent_os/scripts/build_android.py")]
    for name in ("android-jar", "build-tools", "keystore", "key-alias", "password-env", "output-dir"):
        builder += ["--" + name, str(getattr(args, name.replace("-", "_")))]
    if args.model_runtime:
        builder += ["--native-lib", str(args.model_runtime)]
    subprocess.run(builder, cwd=root, check=True)
    env = os.environ.copy()
    env["AGENT_OS_BUILD_REPORT"] = str((args.output_dir / "build_report.json").resolve())
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "agent_os/tests", "-p", "test_*.py", "-v"],
                   cwd=root, env=env, check=True)
    command = [sys.executable, str(root / "agent_os/scripts/verify_release_android.py"),
                    "--apk", str(args.output_dir / "agent-os.apk"), "--report", str(args.output_dir / "build_report.json"),
                    "--build-tools", str(args.build_tools), "--expected-signer", args.expected_signer,
                    "--previous-apk", str(args.previous_apk), "--output", str(verification)]
    if args.model_runtime:
        command.append("--require-model-runtime")
    subprocess.run(command, cwd=root, check=True)
    print("Candidate is verified locally. Device acceptance and a data-recovery rehearsal are still required before distribution.")


if __name__ == "__main__":
    main()
