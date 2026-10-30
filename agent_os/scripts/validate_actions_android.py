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
    "SENSOR_CONTEXT": ("WorkflowValidationActivity", "SENSOR_CONTEXT", "AgentOsWorkflowValidation"),
    "SENSOR_LIVE_SAMPLES": ("WorkflowValidationActivity", "SENSOR_LIVE_SAMPLES", "AgentOsWorkflowValidation"),
    "SENSOR_WINDOW": ("WorkflowValidationActivity", "SENSOR_WINDOW", "AgentOsWorkflowValidation"),
    "MEMORY_LIFECYCLE": ("WorkflowValidationActivity", "MEMORY_LIFECYCLE", "AgentOsWorkflowValidation"),
    "DOCUMENT_CALENDAR_CHAIN": ("WorkflowValidationActivity", "DOCUMENT_CALENDAR_CHAIN", "AgentOsWorkflowValidation"),
    "DOCUMENT_SHARE_CHAIN": ("WorkflowValidationActivity", "DOCUMENT_SHARE_CHAIN", "AgentOsWorkflowValidation"),
    "SEED_TRACEFORM_APPROVAL_EN": ("WorkflowValidationActivity", "SEED_TRACEFORM_APPROVAL_EN", "AgentOsWorkflowValidation"),
    "SEED_TRACEFORM_RESULT_EN": ("WorkflowValidationActivity", "SEED_TRACEFORM_RESULT_EN", "AgentOsWorkflowValidation"),
    "MEMORY_BRANCH_SEED": ("WorkflowValidationActivity", "MEMORY_BRANCH_SEED", "AgentOsWorkflowValidation"),
    "MEMORY_BRANCH_TARGET_EDIT": ("WorkflowValidationActivity", "MEMORY_BRANCH_TARGET_EDIT", "AgentOsWorkflowValidation"),
    "MEMORY_BRANCH_SOURCE_EDIT": ("WorkflowValidationActivity", "MEMORY_BRANCH_SOURCE_EDIT", "AgentOsWorkflowValidation"),
    "MEMORY_BRANCH_TARGET_RESOLVE": ("WorkflowValidationActivity", "MEMORY_BRANCH_TARGET_RESOLVE", "AgentOsWorkflowValidation"),
    "MEMORY_BRANCH_SOURCE_RESOLVE": ("WorkflowValidationActivity", "MEMORY_BRANCH_SOURCE_RESOLVE", "AgentOsWorkflowValidation"),
    "SHARE_ESCAPED_CONTENT": ("WorkflowValidationActivity", "SHARE_ESCAPED_CONTENT", "AgentOsWorkflowValidation"),
    "VERIFY_SHARE_RESTART": ("WorkflowValidationActivity", "VERIFY_SHARE_RESTART", "AgentOsWorkflowValidation"),
    "EXPORT_SHARE_MIGRATION": ("WorkflowValidationActivity", "EXPORT_SHARE_MIGRATION", "AgentOsWorkflowValidation"),
    "IMPORT_SHARE_MIGRATION": ("WorkflowValidationActivity", "IMPORT_SHARE_MIGRATION", "AgentOsWorkflowValidation"),
    "SEED_SHARE_UI": ("WorkflowValidationActivity", "SEED_SHARE_UI", "AgentOsWorkflowValidation"),
    "VERIFY_CHAIN_RESTART": ("WorkflowValidationActivity", "VERIFY_CHAIN_RESTART", "AgentOsWorkflowValidation"),
    "SEED_CALENDAR_RETRY_UI": ("WorkflowValidationActivity", "SEED_CALENDAR_RETRY_UI", "AgentOsWorkflowValidation"),
    "VERIFY_CALENDAR_RETRY_UI": ("WorkflowValidationActivity", "VERIFY_CALENDAR_RETRY_UI", "AgentOsWorkflowValidation"),
    "CLOUD_PLAN_GUARD": ("WorkflowValidationActivity", "CLOUD_PLAN_GUARD", "AgentOsWorkflowValidation"),
    "LOCAL_ENGINE_LABELS": ("WorkflowValidationActivity", "LOCAL_ENGINE_LABELS", "AgentOsWorkflowValidation"),
    "LOCAL_MODEL_DRAFT_BOUNDARY": ("WorkflowValidationActivity", "LOCAL_MODEL_DRAFT_BOUNDARY", "AgentOsWorkflowValidation"),
    "SEED_LOCAL_MODEL_UI": ("WorkflowValidationActivity", "SEED_LOCAL_MODEL_UI", "AgentOsWorkflowValidation"),
    "SEED_LOCAL_MODEL_FAILURE_UI": ("WorkflowValidationActivity", "SEED_LOCAL_MODEL_FAILURE_UI", "AgentOsWorkflowValidation"),
    "SEED_LOCAL_MODEL_WAIT_UI": ("WorkflowValidationActivity", "SEED_LOCAL_MODEL_WAIT_UI", "AgentOsWorkflowValidation"),
    "VERIFY_LOCAL_MODEL_UI": ("WorkflowValidationActivity", "VERIFY_LOCAL_MODEL_UI", "AgentOsWorkflowValidation"),
    "VERIFY_LOCAL_MODEL_CANCEL_UI": ("WorkflowValidationActivity", "VERIFY_LOCAL_MODEL_CANCEL_UI", "AgentOsWorkflowValidation"),
    "OPEN_LOCAL_MODEL_MANAGER": ("WorkflowValidationActivity", "OPEN_LOCAL_MODEL_MANAGER", "AgentOsWorkflowValidation"),
    "VERIFY_LOCAL_MODEL_MANAGER": ("WorkflowValidationActivity", "VERIFY_LOCAL_MODEL_MANAGER", "AgentOsWorkflowValidation"),
    "LOCAL_MODEL_DOCUMENT_SEED": ("LocalModelDocumentProbe", "SEED", "AgentOsModelDocument"),
    "LOCAL_MODEL_DOCUMENT_VERIFY": ("LocalModelDocumentProbe", "VERIFY", "AgentOsModelDocument"),
    "LOCAL_MODEL_FILES": ("LocalModelFilesValidationActivity", "SYNTHETIC", "AgentOsModelFiles"),
    "DEVICE_IDENTITY": ("DeviceIdentityValidationActivity", "SYNTHETIC", "AgentOsDeviceIdentity"),
    "LOCAL_MODEL_FILES_PUBLIC": ("LocalModelFilesValidationActivity", "PUBLIC_MODEL", "AgentOsModelFiles"),
    "SCOPED_SUMMARY_CONTRACT": ("WorkflowValidationActivity", "SCOPED_SUMMARY_CONTRACT", "AgentOsWorkflowValidation"),
    "EXPORT_LIFECYCLE": ("WorkflowValidationActivity", "EXPORT_LIFECYCLE", "AgentOsWorkflowValidation"),
    "IMPORT_LIFECYCLE": ("WorkflowValidationActivity", "IMPORT_LIFECYCLE", "AgentOsWorkflowValidation"),
    "WORKFLOW_RUNS": ("WorkflowValidationActivity", "WORKFLOW_RUNS", "AgentOsWorkflowValidation"),
    "MEMORY_CHOICES": ("WorkflowValidationActivity", "MEMORY_CHOICES", "AgentOsWorkflowValidation"),
    "TASK_CENTER": ("WorkflowValidationActivity", "TASK_CENTER", "AgentOsWorkflowValidation"),
    "WORKFLOW_GUARD": ("WorkflowValidationActivity", "WORKFLOW_GUARD", "AgentOsWorkflowValidation"),
    "RUN_SUITE": ("ActionFlowValidationActivity", "RUN_SUITE", "AgentOsActionsValidation"),
    "VERIFY_RESTART": ("ActionFlowValidationActivity", "VERIFY_RESTART", "AgentOsActionsValidation"),
    "VERIFY_UPGRADE": ("ActionFlowValidationActivity", "VERIFY_UPGRADE", "AgentOsActionsValidation"),
    "VERIFY_UI": ("ActionFlowValidationActivity", "VERIFY_UI", "AgentOsActionsValidation"),
}
for name in ("RUN_DOCUMENTS", "VERIFY_DOCUMENT_RESTART", "STOP_TRACKING", "ARM_DOCUMENT_CRASH", "BASELINE_CLOUD",
             "VERIFY_PRIVACY", "VERIFY_MEMORY_REVIEW", "SEED_DOCUMENT_UI"):
    CASES[name] = ("DocumentFlowValidationActivity", name, "AgentOsDocumentsValidation")
