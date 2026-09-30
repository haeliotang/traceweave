#!/usr/bin/env python3
"""Static Authority Audit for Physical Runner Supervision v1."""

import ast
from pathlib import Path
import subprocess
import sys

TARGET_FILES = [
    Path("/root/pixel_physical_supervision_v1.py"),
    Path("/root/physicalsupervisionctl.py"),
]


def audit_static_authority() -> int:
    print("========================================================================")
    print("PIXEL SENSORIUM PHYSICAL RUNNER SUPERVISION v1 STATIC AUTHORITY AUDIT")
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

    # Check 1: Zero forbidden imports
    FORBIDDEN_MODULES = {
        "pixel_production_runtime",
        "pixel_production_orchestrator",
        "pixel_production_integration",
        "pixel_deployment_control_adapter",
        "pixel_supervision_v1",
        "android_bridge",
        "pixel_controlled_capture",
    }

    found_forbidden = []
    for f in TARGET_FILES:
        tree = ast.parse(f.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    if alias.name in FORBIDDEN_MODULES:
                        found_forbidden.append((f.name, alias.name))
            elif isinstance(node, ast.ImportFrom):
                if node.module in FORBIDDEN_MODULES:
                    found_forbidden.append((f.name, node.module))

    record("Zero imports of frozen runtime/supervision/lineage modules", len(found_forbidden) == 0, f"Found: {found_forbidden}")

    # Check 2: Zero eval, exec, shell=True
    dangerous_calls = []
    for f in TARGET_FILES:
        tree = ast.parse(f.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Call):
                if isinstance(node.func, ast.Name) and node.func.id in ("eval", "exec"):
                    dangerous_calls.append((f.name, node.func.id))
                for kw in node.keywords:
                    if kw.arg == "shell" and isinstance(kw.value, ast.Constant) and kw.value.value is True:
                        dangerous_calls.append((f.name, "shell=True"))

    record("Zero use of eval(), exec(), or shell=True", len(dangerous_calls) == 0, f"Found: {dangerous_calls}")

    # Check 3: Conformance of protocol string
    import pixel_physical_supervision_v1 as sup
    record("Protocol string conforms to pixel-sensorium.physical-supervision-intent.v1", sup.SUPERVISION_PROTOCOL == "pixel-sensorium.physical-supervision-intent.v1")

    # Check 4: Zero lower-layer authority strings or high-level life semantics
    src = Path("/root/pixel_physical_supervision_v1.py").read_text(encoding="utf-8")
    lower_layer_violations = []
    if "DEPLOYMENT_SUPERVISION_AUTHORITY=1" in src:
        lower_layer_violations.append("DEPLOYMENT_SUPERVISION_AUTHORITY=1")
    if "FROZEN_RUNTIME_AUTHORITY=1" in src:
        lower_layer_violations.append("FROZEN_RUNTIME_AUTHORITY=1")
    if "HIGH_LEVEL_LIFE_SEMANTIC_AUTHORITY=1" in src:
        lower_layer_violations.append("HIGH_LEVEL_LIFE_SEMANTIC_AUTHORITY=1")

    record("Zero lower-layer authority claims or life semantics", len(lower_layer_violations) == 0, f"Violations: {lower_layer_violations}")

    # Check 5: Upstream substrate verification
    res = subprocess.run(["/root/verify_physical_observation_stack_v1_frozen.sh"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    record("Substrate frozen authority remains 100% invariant", res.returncode == 0)

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
    sys.exit(audit_static_authority())
