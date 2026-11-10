#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Build an isolated, same-signer APK for synthetic Android service binding."""

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
    parser.add_argument("--production-build", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    fixture = root / "android/lifecycle_probe"
    build = args.output_dir
    classes, dex = build / "classes", build / "dex"
    for directory in (classes, dex):
        directory.mkdir(parents=True, exist_ok=True)
    java = sorted((root / "android/src").rglob("*.java")) + sorted((fixture / "src").rglob("*.java"))
    subprocess.run(["javac", "--release", "8", "-classpath", str(args.android_jar), "-d", str(classes), *map(str, java)], check=True)
    subprocess.run([str(args.build_tools / "d8"), "--min-api", "26", "--lib", str(args.android_jar), "--output", str(dex), *map(str, sorted(classes.rglob("*.class")))], check=True)
    unsigned, aligned, apk = [build / name for name in ("unsigned.apk", "aligned.apk", "probe.apk")]
    subprocess.run([str(args.build_tools / "aapt2"), "link", "-I", str(args.android_jar), "--manifest", str(fixture / "AndroidManifest.xml"), "-A", str(args.production_build / "assets"), "-o", str(unsigned)], check=True)
    dependency = args.production_build / "traceform.apk"
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive, zipfile.ZipFile(dependency) as production:
        archive.write(dex / "classes.dex", "classes.dex")
        for name in production.namelist():
            if name.startswith("lib/"):
                archive.writestr(name, production.read(name))
    subprocess.run([str(args.build_tools / "zipalign"), "-f", "-p", "4", str(unsigned), str(aligned)], check=True)
    subprocess.run([str(args.build_tools / "apksigner"), "sign", "--ks", str(args.keystore), "--ks-pass", "pass:traceform-local", "--out", str(apk), str(aligned)], check=True)
    subprocess.run([str(args.build_tools / "apksigner"), "verify", "--verbose", str(apk)], check=True)
    sources = [Path(__file__).resolve(), fixture / "AndroidManifest.xml", *java]
    report = {
        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "dependency_apk_sha256": hashlib.sha256(dependency.read_bytes()).hexdigest(),
        "source_sha256": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        "scope": "separate_uid_service_binding_and_explicit_bounded_acquisition_probes",
    }
    (build / "build_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"apk": str(apk.resolve()), "apk_sha256": report["apk_sha256"]}))


if __name__ == "__main__":
    main()
