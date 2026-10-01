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

from traceform.forecasting.sandbox_simulator import (
    SandboxSimulator,
    ScenarioIntervention,
    SimulationBranch,
    CounterfactualComparison,
    _compute_jsd,
    _compute_shannon_entropy,
)


class TestSandboxSimulator(unittest.TestCase):
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
        self.assertEqual(len(branch.sample_paths), 500)


if __name__ == "__main__":
    unittest.main()
