"""
Null and Surrogate baseline data generators for trajectory hypothesis testing.

Includes:
1. Permutation Null (I.I.D. shuffle)
2. First-Order Markov Null (preserves 1-step transition matrix)
3. Circadian Phase-Preserving Null (preserves diurnal phase bins)
"""

from __future__ import annotations

import collections
import datetime
import math
import random
from typing import Any, Dict, List, Optional, Tuple


def compute_empirical_transition_matrix(
    state_tokens: List[str],
    vocabulary: Optional[List[str]] = None,
) -> Tuple[List[str], Dict[str, Dict[str, float]], Dict[str, float]]:
    """
    Computes state transition probabilities and empirical stationary distribution.
    """
    vocab = sorted(list(set(state_tokens) if vocabulary is None else set(vocabulary)))
    counts: Dict[str, Dict[str, int]] = {s: {s_next: 0 for s_next in vocab} for s in vocab}
    total_out: Dict[str, int] = {s: 0 for s in vocab}
    state_freq: Dict[str, int] = {s: 0 for s in vocab}

    for i in range(len(state_tokens) - 1):
        curr_s = state_tokens[i]
        next_s = state_tokens[i + 1]
        state_freq[curr_s] += 1
        if curr_s in counts and next_s in counts[curr_s]:
            counts[curr_s][next_s] += 1
            total_out[curr_s] += 1

    if state_tokens:
        state_freq[state_tokens[-1]] += 1

    total_states = len(state_tokens)
    stationary = {s: state_freq[s] / total_states for s in vocab}

    trans_prob: Dict[str, Dict[str, float]] = {s: {} for s in vocab}
    for s in vocab:
        denom = total_out[s]
        if denom > 0:
            for s_next in vocab:
                trans_prob[s][s_next] = counts[s][s_next] / denom
        else:
            # Self-loop fallback for absorbing/terminal states
            for s_next in vocab:
                trans_prob[s][s_next] = 1.0 if s_next == s else 0.0

    return vocab, trans_prob, stationary


def compute_transition_entropy_rate(
    trans_prob: Dict[str, Dict[str, float]],
    stationary: Dict[str, float],
) -> float:
    """
    Computes Shannon transition entropy rate H(S_{t+1} | S_t):
    H = - sum_i pi_i sum_j P_ij log2(P_ij)
    """
    h_rate = 0.0
    for s_i, pi_i in stationary.items():
        if pi_i <= 0:
            continue
        p_row = trans_prob.get(s_i, {})
        row_entropy = 0.0
        for p_ij in p_row.values():
            if p_ij > 1e-12:
                row_entropy -= p_ij * math.log2(p_ij)
        h_rate += pi_i * row_entropy
    return round(h_rate, 4)


def generate_permutation_surrogate(
    state_tokens: List[str],
    seed: Optional[int] = None,
) -> List[str]:
    """
    Model 0: Uniformly random permutation of the state sequence.
    Destroys all Markovian transitions and temporal auto-correlation,
    preserving only the marginal state frequency distribution.
    """
    rng = random.Random(seed)
    shuffled = list(state_tokens)
    rng.shuffle(shuffled)
    return shuffled


def generate_first_order_markov_surrogate(
    state_tokens: List[str],
    vocabulary: Optional[List[str]] = None,
    seed: Optional[int] = None,
) -> List[str]:
    """
    Model 1: First-order Markov random walk surrogate.
    Preserves 1-step empirical transition probabilities P(S_{t+1} | S_t),
    but destroys higher-order memory, long motifs, and global non-Markovian structure.
    """
    if not state_tokens:
        return []

    rng = random.Random(seed)
    vocab, trans_prob, _ = compute_empirical_transition_matrix(state_tokens, vocabulary)

    surrogate: List[str] = [state_tokens[0]]
    curr = state_tokens[0]

    for _ in range(1, len(state_tokens)):
        p_row = trans_prob.get(curr, {})
        choices = list(p_row.keys())
        weights = list(p_row.values())

        if sum(weights) <= 0:
            next_state = curr
        else:
            next_state = rng.choices(choices, weights=weights, k=1)[0]

        surrogate.append(next_state)
        curr = next_state

    return surrogate


def generate_circadian_surrogate(
    state_records: List[Dict[str, Any]],
    seed: Optional[int] = None,
) -> List[str]:
    """
    Model 2: Circadian diurnal surrogate.
    Bins observations by UTC hour of day (0..23), and shuffles observations
    strictly within the same diurnal phase bin across different days.
    Preserves 24-hour diurnal cyclicity while destroying day-to-day continuous coherence.
    """
    if not state_records:
        return []

    rng = random.Random(seed)
    hour_bins: Dict[int, List[str]] = collections.defaultdict(list)

    for r in state_records:
        ts = datetime.datetime.fromisoformat(r["timestamp_utc"].replace("Z", "+00:00"))
        hour_bins[ts.hour].append(r["state_token"])

    # Shuffle each hour bin
    for h in hour_bins:
        rng.shuffle(hour_bins[h])

    # Reconstruct in original timestamp slot order
    bin_pointers: Dict[int, int] = collections.defaultdict(int)
    surrogate: List[str] = []

    for r in state_records:
        ts = datetime.datetime.fromisoformat(r["timestamp_utc"].replace("Z", "+00:00"))
        h = ts.hour
        ptr = bin_pointers[h]
        surrogate.append(hour_bins[h][ptr])
        bin_pointers[h] += 1

    return surrogate
