#!/usr/bin/env python3
"""Build and run a synthetic-only Automerge probe, then remove its isolated app."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[3]
PACKAGE = "org.traceform.agentos.automergevalidation"
EXPECTED = {
    "jar": "7629272a27287b675cd26209cb53ec9aaf4b0e3ecb464fb4df7e7a08b9e24c8e",
    "aar": "fbc19dda6567f48109d68c02c962fe03b9000fe54470bf4e8dd59b9b0b2c732f",
    "slf4j": "0818930dc8d7debb403204611691da58e49d42c50b6ffcfdce02dadb7c3c2b6c",
}


def run(command, **kwargs):
    return subprocess.run([str(item) for item in command], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("jar", "aar", "slf4j", "android-jar", "build-tools", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    for name, expected in EXPECTED.items():
        if hashlib.sha256(getattr(args, name).read_bytes()).hexdigest() != expected:
            raise ValueError("Unexpected public dependency digest: " + name)
    adb = [args.adb, "-s", args.serial]
    packages = run(adb + ["shell", "pm", "list", "packages", PACKAGE], capture_output=True, text=True).stdout.splitlines()
    if "package:" + PACKAGE in packages:
        raise RuntimeError("Isolated package already exists; refusing to replace it")
    sources = [ROOT / "agent_os/tests/automerge/AutomergeConflictProbe.java",
               ROOT / "agent_os/tests/automerge/AutomergeValidationActivity.java"]
    manifest = ROOT / "agent_os/tests/automerge/AndroidManifest.xml"
    with tempfile.TemporaryDirectory(prefix="automerge-android-") as directory:
        temp = Path(directory); classes = temp / "classes"; dex = temp / "dex"; classes.mkdir(); dex.mkdir()
        classpath = os.pathsep.join(str(path.resolve()) for path in (args.android_jar, args.jar, args.slf4j))
        run(["javac", "--release", "8", "-classpath", classpath, "-d", classes, *sources])
        run([args.build_tools / "d8", "--min-api", "29", "--lib", args.android_jar, "--output", dex,
             *classes.rglob("*.class"), args.jar, args.slf4j])
        unsigned = temp / "unsigned.apk"; aligned = temp / "aligned.apk"; apk = temp / "probe.apk"
        run([args.build_tools / "aapt2", "link", "-I", args.android_jar, "--manifest", manifest, "-o", unsigned])
        with zipfile.ZipFile(args.aar) as aar, zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(dex / "classes.dex", "classes.dex")
            archive.writestr("lib/arm64-v8a/libautomerge_jni_0_2_0.so", aar.read("jni/arm64-v8a/libautomerge_jni_0_2_0.so"))
        run([args.build_tools / "zipalign", "-f", "-p", "4", unsigned, aligned])
        env = os.environ.copy(); env["AUTOMERGE_TEST_PASSWORD"] = secrets.token_urlsafe(24)
        key = temp / "test.p12"
        run(["keytool", "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
             "-dname", "CN=Automerge Synthetic Test", "-storetype", "PKCS12", "-keystore", key,
             "-storepass:env", "AUTOMERGE_TEST_PASSWORD", "-keypass:env", "AUTOMERGE_TEST_PASSWORD", "-noprompt"], env=env)
        run([args.build_tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "test",
             "--ks-pass", "env:AUTOMERGE_TEST_PASSWORD", "--out", apk, aligned], env=env)
        installed = False
        try:
            run(adb + ["install", apk], capture_output=True); installed = True
            run(adb + ["shell", "am", "start", "-W", "-S", "-n", PACKAGE + "/org.traceform.agentos.AutomergeValidationActivity"], capture_output=True)
            pid = run(adb + ["shell", "pidof", PACKAGE], capture_output=True, text=True).stdout.strip()
            if not pid.isdecimal(): raise RuntimeError("Isolated test process unavailable")
            result = None
            for _ in range(60):
                logs = run(adb + ["logcat", "-d", "--pid=" + pid, "-s", "AgentOsAutomerge:I", "*:S"], capture_output=True, text=True).stdout
                lines = [line for line in logs.splitlines() if "PASS " in line or "FAIL " in line]
                if lines: result = lines[-1]; break
                time.sleep(1)
            if result is None or "PASS " not in result: raise RuntimeError("Automerge probe failed or timed out: " + str(result))
            edit_visible = "concurrent_edit_visible_after_delete=true" in result
            apk_sha = hashlib.sha256(apk.read_bytes()).hexdigest()
        finally:
            if installed: run(adb + ["uninstall", PACKAGE], capture_output=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"synthetic_only": True, "network_permission": False,
        "automerge_version": "0.0.9", "dependency_sha256": EXPECTED, "apk_sha256": apk_sha,
        "concurrent_edits_and_resolution_passed": True, "save_load_and_old_replay_passed": True,
        "concurrent_edit_visible_after_delete": edit_visible, "old_value_readable_at_historical_head": True,
        "separate_tombstone_survived_honest_offline_edit_and_old_replay": True,
        "isolated_package_removed": True,
        "source_sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources + [manifest]},
        "scope": "compatibility experiment only; no production sync, authentication or encryption"}, indent=2) + "\n")
    print("Automerge synthetic Android probe passed; isolated app removed")


if __name__ == "__main__":
    main()
