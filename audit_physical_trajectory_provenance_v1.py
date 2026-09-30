#!/usr/bin/env python3
"""Trajectory Provenance and Zero High-Level Life Semantics Audit.

Proves:
  1. TRAJECTORY_REFERENCE_INTEGRITY:
     Every PhysicalTransition references an existing prior and subsequent PhysicalObservation.
     Every PhysicalEpisode references existing PhysicalTransitions and PhysicalObservations,
     with strictly consistent temporal ordering.
  2. HIGH_LEVEL_LIFE_SEMANTIC_AUTHORITY=0:
     Persistent physical ledgers contain zero high-level life semantic labels
     (sleeping, working, commuting, home, meeting, exercise, eating).
"""

import json
from pathlib import Path
import re
import sys

from pixel_physical import config, ledger


def audit_trajectory_provenance() -> int:
    print("========================================================================")
    print("PIXEL SENSORIUM TRAJECTORY PROVENANCE & SEMANTIC BOUNDARY AUDIT v1")
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

    obs_list = ledger.read_recent_observations(limit=100000)
    trans_list = ledger.read_recent_transitions(limit=100000)
    ep_list = ledger.read_recent_episodes(limit=100000)

    obs_ids = {o.observation_id: o for o in obs_list}
    trans_ids = {t.transition_id: t for t in trans_list}

    print(f"Ledger Counts for Integrity Audit:")
    print(f"  Observations: {len(obs_list)}")
    print(f"  Transitions:  {len(trans_list)}")
    print(f"  Episodes:     {len(ep_list)}")

    # 1. Transition Reference Integrity
    missing_prior = []
    missing_subsequent = []
    for t in trans_list:
        if t.prior_observation_id not in obs_ids:
            missing_prior.append(t.transition_id)
        if t.subsequent_observation_id not in obs_ids:
            missing_subsequent.append(t.transition_id)

    record("Transitions reference valid prior observations", len(missing_prior) == 0, f"Missing: {missing_prior}")
    record("Transitions reference valid subsequent observations", len(missing_subsequent) == 0, f"Missing: {missing_subsequent}")

    # 2. Episode Reference Integrity & Temporal Consistency
    invalid_ep_refs = []
    temporal_inconsistencies = []
    for ep in ep_list:
        if ep.initial_state_ref and ep.initial_state_ref not in obs_ids:
            invalid_ep_refs.append((ep.episode_id, "initial_state_ref", ep.initial_state_ref))
        if ep.final_state_ref and ep.final_state_ref not in obs_ids:
            invalid_ep_refs.append((ep.episode_id, "final_state_ref", ep.final_state_ref))
        for t_id in ep.transition_ids:
            if t_id not in trans_ids:
                invalid_ep_refs.append((ep.episode_id, "transition_id", t_id))
        for obs_id in ep.evidence_observation_ids:
            if obs_id not in obs_ids:
                invalid_ep_refs.append((ep.episode_id, "evidence_observation", obs_id))

        # Check temporal order: start_time <= end_time
        if ep.start_time_utc > ep.end_time_utc:
            temporal_inconsistencies.append(ep.episode_id)

    record("Episodes reference existing observations and transitions", len(invalid_ep_refs) == 0, f"Invalid: {invalid_ep_refs}")
    record("Episode timestamps strictly preserve causal non-decreasing order", len(temporal_inconsistencies) == 0, f"Inconsistent: {temporal_inconsistencies}")

    # 3. High-Level Life Semantics Audit
    FORBIDDEN_SEMANTICS = [
        r"\bsleeping\b", r"\bworking\b", r"\bcommuting\b", r"\bhome\b",
        r"\bmeeting\b", r"\bexercise\b", r"\beating\b", r"\boffice\b",
        r"\bgym\b", r"\brestaurant\b", r"\bdriving\b"
    ]

    violations = []
    for ledger_path in (config.OBSERVATIONS_LEDGER, config.TRANSITIONS_LEDGER, config.EPISODES_LEDGER):
        if ledger_path.exists():
            text = ledger_path.read_text(encoding="utf-8").lower()
            for pat in FORBIDDEN_SEMANTICS:
                matches = re.findall(pat, text)
                if matches:
                    violations.append((ledger_path.name, pat, len(matches)))

    print(f"High-Level Life Semantics Violations Found: {len(violations)}")
    record("HIGH_LEVEL_LIFE_SEMANTIC_AUTHORITY=0: Zero high-level life semantic labels in ledgers", len(violations) == 0, f"Violations: {violations}")

    print("========================================================================")
    print(f"TRAJECTORY PROVENANCE AUDIT VERDICT: {passed_checks}/{total_checks} PASSED")
    print("========================================================================")

    if passed_checks == total_checks:
        print("TRAJECTORY_REFERENCE_INTEGRITY=PASS")
        print("HIGH_LEVEL_LIFE_SEMANTIC_AUTHORITY=0")
        return 0
    else:
        return 1


if __name__ == "__main__":
    sys.exit(audit_trajectory_provenance())
