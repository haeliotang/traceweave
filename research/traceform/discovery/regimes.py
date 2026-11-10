# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Dynamic Regime Shift Detection.

Segments trajectory into macroscopic dynamic regimes (R01, R02, ...)
based on information-theoretic divergence (Jensen-Shannon Divergence)
between sliding temporal state distributions.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
from typing import Any, Dict, List, Tuple


@dataclass
class Regime:
    regime_id: int
    regime_token: str
    start_step: int
    end_step: int
    start_utc: str
    end_utc: str
    duration_frames: int
    dominant_states: List[Tuple[str, float]]
    entropy_bits: float

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def _compute_empirical_dist(
    tokens: List[str], all_states: List[str]
) -> Dict[str, float]:
    counts = {s: 0 for s in all_states}
    for t in tokens:
        if t in counts:
            counts[t] += 1
    total = len(tokens)
    if total == 0:
        return {s: 1.0 / len(all_states) for s in all_states}
    return {s: counts[s] / total for s in all_states}


def _jensen_shannon_divergence(p: Dict[str, float], q: Dict[str, float]) -> float:
    """Computes JSD between two discrete probability distributions in [0, 1]."""
    m = {k: 0.5 * (p[k] + q[k]) for k in p}
    kl_pm = sum(p[k] * math.log2(p[k] / m[k]) for k in p if p[k] > 0 and m[k] > 0)
    kl_qm = sum(q[k] * math.log2(q[k] / m[k]) for k in q if q[k] > 0 and m[k] > 0)
    jsd = 0.5 * (kl_pm + kl_qm)
    return max(0.0, min(1.0, jsd))


def detect_dynamic_regimes(
    records: List[Dict[str, Any]],
    window_size: int = 60,
    jsd_threshold: float = 0.35,
    min_regime_length: int = 30,
) -> List[Regime]:
    """
    Detects macroscopic regime shifts using sliding-window JSD peak detection.
    """
    n = len(records)
    if n < window_size * 2:
        return []

    tokens = [r["state_token"] for r in records]
    all_states = sorted(list(set(tokens)))

    # Compute divergence profile
    divergence_profile: List[float] = [0.0] * n
    for t in range(window_size, n - window_size):
        left_window = tokens[t - window_size : t]
        right_window = tokens[t : t + window_size]
        p_left = _compute_empirical_dist(left_window, all_states)
        p_right = _compute_empirical_dist(right_window, all_states)
        divergence_profile[t] = _jensen_shannon_divergence(p_left, p_right)

    # Detect peaks exceeding threshold
    changepoints = [0]
    for t in range(window_size + 1, n - window_size - 1):
        if (
            divergence_profile[t] >= jsd_threshold
            and divergence_profile[t] >= divergence_profile[t - 1]
            and divergence_profile[t] >= divergence_profile[t + 1]
            and (t - changepoints[-1]) >= min_regime_length
        ):
            changepoints.append(t)

    changepoints.append(n)

    # Build Regimes
    regimes: List[Regime] = []
    for r_idx in range(len(changepoints) - 1):
        idx_start = changepoints[r_idx]
        idx_end = changepoints[r_idx + 1] - 1
        regime_tokens = tokens[idx_start : idx_end + 1]

        # Calculate state frequency in regime
        freq: Dict[str, int] = {}
        for tok in regime_tokens:
            freq[tok] = freq.get(tok, 0) + 1

        tot = len(regime_tokens)
        all_state_shares = [(tok, round(cnt / tot, 3)) for tok, cnt in freq.items()]
        sorted_dominant = sorted(all_state_shares, key=lambda x: x[1], reverse=True)

        # Entropy of regime (computed over all states, not just top-3)
        ent = 0.0
        for _, prob in sorted_dominant:
            if prob > 0:
                ent -= prob * math.log2(prob)

        # Truncate to top 3 for display
        sorted_dominant = sorted_dominant[:3]

        reg = Regime(
            regime_id=r_idx + 1,
            regime_token=f"R{r_idx + 1:02d}",
            start_step=idx_start,
            end_step=idx_end,
            start_utc=records[idx_start]["timestamp_utc"],
            end_utc=records[idx_end]["timestamp_utc"],
            duration_frames=idx_end - idx_start + 1,
            dominant_states=sorted_dominant,
            entropy_bits=round(ent, 3),
        )
        regimes.append(reg)

    return regimes
