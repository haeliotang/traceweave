"""
Temporal Stability Validator.

Performs rigorous out-of-sample temporal invariance testing between
Day 1–7 Discovery Window (W1) and Day 8–14 Validation Window (W2).
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
from typing import Any, Dict, List, Optional, Tuple

from traceform.null_models.surrogates import (
    compute_empirical_transition_matrix,
    generate_permutation_surrogate,
)
from traceform.discovery.motifs import extract_trajectory_motifs, TrajectoryMotif


@dataclass
class StabilityMetrics:
    w1_observation_count: int
    w2_observation_count: int
    w1_state_vocabulary_size: int
    w2_state_vocabulary_size: int
    shared_state_vocabulary_count: int
    vocabulary_jaccard_similarity: float
    w2_frames_covered_by_w1_vocabulary_ratio: float
    shared_vocabulary_rank_correlation: float
    distribution_cosine_similarity: float
    distribution_pearson_correlation: float
    transition_matrix_jsd_w1_vs_w2: float
    transition_matrix_jsd_w1_vs_null: float
    jsd_ratio_to_null: float
    motifs_recurrence_ratio_w2: float
    stability_verdict_confirmed: bool

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class TemporalStabilityValidator:
    def __init__(self, max_gap_seconds: float = 1800.0):
        self.max_gap_seconds = max_gap_seconds

    @staticmethod
    def _spearman_rank_correlation(p: Dict[str, float], q: Dict[str, float], keys: Optional[List[str]] = None) -> float:
        """Computes Spearman rank correlation between two probability profiles over specified keys."""
        eval_keys = keys if keys is not None else sorted(list(set(p.keys()).union(set(q.keys()))))
        if len(eval_keys) <= 1:
            return 1.0

        p_vals = [p.get(k, 0.0) for k in eval_keys]
        q_vals = [q.get(k, 0.0) for k in eval_keys]

        def _get_ranks(arr: List[float]) -> List[float]:
            indexed = sorted(enumerate(arr), key=lambda x: x[1])
            ranks = [0.0] * len(arr)
            for rank, (orig_idx, _) in enumerate(indexed, 1):
                ranks[orig_idx] = float(rank)
            return ranks

        r_p = _get_ranks(p_vals)
        r_q = _get_ranks(q_vals)
        n = len(eval_keys)
        d_sq = sum((rp - rq) ** 2 for rp, rq in zip(r_p, r_q))
        rho = 1.0 - (6.0 * d_sq) / (n * (n * n - 1))
        return round(float(rho), 4)

    @staticmethod
    def _cosine_similarity(p: Dict[str, float], q: Dict[str, float], keys: List[str]) -> float:
        """Computes cosine similarity between two probability vectors."""
        dot = sum(p.get(k, 0.0) * q.get(k, 0.0) for k in keys)
        norm_p = math.sqrt(sum(p.get(k, 0.0) ** 2 for k in keys))
        norm_q = math.sqrt(sum(q.get(k, 0.0) ** 2 for k in keys))
        if norm_p == 0.0 or norm_q == 0.0:
            return 0.0
        return round(dot / (norm_p * norm_q), 4)

    @staticmethod
    def _pearson_correlation(p: Dict[str, float], q: Dict[str, float], keys: List[str]) -> float:
        """Computes Pearson correlation coefficient between two probability profiles."""
        n = len(keys)
        if n <= 1:
            return 1.0
        p_vals = [p.get(k, 0.0) for k in keys]
        q_vals = [q.get(k, 0.0) for k in keys]
        mean_p = sum(p_vals) / n
        mean_q = sum(q_vals) / n
        cov = sum((x - mean_p) * (y - mean_q) for x, y in zip(p_vals, q_vals))
        var_p = sum((x - mean_p) ** 2 for x in p_vals)
        var_q = sum((y - mean_q) ** 2 for y in q_vals)
        denom = math.sqrt(var_p * var_q)
        if denom == 0.0:
            return 0.0
        return round(cov / denom, 4)

    @staticmethod
    def _matrix_jsd(
        vocab: List[str],
        m1: Dict[str, Dict[str, float]],
        m2: Dict[str, Dict[str, float]],
        stat: Dict[str, float],
    ) -> float:
        """
        Computes average Jensen-Shannon Divergence across transition probability rows,
        weighted by stationary distribution.
        """
        total_jsd = 0.0
        for s_i in vocab:
            w_i = stat.get(s_i, 0.0)
            if w_i <= 0:
                continue

            row1 = m1.get(s_i, {})
            row2 = m2.get(s_i, {})

            m_row = {
                s_j: 0.5 * (row1.get(s_j, 0.0) + row2.get(s_j, 0.0))
                for s_j in vocab
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

    def validate(
        self,
        w1_records: List[Dict[str, Any]],
        w2_records: List[Dict[str, Any]],
    ) -> Tuple[StabilityMetrics, Dict[str, Any]]:
        """
        Executes out-of-sample temporal stability validation.
        """
        if not w1_records or not w2_records:
            raise ValueError("Both w1_records and w2_records must be non-empty for stability validation.")

        w1_tokens = [r["state_token"] for r in w1_records]
        w2_tokens = [r["state_token"] for r in w2_records]

        vocab_w1_set = set(w1_tokens)
        vocab_w2_set = set(w2_tokens)
        shared_vocab = vocab_w1_set.intersection(vocab_w2_set)
        union_vocab = vocab_w1_set.union(vocab_w2_set)

        jaccard = round(len(shared_vocab) / len(union_vocab), 4)

        # Coverage of W2 frames by W1 vocabulary
        w2_covered_count = sum(1 for t in w2_tokens if t in vocab_w1_set)
        w2_coverage_ratio = round(w2_covered_count / len(w2_tokens), 4)

        # Transition matrices
        full_vocab = sorted(list(union_vocab))
        shared_vocab_list = sorted(list(shared_vocab))
        _, trans_w1, stat_w1 = compute_empirical_transition_matrix(w1_tokens, full_vocab)
        _, trans_w2, stat_w2 = compute_empirical_transition_matrix(w2_tokens, full_vocab)

        # Distribution correlations
        shared_rank_corr = self._spearman_rank_correlation(stat_w1, stat_w2, keys=shared_vocab_list)
        cosine_sim = self._cosine_similarity(stat_w1, stat_w2, keys=full_vocab)
        pearson_corr = self._pearson_correlation(stat_w1, stat_w2, keys=full_vocab)

        # Transition matrix JSD W1 vs W2
        jsd_w1_w2 = self._matrix_jsd(full_vocab, trans_w1, trans_w2, stat_w1)

        # Transition matrix JSD W1 vs Permutation Null
        null_w2_tokens = generate_permutation_surrogate(w2_tokens, seed=42)
        _, trans_null, _ = compute_empirical_transition_matrix(null_w2_tokens, full_vocab)
        jsd_w1_null = self._matrix_jsd(full_vocab, trans_w1, trans_null, stat_w1)

        jsd_ratio = round(jsd_w1_w2 / (jsd_w1_null if jsd_w1_null > 0 else 1.0), 4)

        # Motif recurrence: extract motifs on W1 and test frequency in W2
        w1_motifs = extract_trajectory_motifs(w1_records, min_length=3, max_length=5, min_support=4)

        motif_recurrence_details: List[Dict[str, Any]] = []
        recurring_motifs_in_w2 = 0

        # Build n-gram search on W2
        w2_token_seq = w2_tokens
        w2_ngram_counts: Dict[Tuple[str, ...], int] = {}
        for L in range(3, 6):
            for i in range(len(w2_token_seq) - L + 1):
                sub = tuple(w2_token_seq[i : i + L])
                w2_ngram_counts[sub] = w2_ngram_counts.get(sub, 0) + 1

        for m in w1_motifs:
            pat_tuple = tuple(m.pattern)
            w2_count = w2_ngram_counts.get(pat_tuple, 0)
            if w2_count >= 1:
                recurring_motifs_in_w2 += 1
            motif_recurrence_details.append({
                "motif_token": m.motif_token,
                "pattern": m.pattern_string,
                "w1_support": m.support,
                "w2_support": w2_count,
                "recurrent": w2_count >= 1,
            })

        motif_recurrence_ratio = round(recurring_motifs_in_w2 / max(1, len(w1_motifs)), 4)

        # Scientific decision criteria:
        # 1. W2 frames covered by W1 vocabulary >= 85%
        # 2. Distribution invariance: Cosine similarity >= 0.70 AND Shared Rank Corr >= 0.60
        # 3. Transition matrix JSD ratio to Null <= 0.70
        # 4. Motif recurrence ratio >= 50%
        confirmed = (
            w2_coverage_ratio >= 0.85
            and (cosine_sim >= 0.70 and shared_rank_corr >= 0.60)
            and jsd_ratio <= 0.70
            and motif_recurrence_ratio >= 0.50
        )

        metrics = StabilityMetrics(
            w1_observation_count=len(w1_tokens),
            w2_observation_count=len(w2_tokens),
            w1_state_vocabulary_size=len(vocab_w1_set),
            w2_state_vocabulary_size=len(vocab_w2_set),
            shared_state_vocabulary_count=len(shared_vocab),
            vocabulary_jaccard_similarity=jaccard,
            w2_frames_covered_by_w1_vocabulary_ratio=w2_coverage_ratio,
            shared_vocabulary_rank_correlation=shared_rank_corr,
            distribution_cosine_similarity=cosine_sim,
            distribution_pearson_correlation=pearson_corr,
            transition_matrix_jsd_w1_vs_w2=jsd_w1_w2,
            transition_matrix_jsd_w1_vs_null=jsd_w1_null,
            jsd_ratio_to_null=jsd_ratio,
            motifs_recurrence_ratio_w2=motif_recurrence_ratio,
            stability_verdict_confirmed=confirmed,
        )

        return metrics, {
            "motifs_recurrence_details": motif_recurrence_details,
            "top_shared_states": sorted(list(shared_vocab))[:15],
        }
