"""
Trajectory Motif Discovery Engine.

Discovers recurring sequential patterns (M01, M02, ...) of length L >= 3
that appear with significant support across the natural trajectory.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Dict, List, Tuple


@dataclass
class TrajectoryMotif:
    motif_id: int
    motif_token: str
    pattern: List[str]
    pattern_string: str
    support: int
    length: int
    first_occurrence_utc: str
    last_occurrence_utc: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def extract_trajectory_motifs(
    records: List[Dict[str, Any]],
    min_length: int = 3,
    max_length: int = 5,
    min_support: int = 4,
) -> List[TrajectoryMotif]:
    """
    Extracts recurring non-trivial sequence motifs from discrete state sequence.
    Filters out simple self-loop repetitions (e.g. S01-S01-S01).
    """
    tokens = [r["state_token"] for r in records]
    n = len(tokens)

    ngram_counts: Dict[Tuple[str, ...], List[int]] = {}

    for length in range(min_length, max_length + 1):
        for i in range(n - length + 1):
            sub = tuple(tokens[i : i + length])
            # Filter trivial all-identical repetitions
            if len(set(sub)) == 1:
                continue
            if sub not in ngram_counts:
                ngram_counts[sub] = []
            ngram_counts[sub].append(i)

    # Filter by minimum support
    frequent_motifs = [
        (sub, occs) for sub, occs in ngram_counts.items() if len(occs) >= min_support
    ]

    # Sort by support * length descending (MDL compression heuristic)
    frequent_motifs.sort(key=lambda item: len(item[1]) * len(item[0]), reverse=True)

    results: List[TrajectoryMotif] = []
    seen_patterns: set = set()

    for idx, (pattern_tuple, occ_indices) in enumerate(frequent_motifs, 1):
        pattern_list = list(pattern_tuple)
        pattern_str = " -> ".join(pattern_list)
        if pattern_str in seen_patterns:
            continue
        seen_patterns.add(pattern_str)

        first_ts = records[occ_indices[0]].get("timestamp_utc", "UNKNOWN")
        last_ts = records[occ_indices[-1]].get("timestamp_utc", "UNKNOWN")

        motif = TrajectoryMotif(
            motif_id=len(results) + 1,
            motif_token=f"M{len(results) + 1:02d}",
            pattern=pattern_list,
            pattern_string=pattern_str,
            support=len(occ_indices),
            length=len(pattern_list),
            first_occurrence_utc=first_ts,
            last_occurrence_utc=last_ts,
        )
        results.append(motif)
        if len(results) >= 20:  # Top 20 motifs
            break

    return results
