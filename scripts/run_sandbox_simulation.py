#!/usr/bin/env python3
"""
CLI Runner for On-Device Closed-Loop Trajectory Sandbox Simulation.

Executes forward Monte Carlo rollouts and counterfactual 'What-If' scenarios
from current real-time or historical physical trajectory states.
"""

from __future__ import annotations

import argparse
import datetime
import json
from pathlib import Path
import sys
import time

# Ensure traceform package is in path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from traceform.forecasting.sandbox_simulator import (
    SandboxSimulator,
    ScenarioIntervention,
)


def load_transition_model_or_records(repo_root: Path):
    """Attempts to load transition model from discovery spec or fallback to raw ledger."""
    spec_path = repo_root / "artifacts" / "structure_discovery_v1" / "structure_discovery_spec.json"
    if spec_path.exists():
        try:
            with open(spec_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            trans = data.get("transition_matrix")
            if trans:
                nodes = list(trans.keys())
                return "matrix", trans, nodes
        except Exception:
            pass

    # Fallback to records
    records_path = repo_root / "data" / "realtime_manifold_stream.jsonl"
    if not records_path.exists():
        records_path = repo_root / "data" / "realtime_state_stream.jsonl"
    
    if records_path.exists():
        records = []
        with open(records_path, "r", encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if s:
                    try:
                        records.append(json.loads(s))
                    except Exception:
                        continue
        if records:
            return "records", records, None

    return None, None, None


def main():
    parser = argparse.ArgumentParser(
        description="Traceform On-Device Forward Sandbox Simulator"
    )
    parser.add_argument(
        "--start-state",
        type=str,
        default=None,
        help="Initial state token (defaults to latest frame in realtime stream, or M05)",
    )
    parser.add_argument(
        "--horizon-steps",
        type=int,
        default=120,
        help="Simulation horizon in steps (default 120 = 1 hour at 30s/step)",
    )
    parser.add_argument(
        "--rollouts",
        type=int,
        default=500,
        help="Number of Monte Carlo rollouts per branch (default 500)",
    )
    parser.add_argument(
        "--intervention",
        type=str,
        choices=["NONE", "CLAMP_DWELL", "SUPPRESS_CHAOS", "FORCE_TARGET"],
        default="CLAMP_DWELL",
        help="Counterfactual scenario intervention to evaluate",
    )
    parser.add_argument(
        "--target-state",
        type=str,
        default="M01",
        help="Target state for FORCE_TARGET intervention",
    )
    parser.add_argument(
        "--output-file",
        type=str,
        default=None,
        help="Optional path to save simulation JSON artifact",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent

    print("=" * 70)
    print(" Traceform On-Device Trajectory Sandbox Simulator")
    print(" Forward Monte Carlo Rollouts & Counterfactual Branch Engine")
    print("=" * 70)

    # 1. Model Loading
    kind, data, nodes = load_transition_model_or_records(repo_root)
    simulator = SandboxSimulator(step_nominal_seconds=30.0)

    if kind == "matrix":
        print(f"[*] Loaded transition matrix with {len(nodes)} states from discovery spec.")
        simulator.fit_from_transition_matrix(data, vocab=nodes)
    elif kind == "records":
        print(f"[*] Fitted non-stationary model from {len(data)} trajectory stream frames.")
        simulator.fit_from_records(data)
    else:
        # Fallback synthetic demo model
        print("[!] No prior spec found. Seeding nominal ergodic transition model...")
        nominal_vocab = [f"M{i:02d}" for i in range(1, 17)]
        synthetic_trans = {
            u: {v: 0.85 if u == v else 0.15 / (len(nominal_vocab) - 1) for v in nominal_vocab}
            for u in nominal_vocab
        }
        simulator.fit_from_transition_matrix(synthetic_trans, vocab=nominal_vocab)

    # Determine start state
    start_state = args.start_state
    if not start_state:
        # Probe latest frame in realtime stream
        stream_path = repo_root / "data" / "realtime_manifold_stream.jsonl"
        if stream_path.exists():
            try:
                with open(stream_path, "r", encoding="utf-8") as f:
                    lines = [ln.strip() for ln in f if ln.strip()]
                    if lines:
                        last_frame = json.loads(lines[-1])
                        start_state = last_frame.get("state_token")
            except Exception:
                pass
    if not start_state:
        start_state = simulator.vocab[0] if simulator.vocab else "M01"

    horizon_hours = (args.horizon_steps * 30.0) / 3600.0
    print(f"[*] Configuration:")
    print(f"    - Starting State:       {start_state}")
    print(f"    - Horizon:              {args.horizon_steps} steps ({horizon_hours:.2f} hours)")
    print(f"    - Monte Carlo Runs:     {args.rollouts} rollouts/branch")
    print(f"    - Counterfactual Test:  {args.intervention}")

    # Build scenario intervention
    scen: Optional[ScenarioIntervention] = None
    if args.intervention == "CLAMP_DWELL":
        scen = ScenarioIntervention(
            action_type="CLAMP_DWELL",
            max_dwell_steps=30,  # 15 minutes max dwell
        )
    elif args.intervention == "SUPPRESS_CHAOS":
        # Suppress lowest ranked states (highest index)
        high_risk = set(simulator.vocab[-4:]) if len(simulator.vocab) >= 8 else set()
        scen = ScenarioIntervention(
            action_type="SUPPRESS_STATE",
            suppressed_states=high_risk,
        )
    elif args.intervention == "FORCE_TARGET":
        scen = ScenarioIntervention(
            action_type="FORCE_STATE",
            target_state=args.target_state,
            at_step=args.horizon_steps // 4,
        )

    t0 = time.perf_counter()
    if scen:
        comparison = simulator.simulate_counterfactual(
            initial_state=start_state,
            intervention=scen,
            horizon_steps=args.horizon_steps,
            num_rollouts=args.rollouts,
            high_risk_states=set(simulator.vocab[-3:]) if len(simulator.vocab) >= 6 else None,
        )
        sim_ms = (time.perf_counter() - t0) * 1000.0

        print(f"\n[+] Simulation Complete in {sim_ms:.2f} ms ({sim_ms / (args.rollouts * 2):.4f} ms/rollout):")
        print(f"    - Mean Trajectory JSD Divergence: {comparison.mean_divergence_jsd:.4f} bits")
        print(f"    - Peak Divergence:                {comparison.peak_divergence_jsd:.4f} bits (at step {comparison.peak_divergence_step})")
        print(f"    - Recommended Path:               {comparison.recommended_path}")
        print(f"    - Summary Insight:                {comparison.summary_insight}")

        out_data = comparison.to_dict()
        out_data["simulation_time_ms"] = round(sim_ms, 2)
        out_data["start_state"] = start_state
    else:
        branch = simulator.simulate_rollouts(
            initial_state=start_state,
            horizon_steps=args.horizon_steps,
            num_rollouts=args.rollouts,
        )
        sim_ms = (time.perf_counter() - t0) * 1000.0
        print(f"\n[+] Status-Quo Rollout Complete in {sim_ms:.2f} ms:")
        print(f"    - Mean Trajectory Entropy: {branch.mean_trajectory_entropy:.4f} bits")
        print(f"    - Dominant Terminal State: {sorted(branch.absorption_probabilities.items(), key=lambda kv: kv[1], reverse=True)[0]}")
        out_data = branch.to_dict()
        out_data["simulation_time_ms"] = round(sim_ms, 2)

    # 4. Save Artifact
    out_file = args.output_file
    if not out_file:
        out_file = str(
            repo_root / "artifacts" / "forecasting_v1" / "sandbox_simulation_report.json"
        )
    out_path = Path(out_file)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out_data, f, indent=2)
    print(f"[*] Artifact saved: {out_path.resolve()}\n")


if __name__ == "__main__":
    main()
