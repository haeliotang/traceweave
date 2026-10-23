"""
Recurrence Plot and Recurrence Quantification Analysis (RQA).

Implements non-linear dynamical recurrence matrix construction and RQA extraction
(Recurrence Rate, Determinism, Laminarity, Trapping Time, Entropy).
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
from typing import Any, Dict, List


@dataclass
class RQAMetrics:
    recurrence_rate: float
    determinism: float
    laminarity: float
    average_diagonal_length: float
    max_diagonal_length: int
    trapping_time: float
    diagonal_entropy: float
    threshold_epsilon: float

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def compute_recurrence_matrix(
    vectors: List[List[float]],
    topos: List[Dict[str, str]],
    distance_func,
    epsilon: float = 0.25,
) -> List[List[int]]:
    """
    Computes binary recurrence matrix R[i][j] = 1 if D(x_i, x_j) <= epsilon else 0.
    """
    n = len(vectors)
    r_matrix: List[List[int]] = [[0] * n for _ in range(n)]

    for i in range(n):
        r_matrix[i][i] = 1
        for j in range(i + 1, n):
            d = distance_func(vectors[i], topos[i], vectors[j], topos[j])
            if d <= epsilon:
                r_matrix[i][j] = 1
                r_matrix[j][i] = 1

    return r_matrix


def compute_rqa_metrics(
    r_matrix: List[List[int]],
    epsilon: float = 0.25,
    min_line_len: int = 2,
) -> RQAMetrics:
    """
    Extracts standard Recurrence Quantification Analysis (RQA) measures.
    """
    n = len(r_matrix)
    if n == 0:
        return RQAMetrics(0, 0, 0, 0, 0, 0, 0, epsilon)

    total_points = n * n
    recurrence_points = sum(sum(row) for row in r_matrix)
    rr = recurrence_points / total_points

    # 1. Diagonal Line Lengths (parallel to main diagonal, excluding main diagonal j - i != 0)
    diag_lengths: List[int] = []
    for k in range(1, n):
        # Diagonal at offset k (upper triangle)
        curr_len = 0
        for i in range(n - k):
            j = i + k
            if r_matrix[i][j] == 1:
                curr_len += 1
            else:
                if curr_len > 0:
                    diag_lengths.append(curr_len)
                    curr_len = 0
        if curr_len > 0:
            diag_lengths.append(curr_len)

    # 2. Vertical Line Lengths (laminar states)
    vert_lengths: List[int] = []
    for j in range(n):
        curr_len = 0
        for i in range(n):
            if r_matrix[i][j] == 1:
                curr_len += 1
            else:
                if curr_len > 0:
                    vert_lengths.append(curr_len)
                    curr_len = 0
        if curr_len > 0:
            vert_lengths.append(curr_len)

    # Determinism: fraction of diagonal recurrence points in lines >= min_line_len
    total_diag_pts = sum(diag_lengths)
    det_pts = sum(length for length in diag_lengths if length >= min_line_len)
    det = (det_pts / total_diag_pts) if total_diag_pts > 0 else 0.0

    avg_diag = (
        (
            sum(length for length in diag_lengths if length >= min_line_len)
            / len([length for length in diag_lengths if length >= min_line_len])
        )
        if any(length >= min_line_len for length in diag_lengths)
        else 0.0
    )
    max_diag = max(diag_lengths) if diag_lengths else 0

    # Diagonal line length Shannon Entropy
    diag_counts: Dict[int, int] = {}
    for length in diag_lengths:
        if length >= min_line_len:
            diag_counts[length] = diag_counts.get(length, 0) + 1

    total_valid_diags = sum(diag_counts.values())
    entropy = 0.0
    if total_valid_diags > 0:
        for cnt in diag_counts.values():
            p = cnt / total_valid_diags
            entropy -= p * math.log2(p)

    # Laminarity: fraction of vertical recurrence points in lines >= min_line_len
    total_vert_pts = sum(vert_lengths)
    lam_pts = sum(v for v in vert_lengths if v >= min_line_len)
    lam = (lam_pts / total_vert_pts) if total_vert_pts > 0 else 0.0

    valid_verts = [v for v in vert_lengths if v >= min_line_len]
    trapping_time = (sum(valid_verts) / len(valid_verts)) if valid_verts else 0.0

    return RQAMetrics(
        recurrence_rate=round(rr, 4),
        determinism=round(det, 4),
        laminarity=round(lam, 4),
        average_diagonal_length=round(avg_diag, 2),
        max_diagonal_length=max_diag,
        trapping_time=round(trapping_time, 2),
        diagonal_entropy=round(entropy, 4),
        threshold_epsilon=epsilon,
    )
