"""
Infradian Trajectory Dynamics: Weekday vs Weekend Bi-Stable Attractor Audit.

Quantifies macro-scale weekly periodicity and structural divergence
between weekday and weekend dynamic attractors.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import asdict, dataclass
import datetime
import math
from typing import Any, Dict, List, Tuple

from traceform.null_models.surrogates import compute_empirical_transition_matrix


@dataclass
class InfradianComparisonMetrics:
    weekday_observation_count: int
    weekend_observation_count: int
    weekday_unique_states: int
    weekend_unique_states: int
    shared_states_count: int
    stationary_rank_correlation: float
    distribution_cosine_similarity: float
    transition_matrix_jsd: float
    weekday_determinism_ratio: float
    weekend_determinism_ratio: float
    is_distinct_bistable_attractor: bool

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class InfradianDynamicsAuditor:
    def __init__(self):
        pass

    @staticmethod
    def _parse_is_weekend(iso_ts: str) -> bool:
        s = iso_ts.strip().replace("Z", "+00:00")
        try:
            dt = datetime.datetime.fromisoformat(s)
            # Monday=0, Sunday=6. Saturday=5, Sunday=6
            return dt.weekday() >= 5
        except Exception:
            return False

    @staticmethod
    def _matrix_jsd(
        vocab: List[str],
        m1: Dict[str, Dict[str, float]],
        m2: Dict[str, Dict[str, float]],
        stat: Dict[str, float],
    ) -> float:
        total_jsd = 0.0
        for s_i in vocab:
            w_i = stat.get(s_i, 0.0)
            if w_i <= 0:
                continue
            row1 = m1.get(s_i, {})
            row2 = m2.get(s_i, {})
            m_row = {
                s_j: 0.5 * (row1.get(s_j, 0.0) + row2.get(s_j, 0.0)) for s_j in vocab
            }
            kl1 = sum(
                row1.get(s_j, 0.0) * math.log2(row1.get(s_j, 0.0) / m_row[s_j])
                for s_j in vocab
                if row1.get(s_j, 0.0) > 0 and m_row[s_j] > 0
            )
            kl2 = sum(
                row2.get(s_j, 0.0) * math.log2(row2.get(s_j, 0.0) / m_row[s_j])
                for s_j in vocab
                if row2.get(s_j, 0.0) > 0 and m_row[s_j] > 0
            )
            jsd_row = 0.5 * (kl1 + kl2)
            total_jsd += w_i * jsd_row
        return round(float(total_jsd), 4)

    def audit_weekly_split(
        self,
        records: List[Dict[str, Any]],
    ) -> Tuple[InfradianComparisonMetrics, Dict[str, Any]]:
        """
        Splits sequence into Weekday vs Weekend sub-trajectories and compares attractors.
        """
        weekday_recs = []
        weekend_recs = []

        for r in records:
            ts = r.get("timestamp_utc", "")
            if self._parse_is_weekend(ts):
                weekend_recs.append(r)
            else:
                weekday_recs.append(r)

        weekday_tokens = [r["state_token"] for r in weekday_recs]
        weekend_tokens = [r["state_token"] for r in weekend_recs]

        if not weekday_tokens or not weekend_tokens:
            raise ValueError(
                "Insufficient data: need both weekday and weekend observations for infradian audit."
            )

        vocab_weekday = set(weekday_tokens)
        vocab_weekend = set(weekend_tokens)
        shared = vocab_weekday.intersection(vocab_weekend)
        union_vocab = sorted(list(vocab_weekday.union(vocab_weekend)))

        # Transition matrices
        _, trans_wd, stat_wd = compute_empirical_transition_matrix(
            weekday_tokens, union_vocab
        )
        _, trans_we, stat_we = compute_empirical_transition_matrix(
            weekend_tokens, union_vocab
        )

        # Cosine similarity
        dot = sum(stat_wd.get(s, 0.0) * stat_we.get(s, 0.0) for s in union_vocab)
        norm_wd = math.sqrt(sum(stat_wd.get(s, 0.0) ** 2 for s in union_vocab))
        norm_we = math.sqrt(sum(stat_we.get(s, 0.0) ** 2 for s in union_vocab))
        cos_sim = (
            round(dot / (norm_wd * norm_we), 4) if norm_wd > 0 and norm_we > 0 else 0.0
        )

        # Rank correlation on shared states
        shared_list = sorted(list(shared))
        if len(shared_list) > 1:
            p_vals = [stat_wd.get(s, 0.0) for s in shared_list]
            q_vals = [stat_we.get(s, 0.0) for s in shared_list]

            def _get_ranks(arr):
                indexed = sorted(enumerate(arr), key=lambda x: x[1])
                ranks = [0.0] * len(arr)
                i = 0
                while i < len(indexed):
                    j = i
                    while j < len(indexed) and indexed[j][1] == indexed[i][1]:
                        j += 1
                    avg_rank = (i + 1 + j) / 2.0
                    for k in range(i, j):
                        ranks[indexed[k][0]] = avg_rank
                    i = j
                return ranks

            r_p = _get_ranks(p_vals)
            r_q = _get_ranks(q_vals)
            n = len(shared_list)
            d_sq = sum((rp - rq) ** 2 for rp, rq in zip(r_p, r_q))
            rank_corr = round(1.0 - (6.0 * d_sq) / (n * (n * n - 1)), 4)
        else:
            rank_corr = 1.0

        jsd = self._matrix_jsd(union_vocab, trans_wd, trans_we, stat_wd)

        # Determinism ratios: self-transition rates
        det_wd = round(
            sum(1 for a, b in zip(weekday_tokens[:-1], weekday_tokens[1:]) if a == b)
            / max(1, len(weekday_tokens) - 1),
            4,
        )
        det_we = round(
            sum(1 for a, b in zip(weekend_tokens[:-1], weekend_tokens[1:]) if a == b)
            / max(1, len(weekend_tokens) - 1),
            4,
        )

        # If JSD > 0.05 and cosine similarity < 0.90, it constitutes a distinct bi-stable attractor regime
        is_distinct = jsd >= 0.04 or cos_sim <= 0.88

        metrics = InfradianComparisonMetrics(
            weekday_observation_count=len(weekday_tokens),
            weekend_observation_count=len(weekend_tokens),
            weekday_unique_states=len(vocab_weekday),
            weekend_unique_states=len(vocab_weekend),
            shared_states_count=len(shared),
            stationary_rank_correlation=rank_corr,
            distribution_cosine_similarity=cos_sim,
            transition_matrix_jsd=jsd,
            weekday_determinism_ratio=det_wd,
            weekend_determinism_ratio=det_we,
            is_distinct_bistable_attractor=is_distinct,
        )

        c_wd = Counter(weekday_tokens)
        c_we = Counter(weekend_tokens)
        top_wd = [
            (k, round(v / len(weekday_tokens), 4)) for k, v in c_wd.most_common(5)
        ]
        top_we = [
            (k, round(v / len(weekend_tokens), 4)) for k, v in c_we.most_common(5)
        ]

        details = {
            "top_weekday_states": top_wd,
            "top_weekend_states": top_we,
        }

        return metrics, details
