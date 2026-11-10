#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Traceform — Generate Personal Macroscope 2.0 Dashboard.

Compiles all manifold dynamics, quasi-potential surfaces, longitudinal attractor drift,
CSD tipping timelines, and multiscale anomalous diffusion metrics into a single,
self-contained, interactive HTML5 dashboard:
- artifacts/macroscope_v2/macroscope_dashboard_v2.html
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from traceform.ui.macroscope_v2_generator import MacroscopeV2Generator


def main():
    parser = argparse.ArgumentParser(description="Generate Macroscope 2.0 Interactive Dashboard.")
    parser.add_argument(
        "--dynamics-spec",
        type=Path,
        default=PROJECT_ROOT / "artifacts" / "manifold_dynamics_v1" / "manifold_dynamics_spec.json",
        help="Path to manifold dynamics spec JSON",
    )
    parser.add_argument(
        "--multiscale-spec",
        type=Path,
        default=PROJECT_ROOT / "artifacts" / "multiscale_v1" / "multiscale_diffusion_spec.json",
        help="Path to multiscale diffusion spec JSON",
    )
    parser.add_argument(
        "--out-file",
        type=Path,
        default=PROJECT_ROOT / "artifacts" / "macroscope_v2" / "macroscope_dashboard_v2.html",
        help="Output HTML path",
    )
    args = parser.parse_args()

    print("=" * 76)
    print(" Traceform Macroscope 2.0 Dashboard Generator")
    print("=" * 76)

    if not args.dynamics_spec.is_file():
        print(f"[!] Error: Dynamics spec not found at {args.dynamics_spec}")
        sys.exit(1)

    with args.dynamics_spec.open("r", encoding="utf-8") as f:
        dynamics_data = json.load(f)
    print(f"[*] Loaded Dynamics Spec from: {args.dynamics_spec}")

    multiscale_data = {}
    if args.multiscale_spec.is_file():
        with args.multiscale_spec.open("r", encoding="utf-8") as f:
            multiscale_data = json.load(f)
        print(f"[*] Loaded Multiscale Diffusion Spec from: {args.multiscale_spec}")
    else:
        print("[!] Note: Multiscale spec not found, proceeding with defaults.")

    generator = MacroscopeV2Generator(
        manifold_dynamics_spec=dynamics_data,
        multiscale_spec=multiscale_data,
    )
    html_content = generator.generate_html()

    args.out_file.parent.mkdir(parents=True, exist_ok=True)
    with args.out_file.open("w", encoding="utf-8") as f:
        f.write(html_content)

    print(f"[✓] Successfully generated Macroscope 2.0 Dashboard to:")
    print(f"    {args.out_file} ({len(html_content):,} bytes)")
    print("=" * 76)


if __name__ == "__main__":
    main()
