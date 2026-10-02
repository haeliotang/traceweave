#!/usr/bin/env python3
"""Build a separately signed, no-network SDK permission test application."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = root / "android/security_probe"
    build = args.output_dir
    classes, dex = build / "classes", build / "dex"
    for directory in (classes, dex):
        directory.mkdir(parents=True, exist_ok=True)
    java = sorted((source / "src").rglob("*.java"))
    subprocess.run(["javac", "--release", "8", "-classpath", str(args.android_jar), "-d", str(classes), *map(str, java)], check=True)
    subprocess.run([str(args.build_tools / "d8"), "--min-api", "26", "--lib", str(args.android_jar), "--output", str(dex), *map(str, sorted(classes.rglob("*.class")))], check=True)
    unsigned, aligned, apk = [build / name for name in ("unsigned.apk", "aligned.apk", "permission-probe.apk")]
    subprocess.run([str(args.build_tools / "aapt2"), "link", "-I", str(args.android_jar), "--manifest", str(source / "AndroidManifest.xml"), "-o", str(unsigned)], check=True)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex / "classes.dex", "classes.dex")
    subprocess.run([str(args.build_tools / "zipalign"), "-f", "-p", "4", str(unsigned), str(aligned)], check=True)
    key = build / "probe-only.keystore"
    if not key.exists():
        subprocess.run(["keytool", "-genkeypair", "-keystore", str(key), "-storepass", "probe-local", "-keypass", "probe-local", "-alias", "probe", "-dname", "CN=Traceform Permission Probe", "-keyalg", "RSA", "-validity", "3650"], check=True, capture_output=True)
    subprocess.run([str(args.build_tools / "apksigner"), "sign", "--ks", str(key), "--ks-pass", "pass:probe-local", "--out", str(apk), str(aligned)], check=True)
    subprocess.run([str(args.build_tools / "apksigner"), "verify", "--verbose", str(apk)], check=True)
    sources = [Path(__file__).resolve(), source / "AndroidManifest.xml", *java]
    report = {"apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "apk_bytes": apk.stat().st_size,
              "source_sha256": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
              "scope": "separate_development_signer_permission_probe_only"}
    (build / "build_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"apk": str(apk.resolve()), "apk_bytes": report["apk_bytes"]}))


if __name__ == "__main__":
    main()
