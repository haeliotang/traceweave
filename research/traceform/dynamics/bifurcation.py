# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Phase Transition and Dynamical Bifurcation Detector.

Computes sliding-window Recurrence Quantification Analysis (RQA) spectra
and detects non-equilibrium phase transitions and critical slowing down in human trajectories.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import asdict, dataclass
import math
import statistics
from typing import Any, Dict, List


@dataclass
class SlidingWindowPoint:
    step_start: int
    step_end: int
    timestamp_utc: str
    determinism: float
    laminarity: float
    transition_entropy: float
    state_entropy: float
    unique_states_count: int
    is_bifurcation_alert: bool
    bifurcation_z_score: float

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class PhaseTransitionDetector:
    def __init__(self, window_size: int = 120, step_size: int = 30):
        self.window_size = window_size
        self.step_size = step_size

    @staticmethod
    def _compute_entropy(counts: List[int]) -> float:
        total = sum(counts)
        if total <= 0:
            return 0.0
        h = 0.0
        for c in counts:
            if c > 0:
                p = c / total
                h -= p * math.log2(p)
        return round(h, 4)

    def compute_sliding_spectrum(
        self, records: List[Dict[str, Any]]
    ) -> List[SlidingWindowPoint]:
        """
        Computes sliding window dynamic metrics across the trajectory.
        """
        N = len(records)
        if N < self.window_size:
            return []

        tokens = [r["state_token"] for r in records]
        points: List[SlidingWindowPoint] = []

        entropy_values: List[float] = []

        # Pass 1: compute metrics
        temp_data: List[Dict[str, Any]] = []
        for start_idx in range(0, N - self.window_size + 1, self.step_size):
            end_idx = start_idx + self.window_size
            window_tokens = tokens[start_idx:end_idx]
            mid_ts = records[min(N - 1, start_idx + self.window_size // 2)].get(
                "timestamp_utc", ""
            )

            # State entropy
            counts = Counter(window_tokens)
            h_state = self._compute_entropy(list(counts.values()))
            entropy_values.append(h_state)

            # Transitions
            trans_pairs = list(zip(window_tokens[:-1], window_tokens[1:]))
            trans_counts = Counter(trans_pairs)
            h_trans = self._compute_entropy(list(trans_counts.values()))

            # Determinism approximation in window: ratio of diagonal / self transitions
            self_trans = sum(1 for a, b in trans_pairs if a == b)
            det = round(self_trans / max(1, len(trans_pairs)), 4)
            lam = round(max(counts.values()) / len(window_tokens), 4)

            temp_data.append(
                {
                    "step_start": start_idx,
                    "step_end": end_idx,
                    "timestamp_utc": mid_ts,
                    "determinism": det,
                    "laminarity": lam,
                    "transition_entropy": h_trans,
                    "state_entropy": h_state,
                    "unique_states_count": len(counts),
                }
            )

        if not temp_data:
            return []

        # Compute z-scores on entropy to detect bifurcations / chaos spikes
        mean_ent = statistics.mean(entropy_values)
        std_ent = statistics.stdev(entropy_values) if len(entropy_values) > 1 else 1.0
        if std_ent == 0.0:
            std_ent = 1.0

        for item in temp_data:
            z = (item["state_entropy"] - mean_ent) / std_ent
            is_alert = (z >= 2.0) or (item["determinism"] < 0.35)
            points.append(
                SlidingWindowPoint(
                    step_start=item["step_start"],
                    step_end=item["step_end"],
                    timestamp_utc=item["timestamp_utc"],
                    determinism=item["determinism"],
                    laminarity=item["laminarity"],
                    transition_entropy=item["transition_entropy"],
                    state_entropy=item["state_entropy"],
                    unique_states_count=item["unique_states_count"],
                    is_bifurcation_alert=is_alert,
                    bifurcation_z_score=round(z, 2),
                )
            )

        return points
