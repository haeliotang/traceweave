#!/usr/bin/env python3
"""
Traceform Master End-to-End Pipeline CLI.

Executes the complete scientific closed loop:
  Stage 1: Corpus Freeze + Audit v1
  Stage 2: Multimodal Representation Freeze v1
  Stage 3: Null / Surrogate Baseline Engine v1
  Stage 4: Trajectory Structure Discovery v1
  Stage 5: Out-of-Sample Temporal Stability Verification v1
  Stage 6: Candidate Semantic Physical Reverse Projection v1
"""

from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys
import time

REPO_ROOT = Path(__file__).resolve().parent.parent


STAGES = [
    ("Stage 1: Corpus Audit & Freeze", [sys.executable, str(REPO_ROOT / "scripts" / "audit_trajectory_corpus.py")]),
    ("Stage 2: Representation Freeze", [sys.executable, str(REPO_ROOT / "scripts" / "freeze_representation.py")]),
    ("Stage 3: Null / Surrogate Baselines", [sys.executable, str(REPO_ROOT / "scripts" / "run_null_baselines.py")]),
    ("Stage 4: Trajectory Structure Discovery", [sys.executable, str(REPO_ROOT / "scripts" / "run_structure_discovery.py")]),
    ("Stage 5: Temporal Stability Verification", [sys.executable, str(REPO_ROOT / "scripts" / "verify_temporal_stability.py")]),
    ("Stage 6: Candidate Semantic Projection", [sys.executable, str(REPO_ROOT / "scripts" / "project_candidate_semantics.py")]),
]


def run_unit_tests() -> bool:
    print("\n" + "=" * 70)
    print(" Running Unit Test Suite...")
    print("=" * 70)
    res = subprocess.run([sys.executable, "-m", "unittest", "discover", "tests"], cwd=str(REPO_ROOT))
    return res.returncode == 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Traceform Scientific Pipeline Runner")
    parser.add_argument("--all", action="store_true", help="Run all 6 pipeline stages sequentially.")
    parser.add_argument("--stage", type=int, choices=range(1, 7), help="Run a specific stage (1 to 6).")
    parser.add_argument("--dry-run", action="store_true", help="Validate test suite and artifact manifests.")
    args = parser.parse_args()

    print("=" * 70)
    print(" Traceform: Continuous Trajectory Structure Discovery Pipeline")
    print(" Scientific Mission: Zero Predefined Semantics -> Latent Structure Discovery")
    print("=" * 70)

    if args.dry_run:
        print("[*] Mode: DRY-RUN (Validating tests and environment)")
        if not run_unit_tests():
            print("\n[-] Unit tests failed!")
            sys.exit(1)
        print("\n[+] All unit tests passed! Environment and code are healthy.")
        sys.exit(0)

    # Determine stages to execute
    if args.stage:
        selected_stages = [STAGES[args.stage - 1]]
    else:
        # Default or --all: run all stages
        selected_stages = STAGES

    # First run unit tests
    if not run_unit_tests():
        print("\n[-] Unit tests failed! Aborting pipeline execution.")
        sys.exit(1)

    t_start = time.time()
    results = []

    for name, cmd in selected_stages:
        print("\n" + "=" * 70)
        print(f" Executing: {name}")
        print("=" * 70)
        t0 = time.time()
        res = subprocess.run(cmd, cwd=str(REPO_ROOT))
        elapsed = time.time() - t0

        status = "SUCCESS" if res.returncode == 0 else "FAILED"
        results.append((name, status, elapsed))

        if res.returncode != 0:
            print(f"\n[-] Pipeline step '{name}' failed with code {res.returncode}. Aborting.")
            sys.exit(res.returncode)

    total_time = time.time() - t_start

    print("\n" + "=" * 70)
    print(" Pipeline Execution Summary")
    print("=" * 70)
    for name, status, elapsed in results:
        print(f"  - {name:<45} : [{status}] in {elapsed:.2f}s")
    print("-" * 70)
    print(f"  Total Pipeline Wall Time: {total_time:.2f}s")
    print("=" * 70)
    print("\n[+] Full scientific pipeline closed loop verified successfully.")


if __name__ == "__main__":
    main()
