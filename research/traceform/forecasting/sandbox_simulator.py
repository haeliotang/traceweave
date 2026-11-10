# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
On-Device Forward Closed-Loop Sandbox Simulation and Counterfactual Engine.

Simulates future dynamical life trajectories via multi-step Monte Carlo rollouts,
incorporates non-stationary circadian and infradian modulation, and computes
counterfactual 'what-if' branch divergences, risk deltas, and absorption probabilities
strictly without human semantic pre-injection.
"""

from __future__ import annotations

import bisect
from collections import Counter
from dataclasses import asdict, dataclass
import datetime
import math
import random
from typing import Any, Callable, Dict, List, Optional, Set, Tuple

from traceform.forecasting.periodic_kernel import PeriodicTransitionKernel
from traceform.forecasting.continuity import same_stream_segment


@dataclass
class SimulationBranch:
    """Represents a simulated probabilistic trajectory branch over a forward temporal horizon."""
    branch_id: str
    horizon_steps: int
    step_duration_seconds: float
    num_rollouts: int
    state_occupancy_by_step: List[Dict[str, float]]
    entropy_by_step: List[float]
    mean_trajectory_entropy: float
    absorption_probabilities: Dict[str, float]
    mean_hitting_steps: Dict[str, float]
    expected_cumulative_energy: float
    sample_paths: List[List[str]]
    dynamics_evidence: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "branch_id": self.branch_id,
            "horizon_steps": self.horizon_steps,
            "horizon_hours": round((self.horizon_steps * self.step_duration_seconds) / 3600.0, 2),
            "step_duration_seconds": self.step_duration_seconds,
            "num_rollouts": self.num_rollouts,
            "mean_trajectory_entropy": round(self.mean_trajectory_entropy, 4),
            "absorption_probabilities": {
                k: round(v, 4) for k, v in self.absorption_probabilities.items()
            },
            "mean_hitting_steps": {
                k: round(v, 2) for k, v in self.mean_hitting_steps.items()
            },
            "expected_cumulative_energy": round(self.expected_cumulative_energy, 4),
            "terminal_state_distribution": {
                k: round(v, 4)
                for k, v in sorted(
                    self.state_occupancy_by_step[-1].items(),
                    key=lambda kv: kv[1],
                    reverse=True,
                )[:10]
            }
            if self.state_occupancy_by_step
            else {},
            "sample_paths": self.sample_paths[:5],
            **({"dynamics_evidence": self.dynamics_evidence} if self.dynamics_evidence is not None else {}),
        }


@dataclass
class ScenarioIntervention:
    """Declarative specification for a counterfactual simulation scenario."""
    action_type: str  # "FORCE_STATE", "CLAMP_DWELL", "SUPPRESS_STATE", "TIME_SHIFT"
    target_state: Optional[str] = None
    at_step: Optional[int] = None
    max_dwell_steps: Optional[int] = None
    suppressed_states: Optional[Set[str]] = None
    time_shift_hours: Optional[float] = None
    custom_hook: Optional[Callable[[int, str, datetime.datetime], Optional[str]]] = None


@dataclass
class CounterfactualComparison:
    """Quantitative divergence and risk comparison between parallel simulation branches."""
    baseline_branch_id: str
    scenario_branch_id: str
    horizon_steps: int
    mean_divergence_jsd: float
    peak_divergence_jsd: float
    peak_divergence_step: int
    stepwise_jsd: List[float]
    risk_delta_map: Dict[str, float]
    stabilization_step_baseline: int
    stabilization_step_scenario: int
    recommended_path: str
    summary_insight: str

    def to_dict(self) -> Dict[str, Any]:
        return {
            "baseline_branch_id": self.baseline_branch_id,
            "scenario_branch_id": self.scenario_branch_id,
            "horizon_steps": self.horizon_steps,
            "mean_divergence_jsd": round(self.mean_divergence_jsd, 4),
            "peak_divergence_jsd": round(self.peak_divergence_jsd, 4),
            "peak_divergence_step": self.peak_divergence_step,
            "risk_delta_map": {k: round(v, 4) for k, v in self.risk_delta_map.items()},
            "stabilization_step_baseline": self.stabilization_step_baseline,
            "stabilization_step_scenario": self.stabilization_step_scenario,
            "recommended_path": self.recommended_path,
            "summary_insight": self.summary_insight,
        }


def _compute_shannon_entropy(prob_dist: Dict[str, float]) -> float:
    """Calculates Shannon entropy in bits for a discrete distribution."""
    h = 0.0
    for p in prob_dist.values():
        if p > 1e-12:
            h -= p * math.log2(p)
    return h


def _compute_jsd(p: Dict[str, float], q: Dict[str, float], vocab: Set[str]) -> float:
    """Computes Jensen-Shannon divergence (in bits) between two discrete probability distributions."""
    m: Dict[str, float] = {}
    for s in vocab:
        m[s] = 0.5 * (p.get(s, 0.0) + q.get(s, 0.0))

    kl_pm = 0.0
    kl_qm = 0.0
    for s in vocab:
        p_val = p.get(s, 0.0)
        q_val = q.get(s, 0.0)
        m_val = m.get(s, 0.0)
        if p_val > 1e-12 and m_val > 1e-12:
            kl_pm += p_val * math.log2(p_val / m_val)
        if q_val > 1e-12 and m_val > 1e-12:
            kl_qm += q_val * math.log2(q_val / m_val)

    return 0.5 * (kl_pm + kl_qm)


class SandboxSimulator:
    """
    On-device closed-loop forward trajectory sandbox simulator.
    
    Executes fast Monte Carlo rollouts across potential energy landscapes,
    conditions transitions on circadian/infradian time kernels, and evaluates
    counterfactual intervention scenarios in sub-25 millisecond runs.
    """

    def __init__(
        self,
        step_nominal_seconds: float = 30.0,
        energy_map: Optional[Dict[str, float]] = None,
        timezone_name: str = "UTC",
    ):
        self.step_seconds = step_nominal_seconds
        self.energy_map = energy_map or {}
        self.timezone_name = timezone_name
        self.periodic_kernel = None
        self._time_cdf = {}

        self.vocab: List[str] = []
        self.vocab_set: Set[str] = set()
        self.global_trans: Dict[str, Dict[str, float]] = {}

        # Pre-computed CDF tables for O(log K) sampling: state -> (cum_probs, next_states)
        self._global_cdf: Dict[str, Tuple[List[float], List[str]]] = {}

        # Time-conditioned transition kernels (diurnal phase: NIGHT, MORNING, AFTERNOON, EVENING)
        self.phase_trans: Dict[str, Dict[str, Dict[str, float]]] = {}
        self._phase_cdf: Dict[str, Dict[str, Tuple[List[float], List[str]]]] = {}

    def _get_diurnal_phase(self, dt: datetime.datetime) -> str:
        """Categorizes hour into 4 primary circadian phases."""
        hour = dt.hour
        if 0 <= hour < 6:
            return "NIGHT"
        elif 6 <= hour < 12:
            return "MORNING"
        elif 12 <= hour < 18:
            return "AFTERNOON"
        else:
            return "EVENING"

    def _build_cdf_cache(
        self, trans_matrix: Dict[str, Dict[str, float]]
    ) -> Dict[str, Tuple[List[float], List[str]]]:
        """Precomputes discrete CDF cumulative distributions for fast binary search sampling."""
        cdf_cache: Dict[str, Tuple[List[float], List[str]]] = {}
        for src, dst_map in trans_matrix.items():
            if not dst_map:
                cdf_cache[src] = ([1.0], [src])
                continue
            sorted_items = sorted(dst_map.items(), key=lambda kv: kv[1], reverse=True)
            states = [item[0] for item in sorted_items]
            probs = [item[1] for item in sorted_items]
            tot = sum(probs)
            if tot <= 1e-12:
                cdf_cache[src] = ([1.0], [src])
                continue

            cum_probs: List[float] = []
            running = 0.0
            for p in probs:
                running += p / tot
                cum_probs.append(min(1.0, running))
            cum_probs[-1] = 1.0  # Guarantee closing bound
            cdf_cache[src] = (cum_probs, states)
        return cdf_cache

    def fit_from_records(
        self,
        records: List[Dict[str, Any]],
        max_gap_seconds: float = 1800.0,
    ) -> SandboxSimulator:
        """Fits global and circadian-conditioned transition tables directly from timestamped records."""
        if not records:
            return self

        self.vocab_set = set(r["state_token"] for r in records if "state_token" in r)
        if not self.vocab_set:
            raise ValueError(
                f"fit_from_records: none of the {len(records)} records contain "
                f"a 'state_token' field; cannot build transition model."
            )
        self.vocab = sorted(list(self.vocab_set))

        # 1. Count transitions globally and per diurnal phase
        global_counts: Dict[str, Counter[str]] = {s: Counter() for s in self.vocab}
        phase_counts: Dict[str, Dict[str, Counter[str]]] = {
            ph: {s: Counter() for s in self.vocab}
            for ph in ["NIGHT", "MORNING", "AFTERNOON", "EVENING"]
        }

        for i in range(len(records) - 1):
            r1 = records[i]
            r2 = records[i + 1]
            try:
                t1_str = r1["timestamp_utc"].replace("Z", "+00:00")
                t2_str = r2["timestamp_utc"].replace("Z", "+00:00")
                t1 = datetime.datetime.fromisoformat(t1_str)
                t2 = datetime.datetime.fromisoformat(t2_str)
                gap_seconds = (t2 - t1).total_seconds()
                if gap_seconds <= 0 or gap_seconds > max_gap_seconds:
                    continue
                if t1.tzinfo is None or t2.tzinfo is None:
                    continue
                if not same_stream_segment(r1, r2):
                    continue
            except Exception:
                continue

            u = r1.get("state_token")
            v = r2.get("state_token")
            if not u or not v:
                continue

            global_counts[u][v] += 1
            phase = self._get_diurnal_phase(t1)
            phase_counts[phase][u][v] += 1

        # 2. Normalize global transitions
        self.global_trans = {s: {} for s in self.vocab}
        for u in self.vocab:
            total_out = sum(global_counts[u].values())
            if total_out > 0:
                for v, cnt in global_counts[u].items():
                    self.global_trans[u][v] = cnt / total_out
            else:
                self.global_trans[u][u] = 1.0

        # 3. Normalize phase transitions with Laplace fallback to global
        self.phase_trans = {ph: {s: {} for s in self.vocab} for ph in phase_counts}
        for ph in phase_counts:
            for u in self.vocab:
                p_cnt = phase_counts[ph][u]
                total_p = sum(p_cnt.values())
                if total_p >= 5:
                    for v, cnt in p_cnt.items():
                        self.phase_trans[ph][u][v] = cnt / total_p
                else:
                    # Fallback to global transition rule
                    self.phase_trans[ph][u] = dict(self.global_trans[u])

        # 4. Precompute CDF tables
        self._global_cdf = self._build_cdf_cache(self.global_trans)
        self._phase_cdf = {
            ph: self._build_cdf_cache(self.phase_trans[ph]) for ph in self.phase_trans
        }
        self.periodic_kernel = PeriodicTransitionKernel(self.timezone_name).fit(
            records, self.global_trans, max_gap_seconds
        )
        self._time_cdf = {}

        return self

    def fit_from_transition_matrix(
        self,
        trans_matrix: Dict[str, Dict[str, float]],
        vocab: Optional[List[str]] = None,
    ) -> SandboxSimulator:
        """Initializes simulator from a precomputed empirical transition matrix."""
        self.global_trans = trans_matrix
        self.periodic_kernel = None
        self._phase_cdf = {}
        self._time_cdf = {}
        if vocab:
            self.vocab = sorted(list(set(vocab)))
        else:
            nodes: Set[str] = set(trans_matrix.keys())
            for row in trans_matrix.values():
                nodes.update(row.keys())
            self.vocab = sorted(list(nodes))
        self.vocab_set = set(self.vocab)
        self._global_cdf = self._build_cdf_cache(self.global_trans)
        return self

    def _sample_next_state(
        self,
        current_state: str,
        current_time: Optional[datetime.datetime],
        rng: random.Random,
    ) -> str:
        """Draws next state in O(log K) using binary search over the appropriate CDF table."""
        cdf_dict = self._global_cdf
        if current_time is not None and self.periodic_kernel is not None:
            if current_time not in self._time_cdf:
                # Bounded cache; each rollout reuses the same future instants.
                if len(self._time_cdf) >= 4096:
                    self._time_cdf.clear()
                self._time_cdf[current_time] = self._build_cdf_cache({
                    source: self.periodic_kernel.row(source, current_time)
                    for source in self.vocab
                })
            cdf_dict = self._time_cdf[current_time]

        cdf_entry = cdf_dict.get(current_state)
        if not cdf_entry:
            # Self-loop fallback
            return current_state

        cum_probs, states = cdf_entry
        r = rng.random()
        idx = bisect.bisect_right(cum_probs, r)
        idx = min(idx, len(states) - 1)
        return states[idx]

    def simulate_rollouts(
        self,
        initial_state: str,
        horizon_steps: int = 120,  # default 1 hour at 30s/step
        num_rollouts: int = 500,
        start_time_utc: Optional[datetime.datetime] = None,
        branch_id: str = "status_quo",
        intervention: Optional[ScenarioIntervention] = None,
        seed: Optional[int] = 42,
        max_sample_paths: int = 10,
    ) -> SimulationBranch:
        """
        Executes parallel stochastic forward rollouts starting from initial_state.
        Applies scenario interventions dynamically during trajectory evolution.
        """
        rng = random.Random(seed)
        if start_time_utc is None:
            start_time_utc = datetime.datetime.now(datetime.timezone.utc)

        if initial_state not in self.vocab_set:
            # Safest fallback to first available state
            initial_state = self.vocab[0] if self.vocab else "M01"

        step_counts: List[Counter[str]] = [Counter() for _ in range(horizon_steps)]
        paths: List[List[str]] = []
        hitting_records: Dict[str, List[int]] = {s: [] for s in self.vocab}
        total_energy_accum: float = 0.0

        for r_idx in range(num_rollouts):
            curr_s = initial_state
            curr_t = start_time_utc
            curr_dwell = 1
            path: List[str] = [curr_s]
            step_counts[0][curr_s] += 1

            first_hit_in_run: Set[str] = {curr_s}
            hitting_records[curr_s].append(0)
            total_energy_accum += self.energy_map.get(curr_s, 1.0)

            for step in range(1, horizon_steps):
                curr_t = curr_t + datetime.timedelta(seconds=self.step_seconds)

                # Check intervention overrides
                overridden_state: Optional[str] = None
                if intervention:
                    # 1. Force state at step
                    if (
                        intervention.action_type == "FORCE_STATE"
                        and intervention.target_state
                        and step == intervention.at_step
                    ):
                        overridden_state = intervention.target_state

                    # 2. Clamp prolonged dwell
                    elif (
                        intervention.action_type == "CLAMP_DWELL"
                        and intervention.max_dwell_steps
                        and curr_dwell >= intervention.max_dwell_steps
                    ):
                        # Force out: sample from non-self sub-distribution
                        row = self.global_trans.get(curr_s, {})
                        non_self = {v: p for v, p in row.items() if v != curr_s and p > 0}
                        if non_self:
                            tot = sum(non_self.values())
                            r_val = rng.random() * tot
                            running_sum = 0.0
                            chosen = list(non_self.keys())[0]
                            for v, p in non_self.items():
                                running_sum += p
                                if r_val <= running_sum:
                                    chosen = v
                                    break
                            overridden_state = chosen
                        else:
                            # No non-self transitions — pick random vocab state
                            others = [v for v in self.vocab if v != curr_s]
                            overridden_state = rng.choice(others) if others else curr_s

                    # 3. Custom programmatic intervention hook
                    elif intervention.custom_hook:
                        hook_result = intervention.custom_hook(step, curr_s, curr_t)
                        if hook_result:
                            overridden_state = hook_result

                if overridden_state is not None:
                    next_s = overridden_state
                else:
                    next_s = self._sample_next_state(curr_s, curr_t, rng)

                    # 4. Suppress undesirable states
                    if (
                        intervention
                        and intervention.action_type == "SUPPRESS_STATE"
                        and intervention.suppressed_states
                        and next_s in intervention.suppressed_states
                    ):
                        # Reject transition and resample once
                        resampled = self._sample_next_state(curr_s, curr_t, rng)
                        next_s = (
                            resampled
                            if resampled not in intervention.suppressed_states
                            else curr_s
                        )

                # Dwell counter update
                if next_s == curr_s:
                    curr_dwell += 1
                else:
                    curr_dwell = 1

                curr_s = next_s
                path.append(curr_s)
                step_counts[step][curr_s] += 1

                if curr_s not in first_hit_in_run:
                    first_hit_in_run.add(curr_s)
                    hitting_records[curr_s].append(step)

                # Energy accumulation
                total_energy_accum += self.energy_map.get(curr_s, 1.0)

            if len(paths) < max_sample_paths:
                paths.append(path)

        # Aggregate occupancy probability distributions
        occupancy_by_step: List[Dict[str, float]] = []
        entropy_by_step: List[float] = []

        for step in range(horizon_steps):
            cnt = step_counts[step]
            dist = {s: cnt[s] / num_rollouts for s in cnt}
            occupancy_by_step.append(dist)
            entropy_by_step.append(_compute_shannon_entropy(dist))

        mean_entropy = sum(entropy_by_step) / horizon_steps if horizon_steps > 0 else 0.0

        # Absorption / Terminal probabilities
        terminal_dist = occupancy_by_step[-1] if occupancy_by_step else {}
        mean_hitting_steps = {
            s: (sum(hitting_records[s]) / len(hitting_records[s]))
            if hitting_records[s]
            else float(horizon_steps)
            for s in self.vocab
        }

        expected_energy = total_energy_accum / (num_rollouts * horizon_steps) if horizon_steps > 0 else 0.0

        return SimulationBranch(
            branch_id=branch_id,
            horizon_steps=horizon_steps,
            step_duration_seconds=self.step_seconds,
            num_rollouts=num_rollouts,
            state_occupancy_by_step=occupancy_by_step,
            entropy_by_step=entropy_by_step,
            mean_trajectory_entropy=mean_entropy,
            absorption_probabilities=terminal_dist,
            mean_hitting_steps=mean_hitting_steps,
            expected_cumulative_energy=expected_energy,
            sample_paths=paths,
        )

    def simulate_continuous_rollouts(
        self, model, initial_record, quantize, horizon_steps=120,
        num_rollouts=300, seed=42, hurst=None, branch_id="continuous_status_quo",
        phase_quantize=None, velocity_ema_alpha=0.4,
    ) -> SimulationBranch:
        """Aggregate continuously evolved coordinates in the existing token vocabulary.

        The supplied quantizer must use the fitted model's coordinate system.
        No discrete transition or hypothetical actuator is applied to these paths.
        """
        if (not isinstance(horizon_steps, int) or horizon_steps < 1
                or not isinstance(num_rollouts, int) or num_rollouts < 1
                or not math.isfinite(self.step_seconds) or self.step_seconds <= 0):
            raise ValueError("positive integer horizon/count and finite step required")
        if model.model_version is None or initial_record.get("model_version") != model.model_version:
            raise ValueError("continuous rollout requires matching representation provenance")
        if any(initial_record.get(key) != model.coordinate_provenance.get(key)
               for key in ("model_sha256", "normalizer_sha256")):
            raise ValueError("continuous rollout requires matching coordinate provenance")
        initial = initial_record["continuous_vector"]
        if len(initial) != model.dimension or not all(math.isfinite(value) for value in initial):
            raise ValueError("finite dimension-matched initial vector required")
        initial_velocity = initial_record.get("continuous_velocity", [0.0] * model.dimension)
        if phase_quantize is not None:
            if (len(initial_velocity) != model.dimension
                    or not all(math.isfinite(value) for value in initial_velocity)
                    or not math.isfinite(velocity_ema_alpha) or not 0 <= velocity_ema_alpha <= 1):
                raise ValueError("finite matching initial velocity and EMA alpha in [0, 1] required")
        elif quantize is None:
            raise ValueError("coordinate or phase quantizer required")
        counts = [Counter() for _ in range(horizon_steps)]
        hitting = {state: [] for state in self.vocab}
        paths = []
        seen = set()
        energy = 0.0
        previous = None
        velocity = None
        previous_time = 0.0

        def observe(run, step, elapsed, vector):
            nonlocal energy, previous, velocity, previous_time
            if phase_quantize is not None:
                if step == 0:
                    velocity = list(initial_velocity)
                else:
                    interval = max(0.1, elapsed - previous_time)
                    velocity = [velocity_ema_alpha * (current - old) / interval
                                + (1 - velocity_ema_alpha) * value
                                for current, old, value in zip(vector, previous, velocity)]
                state = phase_quantize(vector, tuple(velocity))
                previous, previous_time = tuple(vector), elapsed
            else:
                state = quantize(vector)
            if state not in self.vocab_set:
                raise ValueError("continuous quantizer returned a state outside the vocabulary")
            if step == 0:
                seen.clear()
                if run < 5:
                    paths.append([])
            counts[step][state] += 1
            energy += self.energy_map.get(state, 1.0)
            if state not in seen:
                seen.add(state)
                hitting[state].append(step)
            if run < 5:
                paths[run].append(state)

        if horizon_steps == 1:
            for run in range(num_rollouts):
                observe(run, 0, 0.0, tuple(initial))
        else:
            options = {} if hurst is None else {"hurst": hurst}
            rollout = model.rollout if hurst is None else model.rollout_fractional
            rollout(initial, (horizon_steps - 1) * self.step_seconds,
                    self.step_seconds, num_rollouts, seed, observer=observe, **options)
        occupancy = [{state: count / num_rollouts for state, count in row.items()} for row in counts]
        entropy = [_compute_shannon_entropy(row) for row in occupancy]
        return SimulationBranch(
            branch_id, horizon_steps, self.step_seconds, num_rollouts, occupancy,
            entropy, sum(entropy) / horizon_steps, occupancy[-1],
            {state: sum(steps) / len(steps) if steps else float(horizon_steps)
             for state, steps in hitting.items()},
            energy / (num_rollouts * horizon_steps), paths,
            {"driver": "white_noise_ito" if hurst is None else "additive_fractional_noise",
             "hurst": hurst, "model_version": model.model_version,
             "coordinate_provenance": dict(model.coordinate_provenance),
             "quantization": "phase_energy" if phase_quantize is not None else "coordinates",
             "scope": "continuous_forward_prediction_not_empirical_memory_or_actuator_effect"},
        )

    def simulate_counterfactual(
        self,
        initial_state: str,
        intervention: ScenarioIntervention,
        horizon_steps: int = 120,
        num_rollouts: int = 500,
        start_time_utc: Optional[datetime.datetime] = None,
        high_risk_states: Optional[Set[str]] = None,
        seed: Optional[int] = 42,
    ) -> CounterfactualComparison:
        """
        Simulates baseline status-quo and intervention branches concurrently,
        computing trajectory divergence (JSD), risk mitigation deltas, and stabilization times.
        """
        if start_time_utc is None:
            start_time_utc = datetime.datetime.now(datetime.timezone.utc)

        branch_a = self.simulate_rollouts(
            initial_state=initial_state,
            horizon_steps=horizon_steps,
            num_rollouts=num_rollouts,
            start_time_utc=start_time_utc,
            branch_id="status_quo",
            intervention=None,
            seed=seed,
        )

        branch_b = self.simulate_rollouts(
            initial_state=initial_state,
            horizon_steps=horizon_steps,
            num_rollouts=num_rollouts,
            start_time_utc=start_time_utc,
            branch_id="intervention",
            intervention=intervention,
            seed=(seed + 1) if seed is not None else None,
        )

        # Stepwise Jensen-Shannon Divergence
        stepwise_jsd: List[float] = []
        peak_jsd = 0.0
        peak_step = 0

        for step in range(horizon_steps):
            p = branch_a.state_occupancy_by_step[step]
            q = branch_b.state_occupancy_by_step[step]
            jsd_val = _compute_jsd(p, q, self.vocab_set)
            stepwise_jsd.append(jsd_val)
            if jsd_val > peak_jsd:
                peak_jsd = jsd_val
                peak_step = step

        mean_jsd = sum(stepwise_jsd) / horizon_steps if horizon_steps > 0 else 0.0

        # Risk delta on high-risk states
        risk_delta_map: Dict[str, float] = {}
        total_risk_count_change = 0
        target_states = self.vocab_set if high_risk_states is None else high_risk_states
        for s in target_states:
            p_a = branch_a.absorption_probabilities.get(s, 0.0)
            p_b = branch_b.absorption_probabilities.get(s, 0.0)
            count_change = round(p_b * num_rollouts) - round(p_a * num_rollouts)
            total_risk_count_change += count_change
            risk_delta_map[s] = count_change / num_rollouts  # negative means risk reduced

        # Compute stabilization step (step where entropy delta stabilizes within epsilon)
        stab_a = horizon_steps
        stab_b = horizon_steps
        stab_min = max(2, min(5, horizon_steps // 2))  # Adapt to short horizons
        for s in range(stab_min, horizon_steps):
            if abs(branch_a.entropy_by_step[s] - branch_a.entropy_by_step[s - 1]) < 0.01:
                stab_a = s
                break
        for s in range(stab_min, horizon_steps):
            if abs(branch_b.entropy_by_step[s] - branch_b.entropy_by_step[s - 1]) < 0.01:
                stab_b = s
                break

        # Generate recommendation
        total_risk_reduced = sum(risk_delta_map.values())
        if 20 * total_risk_count_change < -num_rollouts:
            rec = "RECOMMEND_INTERVENTION"
            insight = (
                f"Scenario predicts net terminal risk change ({total_risk_reduced:.1%}) "
                f"with peak trajectory divergence of {peak_jsd:.3f} bits at step {peak_step}."
            )
        elif mean_jsd < 0.02:
            rec = "NEUTRAL"
            insight = "Scenario predicts minimal trajectory divergence."
        else:
            rec = "MAINTAIN_STATUS_QUO"
            insight = (f"Scenario predicts no net terminal risk reduction beyond the 5% threshold "
                       f"({total_risk_reduced:.1%}); mean state energy is "
                       f"{branch_a.expected_cumulative_energy:.2f} vs {branch_b.expected_cumulative_energy:.2f}.")

        return CounterfactualComparison(
            baseline_branch_id="status_quo",
            scenario_branch_id="intervention",
            horizon_steps=horizon_steps,
            mean_divergence_jsd=mean_jsd,
            peak_divergence_jsd=peak_jsd,
            peak_divergence_step=peak_step,
            stepwise_jsd=stepwise_jsd,
            risk_delta_map=risk_delta_map,
            stabilization_step_baseline=stab_a,
            stabilization_step_scenario=stab_b,
            recommended_path=rec,
            summary_insight=insight,
        )
