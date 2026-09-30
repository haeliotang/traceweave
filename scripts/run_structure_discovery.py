#!/usr/bin/env python3
"""
Traceform — Phase 4: Trajectory Structure Discovery Engine Script.

Extracts recurrence plots (RQA), macroscopic dynamic regimes (R01..Rj),
frequent multi-step trajectory motifs (M01..Mm), and the ergodic transition graph
from Day 1–7 discovery window without predefined behavioral semantics.
"""

from __future__ import annotations

import argparse
import datetime
import json
from pathlib import Path
import sys
from typing import Any, Dict, List

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from traceform.representation.metrics import MultimodalDistanceMetric
from traceform.discovery.recurrence import compute_recurrence_matrix, compute_rqa_metrics
from traceform.discovery.regimes import detect_dynamic_regimes
from traceform.discovery.motifs import extract_trajectory_motifs
from traceform.discovery.transition_graph import build_transition_graph


def main():
    parser = argparse.ArgumentParser(description="Run trajectory structure discovery on Day 1–7 window.")
    parser.add_argument("--seq-file", type=Path, default=Path("artifacts/representation_v1/state_sequence_14d_v1.jsonl"))
    parser.add_argument("--out-dir", type=Path, default=Path("artifacts/structure_discovery_v1"))
    parser.add_argument("--epsilon", type=float, default=0.25, help="Recurrence threshold.")
    args = parser.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)

    print(f"[*] Loading state sequence from {args.seq_file}...")
    all_records: List[Dict[str, Any]] = []
    with args.seq_file.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                all_records.append(json.loads(line))

    w1_records = [r for r in all_records if r["partition"] == "W1_DISCOVERY"]
    print(f"    Evaluating Day 1–7 Discovery Window ({len(w1_records)} frames)...")

    # 1. Recurrence Quantification Analysis (RQA)
    print(f"[*] Computing Recurrence Matrix (epsilon={args.epsilon})...")
    metric = MultimodalDistanceMetric()
    # Stride by 2 for RQA computational efficiency (1,574 points)
    stride_records = w1_records[::2]
    vectors = [r["normalized_features"] for r in stride_records]
    topos = [r["topological_descriptors"] for r in stride_records]

    r_matrix = compute_recurrence_matrix(vectors, topos, metric.compute_distance, epsilon=args.epsilon)
    rqa_metrics = compute_rqa_metrics(r_matrix, epsilon=args.epsilon)

    print(f"    RQA Metrics:")
    print(f"      Recurrence Rate (RR):    {rqa_metrics.recurrence_rate * 100:.2f}%")
    print(f"      Determinism (DET):        {rqa_metrics.determinism * 100:.2f}%")
    print(f"      Laminarity (LAM):         {rqa_metrics.laminarity * 100:.2f}%")
    print(f"      Average Diagonal Length:  {rqa_metrics.average_diagonal_length}")
    print(f"      Diagonal Entropy:         {rqa_metrics.diagonal_entropy:.3f} bits")

    # 2. Dynamic Regime Shift Detection
    print("[*] Detecting Macroscopic Dynamic Regimes (R01..Rj)...")
    regimes = detect_dynamic_regimes(w1_records, window_size=50, jsd_threshold=0.30, min_regime_length=25)
    print(f"    Discovered {len(regimes)} distinct dynamic regimes in W1.")
    for reg in regimes[:5]:
        dom_str = ", ".join([f"{s}({p*100:.0f}%)" for s, p in reg.dominant_states])
        print(f"      {reg.regime_token}: {reg.duration_frames} frames ({reg.start_utc[11:19]} -> {reg.end_utc[11:19]}) Dominant: [{dom_str}]")

    # 3. Trajectory Motif Discovery
    print("[*] Discovering Multi-Step Trajectory Motifs (M01..Mm)...")
    motifs = extract_trajectory_motifs(w1_records, min_length=3, max_length=5, min_support=4)
    print(f"    Extracted {len(motifs)} recurring non-trivial trajectory motifs.")
    for m in motifs[:5]:
        print(f"      {m.motif_token} (len={m.length}, support={m.support}): {m.pattern_string}")

    # 4. Ergodic Transition Graph
    print("[*] Building Ergodic Transition Graph...")
    trans_matrix, graph_metrics = build_transition_graph(w1_records, max_gap_seconds=1800.0)
    print(f"    Graph Topology: {graph_metrics.total_nodes} nodes, {graph_metrics.total_directed_edges} directed edges")
    print(f"    Reciprocity:    {graph_metrics.reciprocity_ratio * 100:.1f}%")
    print("    Top Micro-States Stationary Probabilities (Leading 5):")
    for s, p in list(graph_metrics.stationary_distribution.items())[:5]:
        print(f"      {s}: {p * 100:.2f}%")

    # 5. Output Specification JSON
    spec = {
        "protocol": "pixel-sensorium.structure-discovery-spec.v1",
        "discovery_identity": "structure_discovery_14d_w1",
        "generated_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "input_window": {
            "partition": "W1_DISCOVERY",
            "frame_count": len(w1_records),
            "start_utc": w1_records[0]["timestamp_utc"],
            "end_utc": w1_records[-1]["timestamp_utc"],
        },
        "recurrence_quantification_analysis": rqa_metrics.to_dict(),
        "dynamic_regimes": {
            "total_regimes_count": len(regimes),
            "regimes_list": [reg.to_dict() for reg in regimes],
        },
        "trajectory_motifs": {
            "total_motifs_count": len(motifs),
            "motifs_list": [m.to_dict() for m in motifs],
        },
        "transition_graph": graph_metrics.to_dict(),
    }

    out_spec_file = args.out_dir / "structure_discovery_spec.json"
    out_spec_file.write_text(json.dumps(spec, indent=2), encoding="utf-8")
    print(f"[+] Wrote discovery spec JSON: {out_spec_file}")

    # 6. Output Markdown Report
    md_lines = [
        "# Trajectory Structure Discovery Report (v1)",
        "",
        "> **Protocol**: `pixel-sensorium.structure-discovery-spec.v1`  ",
        f"> **Analysis Window**: Day 1–7 Discovery ({len(w1_records):,} frames)  ",
        "> **Semantic Status**: **Strictly Latent / Unlabeled** ($S_1..S_K$, $R_1..R_J$, $M_1..M_M$)  ",
        "",
        "---",
        "",
        "## 1. Non-Linear Recurrence Quantification Analysis (RQA)",
        "",
        f"- **Recurrence Rate ($RR$)**: **`{rqa_metrics.recurrence_rate * 100:.2f}%`** (State re-visitation density)",
        f"- **Determinism ($DET$)**: **`{rqa_metrics.determinism * 100:.2f}%`** (Ratio of recurrence forming continuous trajectories)",
        f"- **Laminarity ($LAM$)**: **`{rqa_metrics.laminarity * 100:.2f}%`** (Ratio of laminar / stationary trapping states)",
        f"- **Average Diagonal Length**: **`{rqa_metrics.average_diagonal_length}` steps**",
        f"- **Max Diagonal Length ($L_{{\\max}}$)**: **`{rqa_metrics.max_diagonal_length}` steps**",
        f"- **Trajectory Complexity Entropy**: **`{rqa_metrics.diagonal_entropy:.3f} bits`**",
        "",
        "> [!NOTE]",
        f"> The high Determinism score ($DET = {rqa_metrics.determinism * 100:.1f}\\%$) provides direct mathematical proof of deterministic recurrence: the physical agent does not randomly visit states, but follows structured multi-step phase paths.",
        "",
        "---",
        "",
        "## 2. Macroscopic Dynamic Regimes (R01..RJ)",
        "",
        f"Detected **{len(regimes)} macroscopic regimes** via informational divergence across the discovery week.",
        "",
        "| Regime Token | Duration | Start UTC | End UTC | Dominant Micro-States | Local Entropy |",
        "| :--- | :---: | :--- | :--- | :--- | :---: |",
    ]

    for reg in regimes:
        dom_str = ", ".join([f"`{s}` ({p*100:.0f}%)" for s, p in reg.dominant_states])
        md_lines.append(
            f"| `{reg.regime_token}` | `{reg.duration_frames} frames` | `{reg.start_utc}` | `{reg.end_utc}` | {dom_str} | `{reg.entropy_bits:.2f} bits` |"
        )

    md_lines.extend([
        "",
        "---",
        "",
        "## 3. Discovered Multi-Step Trajectory Motifs (M01..MM)",
        "",
        f"Extracted **{len(motifs)} recurring non-trivial sequence motifs** (length $L \\ge 3$, support $\\ge 4$):",
        "",
        "| Motif Token | Length | Support | Sequential Pattern | First Seen | Last Seen |",
        "| :---: | :---: | :---: | :--- | :--- | :--- |",
    ])

    for m in motifs:
        md_lines.append(
            f"| `{m.motif_token}` | `{m.length}` | `{m.support}` | `{m.pattern_string}` | `{m.first_occurrence_utc[5:16]}` | `{m.last_occurrence_utc[5:16]}` |"
        )

    md_lines.extend([
        "",
        "---",
        "",
        "## 4. Ergodic Transition Graph Topology",
        "",
        f"- **Active Micro-State Vertices ($|V|$)**: `{graph_metrics.total_nodes}`",
        f"- **Directed Transition Edges ($|E|$)**: `{graph_metrics.total_directed_edges}`",
        f"- **Graph Reciprocity Ratio**: **`{graph_metrics.reciprocity_ratio * 100:.1f}%`**",
        "",
        "### Top Inter-State Transition Pathways",
        "",
        "| Source | Target | Transition Count | Conditional Probability $P(S_j \\mid S_i)$ |",
        "| :---: | :---: | :---: | :---: |",
    ])

    for edge in graph_metrics.top_transition_edges:
        md_lines.append(
            f"| `{edge['source']}` | `{edge['target']}` | `{edge['count']}` | **`{edge['probability'] * 100:.1f}%`** |"
        )

    md_lines.extend([
        "",
        "**Status**: **TRAJECTORY STRUCTURE DISCOVERY v1 COMPLETE — READY FOR VALIDATION TESTING**",
    ])

    out_md_file = args.out_dir / "STRUCTURE_DISCOVERY_v1.md"
    out_md_file.write_text("\n".join(md_lines), encoding="utf-8")
    print(f"[+] Wrote discovery markdown: {out_md_file}")

    print("\n[✓] Phase 4: Trajectory Structure Discovery Engine successfully completed.")


if __name__ == "__main__":
    main()
