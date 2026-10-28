#!/usr/bin/env python3
"""Verify a local Android release candidate; never installs, uninstalls or publishes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "org.traceform.agentos"
MODEL_PROBES = ("LocalModelProbe", "LocalModelQualityProbe", "LocalModelDocumentProbe")


def run(*args):
    return subprocess.check_output([str(a) for a in args], text=True, stderr=subprocess.STDOUT)


def apk_identity(apk, tools):
    badging = run(tools / "aapt2", "dump", "badging", apk)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if match is None:
        raise ValueError("APK identity could not be read")
    certificates = run(tools / "apksigner", "verify", "--print-certs", apk)
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", certificates)
    if len(signers) != 1:
        raise ValueError("Expected one verified APK signer")
    return {"package": match[1], "version_code": int(match[2]), "version": match[3],
            "debuggable": "application-debuggable" in badging, "signer_sha256": signers[0].lower()}


def tree_nodes(text):
    nodes, stack = [], []
    for line in text.splitlines():
        element = re.match(r"(\s*)E: (\S+)", line)
        if element:
            indent = len(element[1])
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = {"name": element[2], "attributes": {}, "children": [], "text": []}
            if stack:
                stack[-1][1]["children"].append(node)
            nodes.append(node); stack.append((indent, node))
        elif stack and "A: " in line:
            key, value = line.split("A: ", 1)[1].split("=", 1)
            key = re.sub(r"\(0x[0-9a-f]+\)$", "", key.rsplit(":", 1)[-1])
            stack[-1][1]["attributes"][key] = value.split(" (Raw:", 1)[0].strip('"')
        elif stack and "T: " in line:
            stack[-1][1]["text"].append(line.split("T: ", 1)[1].strip("'"))
    return nodes


def check_manifest(nodes):
    app = next(n for n in nodes if n["name"] == "application")
    if app["attributes"].get("allowBackup") != "false" or app["attributes"].get("usesCleartextTraffic") != "false":
        raise ValueError("Backup and default cleartext must be disabled")
    for node in nodes:
        name = node["attributes"].get("name", "")
        if "Validation" in name or "Fixture" in name or "BackgroundProbe" in name or "ControlledProbe" in name or any(probe in name for probe in MODEL_PROBES) or node["name"] == "provider":
            raise ValueError("Test components must not be packaged in production")
        if node["name"] == "activity" and node["attributes"].get("exported") == "true" and name not in (".MainActivity", PACKAGE + ".MainActivity"):
            raise ValueError("Only the launcher activity may be exported")


def network_policy_path(nodes, resources):
    app = next(n for n in nodes if n["name"] == "application")
    reference = app["attributes"].get("networkSecurityConfig", "")
    if not re.fullmatch(r"@0x[0-9a-fA-F]+", reference):
        raise ValueError("Manifest must reference a packaged network policy")
    marker = "resource " + reference[1:] + " "
    if marker not in resources:
        raise ValueError("Referenced network policy resource is missing")
    block = re.split(r"\n\s*resource\s+", resources.split(marker, 1)[1], maxsplit=1)[0]
    files = re.findall(r"\(file\) (\S+) type=XML", block)
    if len(files) != 1:
        raise ValueError("Expected one auditable network policy variant")
    return files[0]


def verify(apk, report, tools, expected_signer, previous=None):
    identity = apk_identity(apk, tools)
    if identity["package"] != PACKAGE or identity["debuggable"]:
        raise ValueError("Expected a non-debuggable production APK")
    if identity["signer_sha256"] != expected_signer.lower():
        raise ValueError("APK signer differs from the expected update identity")
    if report["apk_sha256"] != hashlib.sha256(apk.read_bytes()).hexdigest():
        raise ValueError("APK digest differs from build report")
    if any(identity[key] != report[key] for key in ("package", "version", "version_code")):
        raise ValueError("APK identity differs from build report")
    inputs = report["source_sha256"]
    for label, digest in inputs.items():
        path = (ROOT / label).resolve()
        if not path.is_relative_to(ROOT) or "/tests/android/" in label or not path.is_file():
            raise ValueError("Unexpected or unavailable production input: " + label)
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError("Input changed after build: " + label)
    for path in (ROOT / "agent_os/android/res").rglob("*"):
        if path.is_file() and str(path.relative_to(ROOT)) not in inputs:
            raise ValueError("Build report omitted a packaged resource")
    manifest = tree_nodes(run(tools / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk))
    check_manifest(manifest)
    policy_path = network_policy_path(manifest, run(tools / "aapt2", "dump", "resources", apk))
    security = tree_nodes(run(tools / "aapt2", "dump", "xmltree", "--file", policy_path, apk))
    bases = [n for n in security if n["name"] == "base-config"]
    if len(bases) != 1 or bases[0]["attributes"].get("cleartextTrafficPermitted") != "false":
        raise ValueError("Network security must forbid default cleartext")
    for node in security:
        if node["name"] == "domain-config" and node["attributes"].get("cleartextTrafficPermitted") == "true":
            domains = [c for c in node["children"] if c["name"] == "domain"]
            if not domains or any(c["attributes"].get("includeSubdomains") == "true" or c["text"] not in (["localhost"], ["127.0.0.1"]) for c in domains):
                raise ValueError("Cleartext exception must be limited to explicit loopback hosts")
    with zipfile.ZipFile(apk) as archive:
        dex = b"".join(archive.read(n) for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        if any(name in dex for name in (b"BackgroundProbeActivity", b"ControlledProbeService", b"DocumentFlowValidationActivity", b"WorkflowValidationActivity", b"DocumentFixtureProvider", *(probe.encode() for probe in MODEL_PROBES))):
            raise ValueError("Test code was included in production DEX")
    if previous:
        old = apk_identity(previous, tools)
        if old["package"] != PACKAGE or old["signer_sha256"] != identity["signer_sha256"] or old["version_code"] >= identity["version_code"]:
            raise ValueError("Previous APK must be the same signer/package and a lower version")
    return {**identity, "apk_sha256": report["apk_sha256"], "input_count": len(inputs),
            "packaged_manifest_checked": True, "packaged_network_policy_checked": True,
            "test_components_excluded": True, "previous_same_signer_upgrade_checked": previous is not None,
            "verifier_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "scope": "local_candidate_only_not_store_release_or_os_ota"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--expected-signer", required=True)
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.apk, json.loads(args.report.read_text()), args.build_tools, args.expected_signer, args.previous_apk)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        raise SystemExit("Release verification failed: " + str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print("Local release candidate verified: " + result["version"])


if __name__ == "__main__":
    main()
