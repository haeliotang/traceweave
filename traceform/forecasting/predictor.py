"""
Trajectory Forecasting and Hitting Time Estimator.

Performs forward probabilistic prediction of future trajectory states
and computes absorption/hitting times for dynamical regime transitions.
"""

from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
import math
from typing import Any, Dict, List, Optional, Set, Tuple


@dataclass
class ForecastingEvaluationMetrics:
    total_test_steps: int
    top1_accuracy: float
    top3_accuracy: float
    top5_accuracy: float
    mean_log_loss: float
    perplexity: float
    baseline_unigram_top1_accuracy: float
    accuracy_lift_ratio: float

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class VariableOrderPredictor:
    """
    Predicts next micro-state using a smoothed variable-order Markov architecture
    (falling back gracefully from bigram context to unigram context).
    """

    def __init__(self, smoothing: float = 1e-3):
        self.smoothing = smoothing
        self.vocab: List[str] = []
        self.vocab_set: Set[str] = set()
        self.unigram_counts: Counter[str] = Counter()
        self.bigram_counts: Dict[str, Counter[str]] = defaultdict(Counter)
        self.trigram_counts: Dict[Tuple[str, str], Counter[str]] = defaultdict(Counter)
        self.total_tokens: int = 0

    def fit(self, tokens: List[str]) -> VariableOrderPredictor:
        self.vocab_set = set(tokens)
        self.vocab = sorted(list(self.vocab_set))
        self.total_tokens = len(tokens)

        for i in range(len(tokens)):
            curr = tokens[i]
            self.unigram_counts[curr] += 1

            if i >= 1:
                prev = tokens[i - 1]
                self.bigram_counts[prev][curr] += 1

            if i >= 2:
                prev2 = tokens[i - 2]
                prev1 = tokens[i - 1]
                self.trigram_counts[(prev2, prev1)][curr] += 1

        return self

    def predict_proba(self, history: List[str]) -> Dict[str, float]:
        """
        Returns conditional probability distribution for next state given history tokens.
        """
        if not self.vocab:
            return {}

        K = len(self.vocab)
        # Try 2nd-order (trigram)
        if len(history) >= 2:
            ctx = (history[-2], history[-1])
            if ctx in self.trigram_counts and sum(self.trigram_counts[ctx].values()) >= 2:
                counts = self.trigram_counts[ctx]
                total = sum(counts.values()) + self.smoothing * K
                return {s: (counts[s] + self.smoothing) / total for s in self.vocab}

        # Fallback to 1st-order (bigram)
        if len(history) >= 1:
            prev = history[-1]
            if prev in self.bigram_counts and sum(self.bigram_counts[prev].values()) >= 1:
                counts = self.bigram_counts[prev]
                total = sum(counts.values()) + self.smoothing * K
                return {s: (counts[s] + self.smoothing) / total for s in self.vocab}

        # Fallback to unigram
        total = self.total_tokens + self.smoothing * K
        return {s: (self.unigram_counts[s] + self.smoothing) / total for s in self.vocab}

    def evaluate(self, test_tokens: List[str]) -> ForecastingEvaluationMetrics:
        """
        Evaluates out-of-sample forward predictive accuracy.
        """
        if len(test_tokens) < 2:
            raise ValueError("Test tokens sequence too short.")
        if not self.unigram_counts:
            raise RuntimeError("Predictor has not been fitted: unigram_counts is empty.")

        top1_hits = 0
        top3_hits = 0
        top5_hits = 0
        total_loss = 0.0

        # Baseline unigram top-1
        top_unigram_state = self.unigram_counts.most_common(1)[0][0]
        unigram_top1_hits = sum(1 for tok in test_tokens if tok == top_unigram_state)
        baseline_acc = round(unigram_top1_hits / len(test_tokens), 4)

        eval_steps = len(test_tokens) - 1
        for i in range(1, len(test_tokens)):
            history = test_tokens[max(0, i - 5) : i]
            actual = test_tokens[i]

            prob_dist = self.predict_proba(history)
            sorted_candidates = sorted(prob_dist.items(), key=lambda x: x[1], reverse=True)
            candidate_tokens = [tok for tok, _ in sorted_candidates]

            if candidate_tokens and candidate_tokens[0] == actual:
                top1_hits += 1
            if actual in candidate_tokens[:3]:
                top3_hits += 1
            if actual in candidate_tokens[:5]:
                top5_hits += 1

            p_actual = prob_dist.get(actual, self.smoothing / max(1, len(self.vocab)))
            p_actual = max(p_actual, 1e-12)
            total_loss += -math.log2(p_actual)

        top1_acc = round(top1_hits / eval_steps, 4)
        top3_acc = round(top3_hits / eval_steps, 4)
        top5_acc = round(top5_hits / eval_steps, 4)
        mean_loss = round(total_loss / eval_steps, 4)
        perplexity = round(2.0 ** min(30.0, mean_loss), 2)
        lift = round(top1_acc / max(1e-4, baseline_acc), 2)

        return ForecastingEvaluationMetrics(
            total_test_steps=eval_steps,
            top1_accuracy=top1_acc,
            top3_accuracy=top3_acc,
            top5_accuracy=top5_acc,
            mean_log_loss=mean_loss,
            perplexity=perplexity,
            baseline_unigram_top1_accuracy=baseline_acc,
            accuracy_lift_ratio=lift,
        )


class HittingTimeEstimator:
    """
    Computes expected first passage time (Hitting Time) from transient micro-states
    to target absorbing dynamic states using fundamental matrix theory.
    """

    def __init__(self, step_nominal_seconds: float = 30.0):
        self.nominal_sec = step_nominal_seconds

    def compute_expected_hitting_time(
        self,
        trans_matrix: Dict[str, Dict[str, float]],
        target_states: Set[str],
        vocab: List[str],
    ) -> Dict[str, Dict[str, float]]:
        """
        Calculates expected steps and expected minutes to reach target_states from any state.
        Uses iterative linear convergence for robust numerical stability.
        """
        transient_states = [s for s in vocab if s not in target_states]
        if not transient_states or not target_states:
            return {}

        # Initialize hitting time vectors: targets have E[tau] = 0
        h_steps: Dict[str, float] = {s: 0.0 for s in target_states}
        for s in transient_states:
            h_steps[s] = 10.0  # initial seed

        # Iterative Jacobi relaxation: h_i = 1 + sum_{j in transient} P_{ij} * h_j
        for _ in range(500):
            max_delta = 0.0
            new_h = dict(h_steps)
            for i_state in transient_states:
                row = trans_matrix.get(i_state, {})
                # P to absorbing targets
                val = 1.0
                for j_state, prob in row.items():
                    if j_state in transient_states:
                        val += prob * h_steps[j_state]
                delta = abs(val - h_steps[i_state])
                if delta > max_delta:
                    max_delta = delta
                new_h[i_state] = val
            h_steps = new_h
            if max_delta < 1e-5:
                break

        results: Dict[str, Dict[str, float]] = {}
        for s in vocab:
            steps = round(h_steps.get(s, 0.0), 2)
            mins = round((steps * self.nominal_sec) / 60.0, 1)
            results[s] = {
                "expected_steps": steps,
                "expected_minutes": mins,
                "is_target": float(s in target_states),
            }

        return results
