#!/usr/bin/env python3
"""
Fit and serialize 62D Canonical Trans-Spectral Sensorium models and specs.
"""

from __future__ import annotations

import json
from pathlib import Path
import sys

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from traceform.representation.normalizer import MultimodalNormalizer, CONTINUOUS_FEATURE_NAMES
from traceform.representation.manifold_lattice import DynamicalEnergyManifold


def main():
    raw_path = PROJECT_ROOT / "data" / "raw" / ".pixel_sensorium" / "physical_observations.jsonl"
    rep_spec_path = PROJECT_ROOT / "artifacts" / "representation_v1" / "representation_spec.json"
    norm_path = PROJECT_ROOT / "artifacts" / "representation_v1" / "normalizer_v1.json"
    model_path = PROJECT_ROOT / "artifacts" / "manifold_dynamics_v1" / "manifold_lattice_model.json"
    telemetry_model_path = PROJECT_ROOT / "field_telemetry" / "pixel6a" / "models" / "manifold_lattice_model.json"

    print(f"[*] Reading observations from {raw_path}...")
    obs_list = []
    with raw_path.open("r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                obs_list.append(json.loads(line))
            if len(obs_list) >= 1200:
                break

    print(f"[*] Fitting 62D MultimodalNormalizer on {len(obs_list)} observations...")
    normalizer = MultimodalNormalizer()
    params = normalizer.fit_discovery_window(
        obs_list,
        description="Day 1–7 Discovery Window (62D Canonical Trans-Spectral Sensorium)"
    )
    assert len(params.feature_names) == 62, f"Expected 62 features, got {len(params.feature_names)}"

    print(f"[*] Writing {norm_path}...")
    norm_path.parent.mkdir(parents=True, exist_ok=True)
    with norm_path.open("w", encoding="utf-8") as f:
        json.dump(params.to_dict(), f, indent=2)

    print(f"[*] Updating {rep_spec_path} (preserving micro_state_lattice)...")
    with rep_spec_path.open("r", encoding="utf-8") as f:
        rep_spec = json.load(f)

    rep_spec["normalization_parameters"] = params.to_dict()
    rep_spec["continuous_feature_count"] = 62
    rep_spec["continuous_feature_names"] = list(CONTINUOUS_FEATURE_NAMES)

    with rep_spec_path.open("w", encoding="utf-8") as f:
        json.dump(rep_spec, f, indent=2)

    print(f"[*] Fitting 62D DynamicalEnergyManifold (4x4 lattice, 16 states)...")
    manifold = DynamicalEnergyManifold(grid_height=4, grid_width=4, kinetic_weight=0.25)
    tokens = manifold.fit_discovery_window(obs_list, normalizer, epochs=8, seed=42)
    print(f"    Nodes: {len(manifold.nodes)}, Center dim: {len(manifold.nodes[0].center_x)}")
    assert len(manifold.nodes[0].center_x) == 62

    model_dict = manifold.to_dict()
    print(f"[*] Writing {model_path}...")
    model_path.parent.mkdir(parents=True, exist_ok=True)
    with model_path.open("w", encoding="utf-8") as f:
        json.dump(model_dict, f, indent=2)

    print(f"[*] Writing {telemetry_model_path}...")
    telemetry_model_path.parent.mkdir(parents=True, exist_ok=True)
    with telemetry_model_path.open("w", encoding="utf-8") as f:
        json.dump(model_dict, f, indent=2)

    print("[✓] 62D artifacts successfully fitted and serialized.")


if __name__ == "__main__":
    main()
