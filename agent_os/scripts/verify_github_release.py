#!/usr/bin/env python3
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
    extra = sorted(name for name in remote if name not in local_sums)
    return {"missing": missing, "mismatched": mismatched,
            "unknown_digest": unknown_digest, "extra": extra,
            "ok": not missing and not mismatched and not unknown_digest}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--release-dir", required=True)
    parser.add_argument("--gh", default="gh")
    parser.add_argument("--output")
    args = parser.parse_args()

    sums = parse_sums(pathlib.Path(args.release_dir) / "SHA256SUMS")
    result = subprocess.run([args.gh, "release", "view", args.tag, "--json", "assets,isDraft,tagName"],
                            capture_output=True, text=True, check=False)
    if result.returncode != 0:
        print(result.stderr.strip(), file=sys.stderr)
        sys.exit(2)
    payload = json.loads(result.stdout)
    report = compare(sums, payload["assets"])
    report["tag"] = payload.get("tagName")
    report["draft"] = payload.get("isDraft")
    if args.output:
        pathlib.Path(args.output).write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: report[k] for k in ("ok", "missing", "mismatched", "draft")}))
    sys.exit(0 if report["ok"] else 1)


if __name__ == "__main__":
    main()
