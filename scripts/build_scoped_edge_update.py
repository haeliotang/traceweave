#!/usr/bin/env python3
"""Build an Edge update preserving the installed APK's existing code, assets and native library.

This only builds and verifies a candidate; it never reads Edge's database or installs an APK.
The caller must independently verify the supplied baseline is the installed APK before installation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "agent_os/scripts"))
from verify_release_android import apk_identity, tree_nodes

PACKAGE = "org.traceform.edge"
NEW_PERMISSION = PACKAGE + ".READ_HISTORY_SUMMARY"
NEW_SERVICE = ".HistorySummaryService"
TRUSTED_SIGNER = "9effee32f57b35c256529e97cd8db435813e84025d48601c00770b6adf95a6db"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)


def manifest_nodes(apk, tools):
    text = run([tools / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
               capture_output=True, text=True).stdout
    return tree_nodes(text)


def verify_manifest(old, new):
    additions = [n for n in new if n["attributes"].get("name") in (NEW_PERMISSION, NEW_SERVICE)]
    if len(additions) != 2:
        raise ValueError("Expected exactly the new permission and summary service")
    permission = next(n for n in additions if n["name"] == "permission")
    service = next(n for n in additions if n["name"] == "service")
    if permission["attributes"] != {"name": NEW_PERMISSION, "protectionLevel": "0x08000002", "knownCerts": TRUSTED_SIGNER}:
        raise ValueError("Unexpected summary permission")
    if service["attributes"] != {"name": NEW_SERVICE, "permission": NEW_PERMISSION,
                                  "exported": "true", "process": ":edge"}:
        raise ValueError("Unexpected summary service")
    def normalized(nodes):
        result = []
        for node in nodes:
            if node["attributes"].get("name") in (NEW_PERMISSION, NEW_SERVICE):
                continue
            attributes = dict(node["attributes"])
            if node["name"] == "manifest":
                attributes.pop("versionCode", None)
                attributes.pop("versionName", None)
            result.append((node["name"], attributes, node["text"]))
        return result
    if normalized(old) != normalized(new):
        raise ValueError("Existing manifest behavior changed")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("baseline-apk", "compiled-classes", "android-jar", "build-tools", "keystore", "output-dir"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--expected-signer", required=True)
    p.add_argument("--key-alias", default="traceform")
    p.add_argument("--password-env", default="TRACEFORM_EDGE_KEYSTORE_PASSWORD")
    args = p.parse_args()
    out = args.output_dir.resolve()
    baseline = args.baseline_apk.resolve()
    if not baseline.is_file() or baseline.is_relative_to(out):
        p.error("Keep a separate existing baseline APK")
    if not os.environ.get(args.password_env):
        p.error("Set the signing password in the named environment variable")
    out.mkdir(parents=True, exist_ok=True)
    (out / "candidate_report.json").unlink(missing_ok=True)
    tools = args.build_tools.resolve()
    old = apk_identity(baseline, tools)
    if old["package"] != PACKAGE or old["signer_sha256"] != args.expected_signer.lower():
        raise ValueError("Baseline package or signer is not the trusted Edge identity")
    source = ROOT / "android/src/org/traceform/edge/HistorySummaryService.java"
    manifest_source = ROOT / "android/AndroidManifest.xml"
    classes, dex = out / "classes", out / "dex"
    classes.mkdir(exist_ok=True); dex.mkdir(exist_ok=True)
    run(["javac", "--release", "8", "-classpath", str(args.android_jar.resolve()) + os.pathsep + str(args.compiled_classes.resolve()),
         "-d", classes, source])
    files = sorted(classes.glob("org/traceform/edge/HistorySummaryService*.class"))
    if {f.name for f in files} != {"HistorySummaryService.class", "HistorySummaryService$1.class"}:
        raise ValueError("Unexpected summary service classes")
    run([tools / "d8", "--min-api", "26", "--lib", args.android_jar, "--classpath", args.compiled_classes,
         "--output", dex, *files])
    dexdump = run([tools / "dexdump", "-f", dex / "classes.dex"], capture_output=True, text=True).stdout
    import re
    descriptors = set(re.findall(r"Class descriptor\s+: '([^']+)'", dexdump))
    if descriptors != {"Lorg/traceform/edge/HistorySummaryService;", "Lorg/traceform/edge/HistorySummaryService$1;"}:
        raise ValueError("Unexpected code in added DEX")
    ET.register_namespace("android", ANDROID[1:-1])
    tree = ET.parse(manifest_source)
    app = tree.getroot().find("application")
    if old["debuggable"]:
        app.set(ANDROID + "debuggable", "true")
    else:
        app.attrib.pop(ANDROID + "debuggable", None)
    manifest = out / "manifest.xml"
    tree.write(manifest, encoding="utf-8", xml_declaration=True)
    unsigned = out / "unsigned.apk"
    run([tools / "aapt2", "link", "-I", args.android_jar, "--manifest", manifest, "-o", unsigned])
    with zipfile.ZipFile(baseline) as original, zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as result:
        names = original.namelist()
        if len(names) != len(set(names)) or any(n.startswith("classes2") for n in names):
            raise ValueError("Unsupported baseline APK entries")
        if original.read("resources.arsc") != result.read("resources.arsc"):
            raise ValueError("Baseline resources table differs from regenerated manifest")
        for item in original.infolist():
            if item.filename in ("AndroidManifest.xml", "resources.arsc") or item.filename.startswith("META-INF/"):
                continue
            result.writestr(item, original.read(item))
        result.write(dex / "classes.dex", "classes2.dex")
    aligned, candidate = out / "aligned.apk", out / "edge-scoped.apk"
    run([tools / "zipalign", "-f", "-p", "4", unsigned, aligned])
    run([tools / "apksigner", "sign", "--ks", args.keystore, "--ks-key-alias", args.key_alias,
         "--ks-pass", "env:" + args.password_env, "--out", candidate, aligned])
    new = apk_identity(candidate, tools)
    if new["package"] != PACKAGE or new["signer_sha256"] != old["signer_sha256"] or new["version_code"] <= old["version_code"] or new["debuggable"] != old["debuggable"]:
        raise ValueError("Update identity, version or debug state changed unexpectedly")
    verify_manifest(manifest_nodes(baseline, tools), manifest_nodes(candidate, tools))
    with zipfile.ZipFile(baseline) as original, zipfile.ZipFile(candidate) as result:
        preserved = [n for n in original.namelist() if n != "AndroidManifest.xml" and not n.startswith("META-INF/")]
        if any(n not in result.namelist() or digest(original.read(n)) != digest(result.read(n)) for n in preserved):
            raise ValueError("A baseline APK entry changed")
    report = {"baseline_sha256": digest(baseline.read_bytes()), "candidate_sha256": digest(candidate.read_bytes()),
              "package": PACKAGE, "version_code": new["version_code"], "signer_matches_baseline": True,
              "debuggable_preserved": new["debuggable"], "all_original_code_assets_and_native_preserved": True,
              "original_manifest_preserved_except_version_and_scoped_service": True,
              "summary_source_sha256": digest(source.read_bytes()), "manifest_source_sha256": digest(manifest_source.read_bytes()),
              "scope": "candidate_only_no_user_database_read_or_device_install"}
    (out / "candidate_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Scoped Edge candidate verified; baseline APK contents preserved")


if __name__ == "__main__":
    main()
