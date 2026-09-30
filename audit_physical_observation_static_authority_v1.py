#!/usr/bin/env python3
"""Static Authority Audit for Physical Observation Stack v1."""

import ast
from pathlib import Path
import subprocess
import sys

from pixel_physical import config

PHYSICAL_DIR = Path("/root/pixel_physical")
PHYSICALCTL_PATH = Path("/root/physicalctl.py")

FORBIDDEN_IMPORTS = {
    "pixel_production_runtime",
    "pixel_supervision_v1",
    "pixelctl",
    "pixel_production_orchestrator",
    "pixel_production_integration",
    "pixel_controlled_capture",
    "android_bridge",
    "pixel_desktop_controller_v2",
    "pixel_desktop_controller_v3",
    "supervisionctl",
}

FORBIDDEN_STRINGS = [
    "intent.json",
    "supervision.lock",
    "termux-job-scheduler",
    "7701",
    "user is sleeping",
    "user is commuting",
    "user is working",
    "user is at home",
    "user is meeting",
]


def check_ast_imports(tree: ast.AST, file_path: Path) -> list:
    violations = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                root_pkg = alias.name.split(".")[0]
                if root_pkg in FORBIDDEN_IMPORTS:
                    violations.append(f"{file_path.name}: Forbidden import '{alias.name}'")
        elif isinstance(node, ast.ImportFrom):
            if node.module:
                root_pkg = node.module.split(".")[0]
                if root_pkg in FORBIDDEN_IMPORTS:
                    violations.append(f"{file_path.name}: Forbidden from-import '{node.module}'")
    return violations


def check_forbidden_calls(tree: ast.AST, file_path: Path) -> list:
    violations = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            # Check for eval / exec
            if isinstance(node.func, ast.Name) and node.func.id in ("eval", "exec"):
                violations.append(f"{file_path.name}: Forbidden use of {node.func.id}()")
            # Check for subprocess shell=True
            for kw in node.keywords:
                if kw.arg == "shell" and isinstance(kw.value, ast.Constant) and kw.value.value is True:
                    violations.append(f"{file_path.name}: Forbidden shell=True call")
    return violations


def main() -> int:
    print("========================================================================")
    print("PIXEL SENSORIUM PHYSICAL OBSERVATION v1 STATIC AUTHORITY AUDIT")
    print("========================================================================")

    passed_checks = 0
    total_checks = 0

    def record(desc: str, cond: bool, detail: str = ""):
        nonlocal passed_checks, total_checks
        total_checks += 1
        if cond:
            print(f"[{total_checks:02d}] PASS: {desc}")
            passed_checks += 1
        else:
            print(f"[{total_checks:02d}] FAIL: {desc} - {detail}")

    all_py_files = list(PHYSICAL_DIR.glob("*.py")) + [PHYSICALCTL_PATH]

    # Check 1: Forbidden imports
    all_import_violations = []
    for py_file in all_py_files:
        try:
            tree = ast.parse(py_file.read_text(encoding="utf-8"))
            all_import_violations.extend(check_ast_imports(tree, py_file))
        except Exception as e:
            all_import_violations.append(f"{py_file.name}: AST parse failure: {e}")
    record("Zero imports of frozen runtime/supervision/lineage modules", len(all_import_violations) == 0, str(all_import_violations))

    # Check 2: Dynamic evaluation and shell=True
    all_call_violations = []
    for py_file in all_py_files:
        try:
            tree = ast.parse(py_file.read_text(encoding="utf-8"))
            all_call_violations.extend(check_forbidden_calls(tree, py_file))
        except Exception as e:
            all_call_violations.append(f"{py_file.name}: AST parse failure: {e}")
    record("Zero use of eval(), exec(), or shell=True", len(all_call_violations) == 0, str(all_call_violations))

    # Check 3: Forbidden strings (intent.json, supervision.lock, 7701, high-level life semantic labels)
    all_string_violations = []
    for py_file in all_py_files:
        content = py_file.read_text(encoding="utf-8").lower()
        for forbidden in FORBIDDEN_STRINGS:
            if forbidden.lower() in content:
                all_string_violations.append(f"{py_file.name}: Found forbidden token '{forbidden}'")
    record("Zero lower-layer authority strings or high-level life semantics", len(all_string_violations) == 0, str(all_string_violations))

    # Check 4: Substrate invariance
    res = subprocess.run(
        ["/root/verify_android_desktop_integration_v3_frozen.sh"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    record("Substrate frozen authority remains 100% invariant", res.returncode == 0)

    # Check 5: Protocol strings defined
    record("Protocol string conforms to pixel-sensorium.physical-observation.v1", config.PROTOCOL == "pixel-sensorium.physical-observation.v1")

    print("========================================================================")
    print(f"STATIC AUTHORITY AUDIT VERDICT: {passed_checks}/{total_checks} PASSED")
    print("========================================================================")

    if passed_checks == total_checks:
        print("STATIC_AUTHORITY_AUDIT=PASS")
        return 0
    else:
        print("STATIC_AUTHORITY_AUDIT=FAIL")
        return 1


if __name__ == "__main__":
    sys.exit(main())
