#!/usr/bin/env python3
"""Recover the signing keystores from an encrypted .edgea backup and prove they work.

The drill is read-only against the archive and never prints key material: it
authenticates the archive, extracts the keystores into a private temporary
directory, signs a throwaway copy of an APK, verifies the signer certificate
against the expected fingerprint, and removes everything afterwards.
"""
import argparse
import hashlib
import io
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import edge_encrypted_archive as eea

ROOT = pathlib.Path(__file__).resolve().parents[1]


def tool(name):
    """Resolve a JDK or Android build-tools binary even when PATH is minimal."""
    found = shutil.which(name)
    if found:
        return found
    candidates = []
    if name in ("keytool", "jarsigner"):
        candidates += sorted(pathlib.Path("/opt/homebrew/opt").glob("openjdk@*/libexec/openjdk.jdk/Contents/Home/bin/*"))
        candidates += sorted(pathlib.Path("/Library/Java/JavaVirtualMachines").glob("*/Contents/Home/bin/*"))
    if name == "apksigner":
        home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or str(pathlib.Path.home() / "Library/Android/sdk")
        candidates += sorted(pathlib.Path(home).glob("build-tools/*/apksigner"), reverse=True)
    for candidate in candidates:
        if candidate.name == name and candidate.is_file():
            return str(candidate)
    return name


