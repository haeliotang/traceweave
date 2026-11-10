#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Traceform Personal Macroscope Dashboard Generator Script.

Compiles discovered dynamical structures, out-of-sample stability, forward forecasting,
and candidate physical reverse projections into a single standalone interactive HTML dashboard.

Generates:
- artifacts/macroscope_dashboard_v1.html
"""

from __future__ import annotations

import json
from pathlib import Path
import sys

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from traceform.ui.macroscope_generator import MacroscopeDashboardGenerator


def main() -> None:
    print("=" * 70)
    print(" Traceform: The Personal Macroscope Dashboard Compilation")
    print(" Synthesizing Phase Space & Dynamical Structure Visualization")
    print("=" * 70)

    discovery_path = REPO_ROOT / "artifacts" / "structure_discovery_v1" / "structure_discovery_spec.json"
    stability_path = REPO_ROOT / "artifacts" / "temporal_stability_v1" / "temporal_stability_stats.json"
    forecasting_path = REPO_ROOT / "artifacts" / "forecasting_v1" / "forecasting_stats.json"
    bifurcation_path = REPO_ROOT / "artifacts" / "phase_transitions_v1" / "phase_transitions_stats.json"
    semantic_path = REPO_ROOT / "artifacts" / "semantic_projection_v1" / "semantic_projection_spec.json"

    for p in [discovery_path, stability_path, forecasting_path, bifurcation_path, semantic_path]:
        if not p.is_file():
            print(f"Error: Missing dependency artifact at {p}")
            sys.exit(1)

    with open(discovery_path, "r", encoding="utf-8") as f:
        discovery_spec = json.load(f)
    with open(stability_path, "r", encoding="utf-8") as f:
        stability_stats = json.load(f)
    with open(forecasting_path, "r", encoding="utf-8") as f:
        forecasting_stats = json.load(f)
    with open(bifurcation_path, "r", encoding="utf-8") as f:
        bifurcation_stats = json.load(f)
    with open(semantic_path, "r", encoding="utf-8") as f:
        semantic_spec = json.load(f)

    generator = MacroscopeDashboardGenerator()
    html_content = generator.generate_html(
        discovery_spec=discovery_spec,
        stability_stats=stability_stats,
        forecasting_stats=forecasting_stats,
        phase_transitions_stats=bifurcation_stats,
        semantic_spec=semantic_spec,
    )

    out_file = REPO_ROOT / "artifacts" / "macroscope_dashboard_v1.html"
    with open(out_file, "w", encoding="utf-8") as f:
        f.write(html_content)

    print(f"[+] Successfully compiled standalone Macroscope Dashboard HTML:")
    print(f"    File: {out_file} ({len(html_content)} bytes)")
    print(f"    Open in your browser: file://{out_file.resolve()}")
    print("\nDashboard compilation completed successfully.")


if __name__ == "__main__":
    main()
