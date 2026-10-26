#!/usr/bin/env python3
"""Run synthetic action checks against the installed isolated Android test package.

RUN_SUITE and legacy DeviceValidationActivity cases reset only the isolated test DB.
Run VERIFY_UI before RUN_SUITE when retaining manually exercised UI fixtures.
"""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import time


CASES = {
    "RUN_SUITE": ("ActionFlowValidationActivity", "RUN_SUITE", "AgentOsActionsValidation"),
    "VERIFY_RESTART": ("ActionFlowValidationActivity", "VERIFY_RESTART", "AgentOsActionsValidation"),
    "VERIFY_UPGRADE": ("ActionFlowValidationActivity", "VERIFY_UPGRADE", "AgentOsActionsValidation"),
    "VERIFY_UI": ("ActionFlowValidationActivity", "VERIFY_UI", "AgentOsActionsValidation"),
}
for name in ("RUN_DOCUMENTS", "VERIFY_DOCUMENT_RESTART", "STOP_TRACKING", "ARM_DOCUMENT_CRASH", "BASELINE_CLOUD",
             "VERIFY_PRIVACY", "VERIFY_MEMORY_REVIEW", "SEED_DOCUMENT_UI"):
    CASES[name] = ("DocumentFlowValidationActivity", name, "AgentOsDocumentsValidation")
for name in ("EXPORT_MIGRATION", "IMPORT_MIGRATION", "VERIFY_DOCUMENT_UI", "DUPLICATE_RECEIPT", "SEED_CLOUD_UI",
             "VERIFY_CLOUD_UI", "VERIFY_IMPORTED_READ_ONLY"):
    CASES[name] = ("DocumentFlowValidationActivity", name, "AgentOsDocumentsValidation")
for name in ("VERIFY_REVIEW_UI", "INVALID_DRAFT", "SEED_REVIEW_UI", "VERIFY_REVOKED_READ", "MEMORY_BOUNDARY", "SNAPSHOT_BOUNDARY"):
    CASES[name] = ("DocumentFlowValidationActivity", name, "AgentOsDocumentsValidation")
for name in ("MODEL_DOCUMENT", "MODEL_DOCUMENT_FAILURES", "MODEL_CANCEL_RACE"):
    CASES[name] = ("DocumentFlowValidationActivity", name, "AgentOsDocumentsValidation")
for name in ("VERIFY_DYNAMIC_EXECUTOR", "VERIFY_EXECUTOR_LIFECYCLE", "VERIFY_MEMORY_PROVENANCE",
             "VERIFY_MEMORY_CONSOLIDATION_AND_UI", "VERIFY_DATABASE_MIGRATION", "VERIFY_HYBRID_CLOUD_AND_REDACTION"):
    CASES[name] = ("DeviceValidationActivity", "org.traceform.agentos.validation." + name, "AgentOsValidation")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="org.traceform.agentos.actionsvalidation")
    parser.add_argument("--case", choices=CASES, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]
    package = args.package

    def run(*arguments):
        return subprocess.run(adb + list(arguments), capture_output=True, text=True, check=True, timeout=30).stdout

    results = []
    for name in args.case:
        component, action, tag = CASES[name]
        if name == "ARM_DOCUMENT_CRASH":
            run("shell", "am", "start", "-S", "-f", "0x10008000", "-n",
                package + "/org.traceform.agentos." + component, "-a", action)
            time.sleep(2)
            process = subprocess.run(adb + ["shell", "pidof", package], capture_output=True, text=True, timeout=10)
            result = {"case": name, "passed": process.returncode != 0,
                      "result": "Process terminated; VERIFY_DOCUMENT_RESTART must verify the existing file without a second write"}
            results.append(result)
            print(name + " => " + str(result["passed"]), flush=True)
            if not result["passed"]:
                break
            continue
        run("shell", "am", "start", "-W", "-S", "-f", "0x10008000", "-n",
            package + "/org.traceform.agentos." + component, "-a", action)
        pid = run("shell", "pidof", package).strip().split()[0]
        deadline = time.monotonic() + 60
        line = "TIMEOUT"
        while time.monotonic() < deadline:
            logs = run("logcat", "-d", "--pid=" + pid, "-s", tag + ":I", "*:S")
            finals = [entry for entry in logs.splitlines() if "PASS" in entry or "FAIL" in entry]
            if finals:
                line = finals[-1]
                break
            time.sleep(1)
        result = {"case": name, "pid": int(pid), "passed": "PASS" in line, "result": line}
        results.append(result)
        print(name + " => " + line, flush=True)
        if not result["passed"]:
            break
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"package": package, "synthetic_only": True,
        "recorded_at": datetime.now(timezone.utc).isoformat(), "cases": results}, indent=2) + "\n")
    raise SystemExit(0 if all(item["passed"] for item in results) else 1)


if __name__ == "__main__":
    main()