def read_archive_key(service, account):
    """Read the 32-byte archive key from the macOS keychain (hex, no trailing newline)."""
    result = subprocess.run(
        ["security", "find-generic-password", "-s", service, "-a", account, "-w"],
        capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(
            "keychain read failed (exit %d). Unlock the login keychain and allow access "
            "for this item, then retry." % result.returncode)
    secret = result.stdout.strip()
    if len(secret) != 64:
        raise SystemExit("keychain secret is not a 64-character hex key")
    return bytes.fromhex(secret)


def read_plaintext(archive_path, key, limit_bytes=64 * 1024 * 1024):
    """Authenticate the archive and return the whole plaintext (small backups only)."""
    with open(archive_path, "rb") as source:
        length, digest = eea.verify_archive(io.BytesIO(source.read()), key)
    if length > limit_bytes:
        raise SystemExit("plaintext is %d bytes; this drill only handles small archives" % length)
    with open(archive_path, "rb") as source:
        plaintext = io.BufferedReader(eea.VerifiedPlaintext(source, key))
        data = plaintext.read()
    if len(data) != length:
        raise SystemExit("archive length changed while reading")
    return data, digest


def describe_payload(data):
    """Redacted structure report: names, types and sizes only, never values."""
    try:
        parsed = json.loads(data)
    except Exception:
        return {"format": "binary", "head_hex": data[:16].hex(), "bytes": len(data)}
    report = {"format": "json", "bytes": len(data), "keys": {}}
    if isinstance(parsed, dict):
        for name, value in parsed.items():
            if isinstance(value, str):
                report["keys"][name] = {"type": "string", "chars": len(value),
                                        "looks_base64": bool(value) and all(
                                            char in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=" for char in value[:64])}
            elif isinstance(value, dict):
                report["keys"][name] = {"type": "object", "fields": sorted(value.keys())}
            elif isinstance(value, list):
                report["keys"][name] = {"type": "list", "items": len(value)}
            else:
                report["keys"][name] = {"type": type(value).__name__}
    return report


def extract(archive_path, key, destination):
    """Authenticate the archive and unpack keystores it carries, whatever the container is."""
    data, digest = read_plaintext(archive_path, key)
    written = []

    # Case 1: tar with databases/files/shared_prefs (the data backup layout).
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as archive:
            for member in archive:
                name = member.name.removeprefix("./").rstrip("/")
                parts = pathlib.PurePosixPath(name).parts
                if (not name or name.startswith("/") or ".." in parts
                        or any(ord(char) < 32 for char in name)):
                    raise SystemExit("unsafe archive path: %r" % name)
                target = destination / name
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                if not member.isfile():
                    raise SystemExit("unsupported archive entry: %r" % name)
                target.parent.mkdir(parents=True, exist_ok=True)
                with open(target, "wb") as handle:
                    shutil.copyfileobj(archive.extractfile(member), handle)
                os.chmod(target, 0o600)
                written.append(name)
        return written
    except tarfile.ReadError:
        pass

    # Case 2: JSON manifest whose binary fields carry the keystores.
    try:
        parsed = json.loads(data)
    except Exception:
        raise SystemExit("archive payload is neither tar nor json; run --inspect to see its shape")

    def walk(node, prefix):
        if isinstance(node, dict):
            for name, value in node.items():
                walk(value, prefix + [str(name)])
        elif isinstance(node, list):
            for index, value in enumerate(node):
                walk(value, prefix + [str(index)])
        elif isinstance(node, str):
            text = node.strip()
            if len(text) < 64 or len(text) % 4:
                return
            try:
                import base64
                blob = base64.b64decode(text, validate=True)
            except Exception:
                return
            # PKCS#12 starts with 30 82 / 30 81, JKS with FE ED FE ED.
            if blob[:2] in (b"\x30\x82", b"\x30\x81") or blob[:4] == b"\xfe\xed\xfe\xed":
                name = "-".join(prefix) + (".p12" if blob[:1] == b"\x30" else ".jks")
                target = destination / name
                target.parent.mkdir(parents=True, exist_ok=True)
                with open(target, "wb") as handle:
                    handle.write(blob)
                os.chmod(target, 0o600)
                written.append(name)

    walk(parsed, [])
    if written:
        # Keep the manifest next to the keystores so passwords can be read.
        manifest = destination / "backup-manifest.json"
        manifest.write_text(json.dumps(parsed, ensure_ascii=False, indent=1))
        os.chmod(manifest, 0o600)
        written.append("backup-manifest.json")
    return written


def certificate_digest(keystore, alias, storepass):
    result = subprocess.run(
        [tool("keytool"), "-list", "-v", "-keystore", str(keystore), "-alias", alias,
         "-storepass", storepass],
        capture_output=True, text=True)
    if result.returncode != 0:
        return None, result.stderr.strip().splitlines()[-1] if result.stderr else "keytool failed"
    for line in result.stdout.splitlines():
        if "SHA256:" in line:
            # keytool prints AA:BB:CC; the release tooling compares plain hex.
            return line.split("SHA256:", 1)[1].replace(":", "").strip().lower(), None
    return None, "no SHA256 fingerprint in keytool output"


def sign_and_verify(keystore, alias, storepass, unsigned_apk, workdir):
    signed = workdir / "signed.apk"
    shutil.copyfile(unsigned_apk, signed)
    result = subprocess.run(
        [tool("apksigner"), "sign", "--ks", str(keystore), "--ks-key-alias", alias,
         "--ks-pass", "pass:" + storepass, "--out", str(signed), str(unsigned_apk)],
        capture_output=True, text=True)
    if result.returncode != 0:
        return None, (result.stderr or result.stdout).strip().splitlines()[-1:]
    verify = subprocess.run([tool("apksigner"), "verify", "--print-certs", str(signed)],
                            capture_output=True, text=True)
    if verify.returncode != 0:
        return None, (verify.stderr or verify.stdout).strip().splitlines()[-1:]
    digest = None
    for line in verify.stdout.splitlines():
        if "SHA-256 digest" in line:
            digest = line.split(":", 1)[1].strip().lower()
    return digest, None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True)
    parser.add_argument("--keychain-service", default="com.inceptless.traceform.edge.archive")
    parser.add_argument("--keychain-account", required=True)
    parser.add_argument("--keychain-service-recovery", default=None,
                        help="optional second entry to try if the primary one is denied")
    parser.add_argument("--unsigned-apk", default=str(ROOT / "agent_os/build/agent-os-unsigned.apk"))
    parser.add_argument("--expect", action="append", default=[],
                        help="alias=fingerprint, e.g. agent-os-rotation-20261025=9effee32…")
    parser.add_argument("--output", required=True)
    parser.add_argument("--inspect", action="store_true",
                        help="print the redacted payload structure instead of running the drill")
    args = parser.parse_args()

    expected = {}
    for item in args.expect:
        alias, _, fingerprint = item.partition("=")
        expected[alias] = fingerprint.lower()

    archive = pathlib.Path(args.archive)
    receipt = {
        "date": subprocess.run(["date", "+%Y-%m-%d"], capture_output=True, text=True).stdout.strip(),
        "archive": str(archive),
        "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
        "keychain_service": args.keychain_service,
    }

    try:
        key = read_archive_key(args.keychain_service, args.keychain_account)
    except SystemExit:
        if not args.keychain_service_recovery:
            raise
        key = read_archive_key(args.keychain_service_recovery, args.keychain_account)
        receipt["keychain_service"] = args.keychain_service_recovery

    if args.inspect:
        data, digest = read_plaintext(archive, key)
        report = describe_payload(data)
        report["plaintext_sha256"] = digest
        print(json.dumps(report, ensure_ascii=False, indent=1))
        return 0

    work = pathlib.Path(tempfile.mkdtemp(prefix="traceweave-recovery-"))
    os.chmod(work, 0o700)
    try:
        members = extract(archive, key, work)
        receipt["archive_authenticated"] = True
        receipt["members"] = members

        keystores = [path for path in sorted(work.rglob("*"))
                     if path.is_file() and path.suffix.lower() in (".p12", ".jks", ".keystore")]
        receipt["keystores_found"] = [str(path.relative_to(work)) for path in keystores]

        # Candidate passwords: any short string in the manifest whose key looks like a
        # credential. The drill tries each one against keytool instead of assuming a schema.
        candidates = set()
        for candidate in sorted(work.rglob("*")):
            if not candidate.is_file() or candidate.suffix.lower() != ".json":
                continue
            try:
                payload = json.loads(candidate.read_text())
            except Exception:
                continue
            stack = [payload]
            while stack:
                node = stack.pop()
                if isinstance(node, dict):
                    for name, value in node.items():
                        if isinstance(value, str) and 0 < len(value) <= 128 and any(
                                hint in name.lower() for hint in ("pass", "secret", "pin")):
                            candidates.add(value)
                        elif isinstance(value, (dict, list)):
                            stack.append(value)
                elif isinstance(node, list):
                    stack.extend(item for item in node if isinstance(item, (dict, list)))
        receipt["password_candidates_tried"] = len(candidates)

        def keytool_aliases(keystore, storepass):
            result = subprocess.run(
                [tool("keytool"), "-list", "-keystore", str(keystore), "-storepass", storepass],
                capture_output=True, text=True)
            if result.returncode != 0:
                return []
            aliases = []
            for line in result.stdout.splitlines():
                if "," in line and any(word in line for word in ("PrivateKeyEntry", "trustedCertEntry", "SecretKeyEntry")):
                    aliases.append(line.split(",")[0].strip())
            return aliases

        results = []
        for keystore in keystores:
            entry = {"keystore": str(keystore.relative_to(work)), "aliases": []}
            for storepass in candidates:
                aliases = keytool_aliases(keystore, storepass)
                if not aliases:
                    continue
                entry["opened_with_candidate"] = True
                for alias in aliases:
                    digest, error = certificate_digest(keystore, alias, storepass)
                    row = {"alias": alias, "certificate_sha256": digest, "keytool_error": error}
                    if digest and args.unsigned_apk and pathlib.Path(args.unsigned_apk).is_file():
                        signed_digest, sign_error = sign_and_verify(keystore, alias, storepass,
                                                                    args.unsigned_apk, work)
                        row["signed_apk_signer"] = signed_digest
                        row["sign_error"] = sign_error
                    entry["aliases"].append(row)
                break
            results.append(entry)
        receipt["recovered"] = results

        checks = []
        for entry in results:
            for row in entry["aliases"]:
                alias = row["alias"]
                digest = row.get("certificate_sha256")
                if alias in expected:
                    checks.append({
                        "alias": alias,
                        "expected": expected[alias],
                        "actual": digest,
                        "matches": digest == expected[alias],
                    })
                if row.get("signed_apk_signer"):
                    checks.append({
                        "alias": alias,
                        "signed_apk_matches_certificate": row["signed_apk_signer"] == digest,
                    })
        receipt["checks"] = checks
        receipt["verified"] = bool(checks) and all(
            check.get("matches", True) and check.get("signed_apk_matches_certificate", True)
            for check in checks)
    finally:
        shutil.rmtree(work, ignore_errors=True)
        receipt["temporary_key_material_removed"] = not work.exists()

    receipt["limitations"] = [
        "the drill proves the archive decrypts and the keys sign; it does not install anything",
        "keychain access still requires operator approval on this machine",
        "rotation, revocation and restore on a second device remain untested",
    ]
    pathlib.Path(args.output).write_text(json.dumps(receipt, ensure_ascii=False, indent=1) + "\n")
    print(json.dumps({"verified": receipt.get("verified"),
                      "keystores": receipt.get("keystores_found"),
                      "checks": receipt.get("checks")}, ensure_ascii=False))
    return 0 if receipt.get("verified") else 1


if __name__ == "__main__":
    sys.exit(main())
