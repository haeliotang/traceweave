#!/usr/bin/env python3
"""Assemble a GitHub Release directory from a preflight output."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preflight-dir", required=True, help="Directory produced by release_preflight_android.py")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--version", required=True, help="Release version, e.g. 0.6.14")
    parser.add_argument("--changes-from", help="Git ref; commits after it become the change list")
    parser.add_argument("--changes-limit", type=int, default=40)
    args = parser.parse_args()

    source = pathlib.Path(args.preflight_dir)
    target = pathlib.Path(args.output_dir)
    target.mkdir(parents=True, exist_ok=True)

    preflight = json.loads((source / "preflight.json").read_text())
    if not preflight.get("ok"):
        print("preflight did not pass; refusing to package", file=sys.stderr)
        sys.exit(2)

    apk_source = source / "agent-os.apk"
    apk_target = target / f"traceform-agent-os-{args.version}.apk"
    shutil.copy2(apk_source, apk_target)
    for name, destination in [
        ("build_report.json", "build_report.json"),
        ("release_verification.json", "release_verification.json"),
        ("release_notes.md", "RELEASE_NOTES.md"),
    ]:
        origin = source / name
        if origin.exists():
            shutil.copy2(origin, target / destination)

    if args.changes_from:
        log = subprocess.run(["git", "log", "--oneline", f"{args.changes_from}..HEAD"],
                             cwd=pathlib.Path(__file__).resolve().parents[2],
                             capture_output=True, text=True, check=False)
        lines = [line for line in log.stdout.splitlines() if line.strip()][: args.changes_limit]
        if lines:
            notes = target / "RELEASE_NOTES.md"
            text = notes.read_text().replace("- （未填写）", "\n".join(f"- {line.split(' ', 1)[-1]}" for line in lines))
            notes.write_text(text)

    entries = []
    for path in sorted(target.iterdir()):
        if path.name == "SHA256SUMS":
            continue
        entries.append(f"{sha256(path)}  {path.name}")
    (target / "SHA256SUMS").write_text("\n".join(entries) + "\n")

    manifest = {
        "version": args.version,
        "apk": apk_target.name,
        "files": {path.name: sha256(path) for path in sorted(target.iterdir()) if path.name != "SHA256SUMS"},
        "preflight_ok": True,
    }
    (target / "release_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"release_dir": str(target), "files": list(manifest["files"])}))


if __name__ == "__main__":
    main()