for name in ("EXPORT_MIGRATION", "IMPORT_MIGRATION", "IMPORT_MIGRATION_WITH_CLOUD_FIXTURE", "ARCHIVE_DELETE_STALE", "SEED_ARCHIVE_DELETE_UI", "VERIFY_ARCHIVE_DELETE_UI", "SEED_ARCHIVE_DELETE_MULTI_UI", "VERIFY_ARCHIVE_DELETE_MULTI_UI", "SEED_ARCHIVE_DELETE_UI_STALE", "VERIFY_ARCHIVE_DELETE_UI_STALE", "SEED_REBOOT_STATE", "VERIFY_REBOOT_STATE", "MERGE_CAUSAL", "MERGE_CONCURRENT", "MERGE_TOMBSTONE", "MERGE_REPLAY_LIMIT", "MERGE_RESOLVE", "VERIFY_ARCHIVE_DELETE_UI_RETRY", "SEED_EXPORT_UI", "VERIFY_EXPORT_UI", "REQUEST_NOTIFICATION_UI", "VERIFY_NOTIFICATION_UI", "VERIFY_NOTIFICATION_DENIED", "REVIEW_ARCHIVE_CONFLICTS", "SEED_ARCHIVE_REVIEW_UI", "VERIFY_ARCHIVE_REVIEW_UI", "VERIFY_DOCUMENT_UI", "DUPLICATE_RECEIPT", "SEED_CLOUD_UI",
             "VERIFY_CLOUD_UI", "VERIFY_IMPORTED_READ_ONLY"):
    CASES[name] = ("DocumentFlowValidationActivity", name, "AgentOsDocumentsValidation")
