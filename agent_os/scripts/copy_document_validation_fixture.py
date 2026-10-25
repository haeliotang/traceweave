#!/usr/bin/env python3
"""Copy the synthetic EXPORT_MIGRATION fixture between installed debug test packages."""

import argparse
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]
    source = "org.traceform.agentos.actionsvalidation"
    target = "org.traceform.agentos.documentsvalidation"
    subprocess.run(adb + ["shell", "run-as", target, "mkdir", "-p", "files"], check=True)
    for name in ("synthetic-migration.taos", "migration-ids.json"):
        payload = subprocess.check_output(adb + ["exec-out", "run-as", source, "cat", "files/" + name])
        command = shlex.join(["run-as", target, "sh", "-c", "cat > files/" + name])
        subprocess.run(adb + ["shell", command], input=payload, check=True)
    print("Copied encrypted synthetic fixture and synthetic record IDs to the target debug package")


if __name__ == "__main__":
    main()
