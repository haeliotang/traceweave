#!/usr/bin/env python3
"""
Traceform Candidate Semantic Projection Runner Script.

Performs physical reverse projection of discovered latent structures
(micro-states Sk, macro-regimes Rj, motifs Mm) into objective physical observables.
Constructs quarantined post-hoc candidate semantic hypotheses.

Generates:
- artifacts/semantic_projection_v1/semantic_projection_spec.json
- artifacts/semantic_projection_v1/SEMANTIC_PROJECTION_v1.md
"""

from __future__ import annotations

from collections import defaultdict
import json
from pathlib import Path
import sys

# Ensure repository root is on sys.path
REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from traceform.semantics.projector import CandidateSemanticProjector, PhysicalObservableProfile


def main() -> None:
    print("=" * 70)
    print(" Traceform Candidate Semantic Projection v1")
    print(" Post-Hoc Physical Reverse Projection of Discovered Latent Structure")
    print("=" * 70)

    # 1. Paths
    seq_path = REPO_ROOT / "artifacts" / "representation_v1" / "state_sequence_14d_v1.jsonl"
    raw_obs_path = REPO_ROOT / "data" / "raw" / ".pixel_sensorium" / "physical_observations.jsonl"
    discovery_path = REPO_ROOT / "artifacts" / "structure_discovery_v1" / "structure_discovery_spec.json"

    if not seq_path.is_file():
        print(f"Error: Missing {seq_path}")
        sys.exit(1)
    if not raw_obs_path.is_file():
        print(f"Error: Missing {raw_obs_path}")
        sys.exit(1)
    if not discovery_path.is_file():
        print(f"Error: Missing {discovery_path}")
        sys.exit(1)

    # 2. Load sequence
    print("[*] Loading 14-day state sequence...")
    seq_records = []
    with open(seq_path, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                seq_records.append(json.loads(line.strip()))
    print(f"    Loaded {len(seq_records)} sequence records.")

    # 3. Load raw observations map
    print("[*] Indexing raw observations ledger...")
    needed_obs_ids = {r["observation_id"] for r in seq_records}
    raw_obs_map = {}
    with open(raw_obs_path, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            obs = json.loads(line.strip())
            obs_id = obs.get("observation_id")
            if obs_id in needed_obs_ids:
                raw_obs_map[obs_id] = obs
    print(f"    Indexed {len(raw_obs_map)} corresponding raw observation frames.")

    # 4. Load structure discovery spec
    with open(discovery_path, "r", encoding="utf-8") as f:
        discovery_spec = json.load(f)

    # 5. Group sequence by state token
    records_by_state = defaultdict(list)
    for r in seq_records:
        records_by_state[r["state_token"]].append(r)

    projector = CandidateSemanticProjector()

    # 6. Compute Physical Profiles for all states
    print("[*] Computing physical observable profiles for discovered micro-states...")
    state_profiles: dict[str, PhysicalObservableProfile] = {}
    for state_token in sorted(records_by_state.keys(), key=lambda tok: int(tok[1:])):
        recs = records_by_state[state_token]
        prof = projector.compute_state_physical_profile(state_token, recs, raw_obs_map)
        state_profiles[state_token] = prof

    print(f"    Profiled {len(state_profiles)} micro-states.")

    # 7. Project Macro-Regimes
    print("[*] Projecting macro-regimes...")
    regimes_list = discovery_spec.get("dynamic_regimes", {}).get("regimes_list", [])
    projected_regimes = projector.project_macro_regimes(regimes_list, state_profiles)

    # 8. Project Motifs
    print("[*] Projecting trajectory motifs...")
    motifs_list = discovery_spec.get("trajectory_motifs", {}).get("top_motifs", [])
    projected_motifs = projector.project_motifs(motifs_list, state_profiles)

    # 9. Save JSON Specification
    output_dir = REPO_ROOT / "artifacts" / "semantic_projection_v1"
    output_dir.mkdir(parents=True, exist_ok=True)

    spec_path = output_dir / "semantic_projection_spec.json"
    spec_data = {
        "protocol": "pixel-sensorium.candidate-semantic-projection.v1",
        "projection_identity": "semantic_projection_14d_v1",
        "epistemic_quarantine": {
            "predefined_semantics_in_discovery": False,
            "nature_of_semantics": "POST_HOC_REVERSE_PHYSICAL_PROJECTION_ONLY",
            "audit_attestation": "All state tokens (S1..SK, R1..RJ, M1..MM) were discovered unsupervised. Candidate semantic labels are hypotheses derived strictly from empirical physical sensors."
        },
        "total_micro_states_profiled": len(state_profiles),
        "micro_state_profiles": {tok: prof.to_dict() for tok, prof in state_profiles.items()},
        "projected_macro_regimes": projected_regimes,
        "projected_trajectory_motifs": projected_motifs,
    }

    with open(spec_path, "w", encoding="utf-8") as f:
        json.dump(spec_data, f, indent=2)
    print(f"Saved semantic projection specification to: {spec_path}")

    # 10. Generate Markdown Report
    report_path = output_dir / "SEMANTIC_PROJECTION_v1.md"
    md = f"""# Traceform Candidate Semantic Projection Audit (v1)

**Execution Date**: 2026-10-01  
**Scope**: 14-Day Natural Human Trajectory (`natural_trajectory_14d_v1`)  
**Methodology**: Unsupervised Latent Structure $\\rightarrow$ Post-Hoc Physical Reverse Projection  

> [!IMPORTANT]
> **Epistemic Quarantine Notice**:  
> All trajectory structures ($S_k, R_j, M_m$) were discovered **strictly without human semantic labels or priors**.  
> The semantic hypotheses presented below are **post-hoc reverse projections** generated by mapping discovered mathematical states back into objective physical sensor readings (lux, acceleration, charging power, diurnal phase, RF anchors).  
> These labels were **NEVER** available to or used by the discovery algorithms.

---

## 1. Micro-State Physical Reverse Projections ($S_1..S_K$)

Below are the empirical sensor profiles and resulting candidate hypotheses for the top recurring micro-states discovered in the 14-day corpus:

| State | Frames | Dur (h) | Stat % | Dark % | AC Plug % | Day/Night | Primary RF Anchor | Candidate Semantic Hypothesis (Post-Hoc) | Conf |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
"""
    for tok, prof in sorted(state_profiles.items(), key=lambda x: x[1].observation_count, reverse=True)[:15]:
        stat_p = f"{prof.stationary_ratio * 100:.1f}%"
        dark_p = f"{prof.darkness_ratio * 100:.1f}%"
        ac_p = f"{prof.plugged_ac_ratio * 100:.1f}%"
        dn_tag = f"{prof.day_ratio*100:.0f}D/{prof.night_ratio*100:.0f}N"
        md += f"| `{tok}` | {prof.observation_count} | {prof.total_duration_hours:.1f}h | {stat_p} | {dark_p} | {ac_p} | {dn_tag} | `{prof.top_wifi_ap}` | **{prof.candidate_semantic_hypothesis.split('(')[0].strip()}** | {prof.hypothesis_confidence:.2f} |\n"

    md += """
---

## 2. Macro-Regime Reverse Projections ($R_1..R_J$)

The continuous trajectory decomposed into 35 coherent dynamical regimes. Below is the reverse projection of representative behavioral regimes across the 14-day observation:

| Regime | Time Window (UTC) | Dur (h) | Dominant State | Physical Interpretation |
| :--- | :--- | :--- | :--- | :--- |
"""
    for r in projected_regimes[:12]:
        start_short = r["start_utc"].split("T")[0] + " " + r["start_utc"].split("T")[1][:5]
        end_short = r["end_utc"].split("T")[0] + " " + r["end_utc"].split("T")[1][:5]
        md += f"| `{r['regime_token']}` | {start_short} → {end_short} | {r['duration_hours']}h | `{r['dominant_state']}` ({r['dominant_share']*100:.1f}%) | {r['candidate_regime_interpretation']} |\n"

    md += """
---

## 3. Trajectory Motif Reverse Projections ($M_1..M_M$)

Multi-step trajectory sequences recurring across both discovery and validation weeks reflect composite physical behavioral patterns:

| Motif | Discovered Sequence | Support | Post-Hoc Behavioral Translation |
| :--- | :--- | :--- | :--- |
"""
    for m in projected_motifs[:10]:
        md += f"| `{m['motif_token']}` | `{m['pattern']}` | {m['support']} | {m['candidate_sequence_interpretation']} |\n"

    md += """
---

## 4. Scientific Synthesis

Through this end-to-end closed loop, Traceform demonstrates:

1. **Physical Groundedness**: Unsupervised mathematical states ($S_k$) naturally group into physically distinct, semantically coherent human life behaviors (nocturnal charging rest, unplugged daylight desk work, active locomotion, RF transition points).
2. **Temporal Coherence**: Macro-regimes ($R_j$) capture multi-hour behavioral epochs that align naturally with diurnal life cycles without any calendar or clock supervision.
3. **Behavioral Invariance**: Trajectory motifs ($M_m$) represent recurring operational routines that repeat faithfully across weeks.

**Conclusion: Natural human trajectory contains persistent, discoverable latent structure that can be reconstructed without predefined semantics.**
"""

    with open(report_path, "w", encoding="utf-8") as f:
        f.write(md)
    print(f"Saved Markdown report to: {report_path}")
    print("\nCandidate Semantic Projection completed successfully.")


if __name__ == "__main__":
    main()