for name in ("VERIFY_REVIEW_UI", "INVALID_DRAFT", "SEED_REVIEW_UI", "VERIFY_REVOKED_READ", "MEMORY_BOUNDARY", "SNAPSHOT_BOUNDARY"):
    CASES[name] = ("DocumentFlowValidationActivity", name, "AgentOsDocumentsValidation")
for name in ("MODEL_DOCUMENT", "MODEL_DOCUMENT_FAILURES", "MODEL_CANCEL_RACE"):
    CASES[name] = ("DocumentFlowValidationActivity", name, "AgentOsDocumentsValidation")
for name in ("SEED_WORKFLOW_UI", "OPEN_WORKFLOW_UI", "VERIFY_WORKFLOW_UI", "SEED_CENTER_UI", "OPEN_CENTER_UI", "VERIFY_CENTER_UI", "VERIFY_IMPORTED_WORKFLOWS"):
    CASES[name] = ("WorkflowValidationActivity", name, "AgentOsWorkflowValidation")
for name in ("VERIFY_SENSOR_OPT_IN", "VERIFY_DYNAMIC_EXECUTOR", "VERIFY_EXECUTOR_LIFECYCLE", "VERIFY_MEMORY_PROVENANCE",
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
    if args.package not in ("org.traceform.agentos.actionsvalidation", "org.traceform.agentos.documentsvalidation"):
        parser.error("Only isolated validation packages are supported")
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
    args.output.write_text(json.dumps({"package": package, "synthetic_only": all(item["case"] not in ("SENSOR_CONTEXT", "SENSOR_LIVE_SAMPLES") for item in results),
        "recorded_at": datetime.now(timezone.utc).isoformat(), "cases": results}, indent=2) + "\n")
    raise SystemExit(0 if all(item["passed"] for item in results) else 1)


if __name__ == "__main__":
    main()
