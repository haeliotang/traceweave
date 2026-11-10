#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Compare a GitHub release's assets against a locally packaged release directory."""
import argparse
import json
import pathlib
import subprocess
import sys


def parse_sums(path):
    sums = {}
    for line in pathlib.Path(path).read_text().splitlines():
        parts = line.split()
        if len(parts) == 2:
            sums[parts[1]] = parts[0]
    return sums


# The checksum file cannot contain its own digest, so it is expected but not compared.
SELF_REFERENTIAL = {"SHA256SUMS"}


def compare(local_sums, remote_assets):
    """remote_assets: list of {"name": str, "digest": "sha256:..."}."""
    remote = {}
    for asset in remote_assets:
        digest = asset.get("digest") or ""
        remote[asset["name"]] = digest.split(":", 1)[1] if digest.startswith("sha256:") else None
    missing = sorted(name for name in local_sums if name not in remote)
    mismatched = sorted(name for name, digest in local_sums.items()
                        if remote.get(name) is not None and remote[name] != digest)
    unknown_digest = sorted(name for name in local_sums if remote.get(name) is None)
    extra = sorted(name for name in remote
                   if name not in local_sums and name not in SELF_REFERENTIAL)
    return {"missing": missing, "mismatched": mismatched,
            "unknown_digest": unknown_digest, "extra": extra,
            # An extra asset is a failure: someone can add a second APK to a release and the
            # old check would still call it verified.
            "ok": not missing and not mismatched and not unknown_digest and not extra}


def check_identity(report, tag, payload, notes_path):
    """The tag must match and the release body must start with the packaged notes."""
    problems = []
    if payload.get("tagName") != tag:
        problems.append("tag mismatch: %s" % payload.get("tagName"))
    if payload.get("isDraft"):
        problems.append("release is still a draft")
    if notes_path and pathlib.Path(notes_path).is_file():
        notes = pathlib.Path(notes_path).read_text().strip()
        body = (payload.get("body") or "").strip()
        if "（未填写）" in notes:
            # The v0.1.0 asset shipped with a placeholder; its body was corrected afterwards,
            # so an exact comparison is impossible and is recorded instead of failed.
            report["legacy_placeholder_notes"] = True
        # Published releases may append the development-key side-load notice after the
        # packaged notes; the body must still start with the packaged text verbatim.
        elif not body.startswith(notes.rstrip()):
            problems.append("release body differs from RELEASE_NOTES.md")
    report["identity_problems"] = problems
    if problems:
        report["ok"] = False
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--release-dir", required=True)
    parser.add_argument("--gh", default="gh")
    parser.add_argument("--output")
    args = parser.parse_args()

    sums = parse_sums(pathlib.Path(args.release_dir) / "SHA256SUMS")
    result = subprocess.run([args.gh, "release", "view", args.tag,
                            "--json", "assets,isDraft,tagName,body"],
                            capture_output=True, text=True, check=False)
    if result.returncode != 0:
        print(result.stderr.strip(), file=sys.stderr)
        sys.exit(2)
    payload = json.loads(result.stdout)
    report = compare(sums, payload["assets"])
    report["tag"] = payload.get("tagName")
    report["draft"] = payload.get("isDraft")
    notes = pathlib.Path(args.release_dir) / "RELEASE_NOTES.md"
    check_identity(report, args.tag, payload, notes if notes.is_file() else None)
    if args.output:
        pathlib.Path(args.output).write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: report[k] for k in ("ok", "missing", "mismatched", "extra", "draft",
        "identity_problems")}, ensure_ascii=False))
    sys.exit(0 if report["ok"] else 1)


if __name__ == "__main__":
    main()
