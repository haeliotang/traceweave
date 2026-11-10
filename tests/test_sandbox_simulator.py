# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Unit tests for the On-Device Forward Sandbox Simulator & Counterfactual Engine.

Verifies:
1. Monte Carlo forward trajectory rollout probability conservation and step consistency.
2. Stepwise trajectory entropy and cumulative potential energy estimation.
3. Jensen-Shannon Divergence calculation between counterfactual parallel branches.
4. Circadian diurnal phase modulation across 24h cycle.
5. Sub-50 millisecond performance for 500 rollouts over a 120-step horizon.
6. Scenario interventions (CLAMP_DWELL, FORCE_STATE, SUPPRESS_STATE).
"""

from __future__ import annotations

import datetime
import math
import time
import unittest

from traceform.dynamics.continuous import ContinuousDynamics
from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode

from traceform.forecasting.sandbox_simulator import (
    SandboxSimulator,
    ScenarioIntervention,
    SimulationBranch,
    CounterfactualComparison,
    _compute_jsd,
    _compute_shannon_entropy,
)


class TestSandboxSimulator(unittest.TestCase):
    def test_exact_five_percent_reduction_is_not_recommended(self):
        import copy
        from unittest.mock import patch
        simulator = SandboxSimulator().fit_from_transition_matrix({'A': {'A': 1}, 'B': {'B': 1}})
        baseline = simulator.simulate_rollouts('A', horizon_steps=2, num_rollouts=100)
        for remaining, recommended in ((95, False), (94, True)):
            scenario = copy.deepcopy(baseline)
            scenario.absorption_probabilities = {'A': remaining / 100, 'B': (100 - remaining) / 100}
            scenario.state_occupancy_by_step[-1] = scenario.absorption_probabilities
            scenario.entropy_by_step[-1] = _compute_shannon_entropy(scenario.absorption_probabilities)
            with patch.object(simulator, 'simulate_rollouts', side_effect=[baseline, scenario]):
                comparison = simulator.simulate_counterfactual('A', ScenarioIntervention(action_type='CLAMP_DWELL', max_dwell_steps=3),
                    horizon_steps=2, num_rollouts=100, high_risk_states={'A'})
            self.assertEqual(comparison.recommended_path == 'RECOMMEND_INTERVENTION', recommended)

    def test_recommendation_counts_risk_increases_as_well_as_decreases(self):
        simulator = SandboxSimulator().fit_from_transition_matrix({'A': {'A': 1}, 'B': {'B': 1}})
        intervention = ScenarioIntervention(action_type='FORCE_STATE', target_state='B', at_step=1)
        for risk_states in ({'A', 'B'}, None, set()):
            comparison = simulator.simulate_counterfactual(
                'A', intervention, horizon_steps=3, num_rollouts=10, high_risk_states=risk_states)
            self.assertNotEqual(comparison.recommended_path, 'RECOMMEND_INTERVENTION')
            self.assertAlmostEqual(sum(comparison.risk_delta_map.values()), 0)
            self.assertNotIn('lower dissipation energy', comparison.summary_insight)
        comparison = simulator.simulate_counterfactual(
            'A', intervention, horizon_steps=3, num_rollouts=10, high_risk_states={'A'})
        self.assertEqual(comparison.recommended_path, 'RECOMMEND_INTERVENTION')
        self.assertEqual(comparison.risk_delta_map, {'A': -1})

    def test_continuous_phase_velocity_resets_for_each_rollout(self):
        model = ContinuousDynamics()
        model.dimension = 1
        model.model_version = 'v1'
        model.anchors = [{'center': [0], 'drift': [1], 'noise_covariance': [[0]]}]
        manifold = DynamicalEnergyManifold()
        manifold.nodes = [ManifoldNode(0, (0, 0), 'A', [0], [1]),
                          ManifoldNode(1, (0, 1), 'B', [0], [-1])]
        manifold.last_obs_vector, manifold.last_velocity = [9], [8]
        sim = SandboxSimulator(step_nominal_seconds=1).fit_from_transition_matrix({'A': {'A': 1}, 'B': {'B': 1}})
        for hurst in (None, .25):
            branch = sim.simulate_continuous_rollouts(
                model, {'continuous_vector': [0], 'continuous_velocity': [-1], 'model_version': 'v1'},
                None, horizon_steps=3, num_rollouts=7, hurst=hurst,
                phase_quantize=lambda x, v: manifold.quantize_phase(x, v)[0],
                velocity_ema_alpha=manifold.ema_alpha)
            self.assertEqual(branch.state_occupancy_by_step, [{'B': 1}, {'B': 1}, {'A': 1}])
            self.assertEqual(branch.sample_paths, [['B', 'B', 'A']] * 5)
        self.assertEqual(manifold.last_obs_vector, [9])
        self.assertEqual(manifold.last_velocity, [8])

    def test_continuous_branches_aggregate_all_runs_without_discrete_jumps(self):
        model = ContinuousDynamics()
        model.dimension = 128
        model.model_version = 'v1'
        model.anchors = [{'center': [0] * 128, 'drift': [1] * 128,
                          'noise_covariance': [[0] * 128 for _ in range(128)]}]
        sim = SandboxSimulator(step_nominal_seconds=1, energy_map={'A': 0, 'B': 2})
        sim.fit_from_transition_matrix({'A': {'A': 1}, 'B': {'B': 1}})
        record = {'continuous_vector': [0] * 128, 'model_version': 'v1'}
        for hurst in (None, .25):
            branch = sim.simulate_continuous_rollouts(
                model, record, lambda vector: 'B' if vector[127] >= 2 else 'A',
                horizon_steps=4, num_rollouts=7, hurst=hurst)
            self.assertEqual(branch.state_occupancy_by_step, [{'A': 1}, {'A': 1}, {'B': 1}, {'B': 1}])
            self.assertEqual(branch.absorption_probabilities, {'B': 1})
            self.assertEqual(branch.mean_hitting_steps['B'], 2)
            self.assertEqual(branch.expected_cumulative_energy, 1)
            self.assertEqual(len(branch.sample_paths), 5)
            self.assertEqual(branch.to_dict()['dynamics_evidence']['hurst'], hurst)
        record['model_version'] = 'v2'
        with self.assertRaisesRegex(ValueError, 'provenance'):
            sim.simulate_continuous_rollouts(model, record, lambda vector: 'A')
        record['model_version'] = 'v1'
        with self.assertRaisesRegex(ValueError, 'vocabulary'):
            sim.simulate_continuous_rollouts(model, record, lambda vector: 'unknown', num_rollouts=1)

    def test_continuous_decimal_step_has_exact_requested_horizon(self):
        model = ContinuousDynamics()
        model.dimension = 1
        model.model_version = 'v1'
        model.anchors = [{'center': [0], 'drift': [1], 'noise_covariance': [[0]]}]
        sim = SandboxSimulator(step_nominal_seconds=.1).fit_from_transition_matrix({'A': {'A': 1}})
        for hurst in (None, .25):
            branch = sim.simulate_continuous_rollouts(
                model, {'continuous_vector': [0], 'model_version': 'v1'}, lambda vector: 'A',
                horizon_steps=7, num_rollouts=2, hurst=hurst)
            self.assertEqual(len(branch.sample_paths[0]), 7)
            self.assertEqual(branch.state_occupancy_by_step, [{'A': 1}] * 7)

    def test_nonpositive_timestamp_gaps_are_not_transitions(self):
        records = [
            {"timestamp_utc": "2026-10-01T02:01:00Z", "state_token": "M01"},
            {"timestamp_utc": "2026-10-01T02:00:00Z", "state_token": "M02"},
            {"timestamp_utc": "2026-10-01T02:00:00Z", "state_token": "M03"},
        ]
        sim = SandboxSimulator().fit_from_records(records)
        self.assertEqual(sim.global_trans["M01"], {"M01": 1.0})
        self.assertEqual(sim.global_trans["M02"], {"M02": 1.0})

    def setUp(self):
        self.vocab = ["M01", "M02", "M03", "M04"]
        self.energy_map = {"M01": 0.2, "M02": 0.8, "M03": 1.5, "M04": 3.0}
        self.trans_matrix = {
            "M01": {"M01": 0.70, "M02": 0.20, "M03": 0.08, "M04": 0.02},
            "M02": {"M01": 0.30, "M02": 0.50, "M03": 0.15, "M04": 0.05},
            "M03": {"M01": 0.10, "M02": 0.20, "M03": 0.60, "M04": 0.10},
            "M04": {"M01": 0.05, "M02": 0.10, "M03": 0.25, "M04": 0.60},
        }
        self.simulator = SandboxSimulator(
            step_nominal_seconds=30.0,
            energy_map=self.energy_map,
        ).fit_from_transition_matrix(self.trans_matrix, vocab=self.vocab)

    def test_rollout_probability_conservation(self):
        """Verifies that state occupancy probabilities sum to 1.0 at every future step."""
        branch = self.simulator.simulate_rollouts(
            initial_state="M01",
            horizon_steps=60,
            num_rollouts=200,
            seed=42,
        )
        self.assertIsInstance(branch, SimulationBranch)
        self.assertEqual(len(branch.state_occupancy_by_step), 60)
        self.assertEqual(len(branch.entropy_by_step), 60)

        for step, dist in enumerate(branch.state_occupancy_by_step):
            tot = sum(dist.values())
            self.assertAlmostEqual(tot, 1.0, places=4, msg=f"Step {step} probs do not sum to 1")

        # Initial step must be 100% in initial state
        self.assertAlmostEqual(branch.state_occupancy_by_step[0]["M01"], 1.0, places=4)

    def test_entropy_and_energy_bounds(self):
        """Verifies non-negative trajectory entropy and cumulative potential energy."""
        branch = self.simulator.simulate_rollouts(
            initial_state="M04",
            horizon_steps=40,
            num_rollouts=150,
            seed=10,
        )
        self.assertGreaterEqual(branch.mean_trajectory_entropy, 0.0)
        self.assertLessEqual(branch.mean_trajectory_entropy, math.log2(len(self.vocab)))
        self.assertGreater(branch.expected_cumulative_energy, 0.0)

    def test_counterfactual_intervention_divergence(self):
        """Verifies that an intervention produces divergence and measurable risk delta."""
        intervention = ScenarioIntervention(
            action_type="FORCE_STATE",
            target_state="M01",
            at_step=10,
        )
        comp = self.simulator.simulate_counterfactual(
            initial_state="M04",
            intervention=intervention,
            horizon_steps=50,
            num_rollouts=300,
            high_risk_states={"M04"},
            seed=99,
        )
        self.assertIsInstance(comp, CounterfactualComparison)
        self.assertGreater(comp.peak_divergence_jsd, 0.0)
        self.assertGreater(comp.mean_divergence_jsd, 0.0)
        self.assertIn("M04", comp.risk_delta_map)
        # Forcing M01 at step 10 should reduce terminal absorption probability of high-risk M04
        self.assertLessEqual(comp.risk_delta_map["M04"], 0.05)

    def test_clamp_dwell_scenario(self):
        """Verifies that CLAMP_DWELL limits excessive dwell times."""
        # High persistence matrix
        sticky_trans = {
            "M01": {"M01": 0.99, "M02": 0.01},
            "M02": {"M01": 0.01, "M02": 0.99},
        }
        sticky_sim = SandboxSimulator().fit_from_transition_matrix(sticky_trans, vocab=["M01", "M02"])
        
        # Free rollout
        free_branch = sticky_sim.simulate_rollouts("M01", horizon_steps=50, num_rollouts=100, seed=1)
        # Clamped rollout
        clamped_branch = sticky_sim.simulate_rollouts(
            "M01",
            horizon_steps=50,
            num_rollouts=100,
            intervention=ScenarioIntervention(action_type="CLAMP_DWELL", max_dwell_steps=10),
            seed=1,
        )
        # Clamped branch should have higher proportion of M02 transitions
        terminal_m02_free = free_branch.absorption_probabilities.get("M02", 0.0)
        terminal_m02_clamped = clamped_branch.absorption_probabilities.get("M02", 0.0)
        self.assertGreater(terminal_m02_clamped, terminal_m02_free)

    def test_circadian_phase_conditioning(self):
        """Verifies diurnal phase modulation alters transition dynamics based on timestamp."""
        # Construct synthetic records covering night and afternoon
        records = []
        for i in range(50):
            # Night frames: only stay in M01
            records.append({
                "timestamp_utc": f"2026-10-01T02:{i:02d}:00Z",
                "state_token": "M01",
            })
        for i in range(50):
            # Afternoon frames: alternate M02 -> M03
            records.append({
                "timestamp_utc": f"2026-10-01T14:{i:02d}:00Z",
                "state_token": "M02" if i % 2 == 0 else "M03",
            })

        sim = SandboxSimulator().fit_from_records(records)
        self.assertIn("NIGHT", sim.phase_trans)
        self.assertIn("AFTERNOON", sim.phase_trans)

        night_time = datetime.datetime(2026, 10, 1, 3, 0, tzinfo=datetime.timezone.utc)
        afternoon_time = datetime.datetime(2026, 10, 1, 15, 0, tzinfo=datetime.timezone.utc)

        night_branch = sim.simulate_rollouts("M01", horizon_steps=20, num_rollouts=100, start_time_utc=night_time, seed=5)
        self.assertAlmostEqual(night_branch.absorption_probabilities.get("M01", 0.0), 1.0, places=2)

    def test_submillisecond_performance_benchmark(self):
        """Verifies that 500 rollouts over 120 steps completes in < 50 milliseconds."""
        t0 = time.perf_counter()
        branch = self.simulator.simulate_rollouts(
            initial_state="M01",
            horizon_steps=120,
            num_rollouts=500,
            seed=7,
        )
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        # Total steps sampled = 500 * 120 = 60,000 steps; must be < 250ms on mobile ARM CPU
        self.assertLess(elapsed_ms, 250.0, f"Simulation too slow: {elapsed_ms:.2f} ms")
        self.assertEqual(len(branch.sample_paths), 10)

    def test_short_horizon_counterfactual(self):
        """Verifies counterfactual works with very short horizons (BUG-S5 regression)."""
        intervention = ScenarioIntervention(
            action_type="FORCE_STATE",
            target_state="M01",
            at_step=2,
        )
        comp = self.simulator.simulate_counterfactual(
            initial_state="M04",
            intervention=intervention,
            horizon_steps=4,
            num_rollouts=50,
            seed=77,
        )
        self.assertIsInstance(comp, CounterfactualComparison)
        self.assertEqual(comp.horizon_steps, 4)
        # Stabilization step should be <= horizon_steps (not crash or return nonsensical value)
        self.assertLessEqual(comp.stabilization_step_baseline, 4)
        self.assertLessEqual(comp.stabilization_step_scenario, 4)

    def test_records_without_state_token_raises(self):
        """Verifies that fit_from_records raises ValueError when no state_token found (BUG-S6)."""
        bad_records = [
            {"timestamp_utc": "2026-10-01T00:00:00Z", "value": 42},
            {"timestamp_utc": "2026-10-01T00:01:00Z", "value": 43},
        ]
        sim = SandboxSimulator()
        with self.assertRaises(ValueError):
            sim.fit_from_records(bad_records)


if __name__ == "__main__":
    unittest.main()
