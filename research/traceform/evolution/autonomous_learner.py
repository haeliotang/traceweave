"""
Traceform Autonomous Learner (Scheme 1: On-Device Self-Evolution).

Enables Pixel 6a to self-calibrate its 2D Dynamical Energy Manifold (DETM)
locally using pure Python (< 3 seconds) without needing USB or PC connection.
Evaluates topological strain, refits the attractor lattice over sliding temporal
windows, and tracks longitudinal drift.
"""

from __future__ import annotations

from collections import deque
import datetime
import json
from pathlib import Path
import time
from typing import Any, Dict, List, Optional, Tuple

from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.manifold_lattice import DynamicalEnergyManifold


class AutonomousLearner:
    """
    On-device autonomous self-evolution engine running locally on Pixel 6a.
    """

    def __init__(
        self,
        raw_ledger_path: Path,
        normalizer_path: Path,
        current_model_path: Path,
        output_model_path: Path,
        strain_threshold: float = 120.0,
    ) -> None:
        self.raw_ledger_path = Path(raw_ledger_path)
        self.normalizer_path = Path(normalizer_path)
        self.current_model_path = Path(current_model_path)
        self.output_model_path = Path(output_model_path)
        self.strain_threshold = strain_threshold

    def evaluate_topological_strain(
        self,
        sample_size: int = 200,
    ) -> Tuple[float, List[Dict[str, Any]]]:
        """
        Calculates average geodesic energy distance over recent observations.
        High strain implies the user's living patterns have fundamentally drifted
        away from the current attractor lattice.
        """
        if (
            not self.raw_ledger_path.is_file()
            or not self.normalizer_path.is_file()
            or not self.current_model_path.is_file()
        ):
            return 0.0, []

        with self.normalizer_path.open("r", encoding="utf-8") as f:
            d = json.load(f)
            normalizer = MultimodalNormalizer.from_dict(
                d.get("normalization_parameters", d)
            )

        with self.current_model_path.open("r", encoding="utf-8") as f:
            manifold = DynamicalEnergyManifold.from_dict(json.load(f))

        # Stream last N lines using bounded deque
        recent_lines: deque[str] = deque(maxlen=sample_size)
        with self.raw_ledger_path.open("r", encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if s:
                    recent_lines.append(s)

        observations = []
        for line_str in recent_lines:
            try:
                observations.append(json.loads(line_str))
            except Exception:
                continue

        if not observations:
            return 0.0, []

        distances = []
        for obs in observations:
            _, _, dist = manifold.quantize(obs, normalizer)
            distances.append(dist)

        avg_strain = sum(distances) / len(distances) if distances else 0.0
        return avg_strain, observations

    def self_calibrate(
        self,
        window_size: int = 1500,
        force: bool = False,
    ) -> Optional[Dict[str, Any]]:
        """
        Executes on-device autonomous retraining if topological strain exceeds threshold
        or if force is True.
        """
        t0 = time.perf_counter()
        avg_strain, _ = self.evaluate_topological_strain(sample_size=200)

        if not force and avg_strain < self.strain_threshold:
            return {
                "calibrated": False,
                "reason": f"Topological strain ({avg_strain:.2f}) within nominal bound (< {self.strain_threshold:.2f})",
                "strain": round(avg_strain, 4),
            }

        # Stream observations for fitting using bounded deque
        fit_lines: deque[str] = deque(maxlen=window_size)
        with self.raw_ledger_path.open("r", encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if s:
                    fit_lines.append(s)

        observations = []
        for line_str in fit_lines:
            try:
                observations.append(json.loads(line_str))
            except Exception:
                continue

        if len(observations) < 50:
            return {
                "calibrated": False,
                "reason": f"Insufficient observations for retraining ({len(observations)} frames)",
                "strain": round(avg_strain, 4),
            }

        # 1. Fit Normalizer
        normalizer = MultimodalNormalizer()
        normalizer.fit_discovery_window(
            observations, description="On-Device Autonomous Window"
        )

        # 2. Fit DETM Manifold
        manifold = DynamicalEnergyManifold(
            grid_height=4, grid_width=4, kinetic_weight=0.25
        )
        tokens = manifold.fit_discovery_window(observations, normalizer, epochs=6)

        # 3. Export calibrated models atomically
        self.output_model_path.parent.mkdir(parents=True, exist_ok=True)
        temp_model = self.output_model_path.with_suffix(".tmp")
        with temp_model.open("w", encoding="utf-8") as f:
            json.dump(manifold.to_dict(), f, indent=2)
        temp_model.replace(self.output_model_path)

        train_ms = (time.perf_counter() - t0) * 1000.0

        return {
            "calibrated": True,
            "observations_used": len(observations),
            "strain_before": round(avg_strain, 4),
            "training_ms": round(train_ms, 2),
            "node_count": len(manifold.nodes),
            "state_tokens": tokens[:6],
            "calibrated_at_utc": datetime.datetime.now(
                datetime.timezone.utc
            ).isoformat(),
            "output_model": str(self.output_model_path),
        }
