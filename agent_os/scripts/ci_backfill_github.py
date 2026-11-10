#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Re-run hosted CI runs that failed while the account was blocked.

Lists failed runs since a date and re-runs them; dry-run by default so the list
can be reviewed first.
"""
import argparse
import json
import subprocess
import sys


def select_runs(runs, since, limit):
    """Filter to failed runs created on/after `since`, newest first."""
    picked = []
    for run in sorted(runs, key=lambda item: item.get("createdAt", ""), reverse=True):
        if run.get("conclusion") != "failure":
            continue
        if since and run.get("createdAt", "") < since:
            continue
        picked.append(run)
        if len(picked) >= limit:
            break
    return picked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="owner/name")
    parser.add_argument("--since", required=True, help="ISO date, e.g. 2026-10-30")
    parser.add_argument("--limit", type=int, default=20)
    parser.add_argument("--gh", default="gh")
    parser.add_argument("--apply", action="store_true", help="Actually re-run; default is a dry run")
    parser.add_argument("--output")
    args = parser.parse_args()

    listing = subprocess.run(
        [args.gh, "run", "list", "--repo", args.repo, "--limit", "100", "--json",
         "databaseId,conclusion,createdAt,displayTitle,headSha,status"],
        capture_output=True, text=True, check=False)
    if listing.returncode != 0:
        print(listing.stderr.strip(), file=sys.stderr)
        sys.exit(2)

    runs = json.loads(listing.stdout)
    chosen = select_runs(runs, args.since, args.limit)
    results = []
    for run in chosen:
        entry = {"id": run["databaseId"], "title": run.get("displayTitle"), "createdAt": run.get("createdAt")}
        if args.apply:
            rerun = subprocess.run([args.gh, "run", "rerun", str(run["databaseId"]), "--repo", args.repo],
                                   capture_output=True, text=True, check=False)
            entry["rerun"] = "ok" if rerun.returncode == 0 else rerun.stderr.strip()
        results.append(entry)

    report = {"repo": args.repo, "since": args.since, "applied": args.apply,
              "count": len(results), "runs": results}
    if args.output:
        open(args.output, "w").write(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"count": len(results), "applied": args.apply}))
    sys.exit(0)


if __name__ == "__main__":
    main()
