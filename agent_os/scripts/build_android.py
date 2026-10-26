#!/usr/bin/env python3
"""Build the standalone Agent OS Android prototype APK with an installed SDK."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def run(arguments):
    subprocess.run([str(argument) for argument in arguments], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--key-alias", required=True)
    parser.add_argument("--password-env", default="AGENT_OS_KEYSTORE_PASSWORD")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--source-dir", type=Path)
    parser.add_argument("--extra-source", type=Path, action="append", default=[])
    parser.add_argument("--native-lib", type=Path, action="append", default=[])
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--scope", default="local_executor_job_scheduler_encrypted_checkpoint_archive_and_user_confirmed_demo_capability_broker")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[2]
    manifest = (args.manifest or root / "agent_os/android/AndroidManifest.xml").resolve()
    source_dir = (args.source_dir or root / "agent_os/android/src").resolve()
    sources = sorted(source_dir.rglob("*.java"))
    sources.extend(path.resolve() for path in args.extra_source)
    android_jar = args.android_jar.resolve()
    build_tools = args.build_tools.resolve()
    keystore = args.keystore.resolve()
    output_dir = (args.output_dir or root / "agent_os/build").resolve()
    password = os.environ.get(args.password_env)
    if not android_jar.is_file():
        parser.error("--android-jar must point to an installed Android platform android.jar")
    if not manifest.is_file():
        parser.error("Manifest is missing: " + str(manifest))
    if any(not source.is_file() for source in sources):
        parser.error("--extra-source must point to existing Java files")
    if not keystore.is_file():
        parser.error("--keystore must point to an existing local development keystore")
    if not password:
        parser.error("Set the keystore password in the environment variable named by --password-env")

    tools = {name: build_tools / name for name in ("aapt2", "d8", "zipalign", "apksigner")}
    for name, path in tools.items():
        if not path.is_file():
            parser.error("Missing Android build tool: " + str(path))

    aidl_tool = build_tools / "aidl"
    build = output_dir
    classes = build / "classes"
    dex = build / "dex"
    gen = build / "gen"
    build.mkdir(parents=True, exist_ok=True)
    for directory in (classes, dex, gen):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)

    aidl_files = sorted(source_dir.rglob("*.aidl"))
    if aidl_files and aidl_tool.is_file():
        for aidl_file in aidl_files:
            run([aidl_tool, "--lang=java", "-I", str(source_dir), "-o", str(gen), str(aidl_file)])
        sources.extend(sorted(gen.rglob("*.java")))

    if not sources:
        parser.error("No Java or AIDL sources found in " + str(source_dir))

    run(["javac", "--release", "8", "-classpath", android_jar, "-d", classes, *sources])
    class_files = sorted(classes.rglob("*.class"))
    run([tools["d8"], "--min-api", "26", "--lib", android_jar, "--output", dex, *class_files])

    unsigned = build / "agent-os-unsigned.apk"
    aligned = build / "agent-os-aligned.apk"
    apk = build / "agent-os.apk"

    res_dir = root / "agent_os/android/res"
    if res_dir.is_dir():
        compiled_res = build / "compiled_res.zip"
        run([tools["aapt2"], "compile", "--dir", res_dir, "-o", compiled_res])
        run([tools["aapt2"], "link", "-I", android_jar, "--manifest", manifest, "-R", compiled_res, "--auto-add-overlay", "-o", unsigned])
    else:
        run([tools["aapt2"], "link", "-I", android_jar, "--manifest", manifest, "-o", unsigned])
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex / "classes.dex", "classes.dex")
        for lib in args.native_lib:
            lib_path = lib.resolve()
            if lib_path.is_file():
                archive.write(lib_path, f"lib/arm64-v8a/{lib_path.name}")
    run([tools["zipalign"], "-f", "-p", "4", unsigned, aligned])
    run([tools["apksigner"], "sign", "--ks", keystore, "--ks-key-alias", args.key_alias,
         "--ks-pass", "env:" + args.password_env, "--out", apk, aligned])
    run([tools["apksigner"], "verify", "--verbose", apk])

    tracked_sources = [manifest, *sources, Path(__file__).resolve()]
    tracked_sources.extend(sorted(path for path in res_dir.rglob("*") if path.is_file()))
    tracked_sources.extend(path.resolve() for path in args.native_lib if path.is_file())

    def source_label(path):
        for base, label in ((root, ""), (source_dir, "custom_source/"), (build, "generated/")):
            try:
                relative = path.relative_to(base)
                return label + str(relative)
            except ValueError:
                pass
        return "external/" + path.name

    manifest_attributes = ET.parse(manifest).getroot().attrib
    android_namespace = "{http://schemas.android.com/apk/res/android}"
    report = {
        "package": manifest_attributes.get("package"),
        "version": manifest_attributes.get(android_namespace + "versionName"),
        "version_code": int(manifest_attributes.get(android_namespace + "versionCode", "0")),
        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "apk_bytes": apk.stat().st_size,
        "signing": "local_development_key",
        "source_sha256": {
            source_label(path): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in tracked_sources
        },
        "scope": args.scope,
    }
    (build / "build_report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"apk": str(apk), "apk_bytes": report["apk_bytes"]}))


if __name__ == "__main__":
    main()
