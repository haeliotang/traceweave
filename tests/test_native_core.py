import ctypes
import datetime
import math
from pathlib import Path
import random
import shutil
import subprocess
import sys
import tempfile
import unittest

from traceform.forecasting.periodic_kernel import PeriodicTransitionKernel

from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode


class TestNativeCore(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("clang++") or shutil.which("c++")
        if not compiler:
            raise unittest.SkipTest("native C++ compiler unavailable")
        cls.directory = tempfile.TemporaryDirectory()
        root = Path(__file__).resolve().parents[1]
        library = Path(cls.directory.name) / "core.so"
        flags = []
        if sys.platform == "darwin":
            sdk = subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip()
            flags = ["-isystem", str(Path(sdk) / "usr/include/c++/v1")]
        subprocess.run([compiler, "-std=c++17", "-O3", "-shared", "-fPIC", *flags, str(root / "native/traceform_core.cpp"), "-o", str(library)], check=True, capture_output=True)
        cls.core = ctypes.CDLL(str(library))
        pointer = ctypes.POINTER(ctypes.c_double)
        cls.core.traceform_periodic_cdf.argtypes = [pointer, pointer, pointer, ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int), ctypes.c_size_t, ctypes.c_size_t, ctypes.c_size_t, pointer]
        cls.core.traceform_normalize.argtypes = [pointer, pointer, pointer, ctypes.c_size_t, pointer]
        cls.core.traceform_quantize.argtypes = [pointer, pointer, pointer, pointer, ctypes.c_size_t, ctypes.c_size_t, ctypes.c_double, pointer]
        cls.core.traceform_rollouts.argtypes = [pointer, ctypes.c_size_t, ctypes.c_size_t, ctypes.c_size_t, ctypes.c_size_t, pointer, ctypes.POINTER(ctypes.c_ulonglong)]
        cls.core.traceform_counterfactual.argtypes = [pointer, pointer, ctypes.c_size_t, ctypes.c_size_t,
            ctypes.c_size_t, ctypes.c_size_t, ctypes.c_size_t, pointer, pointer, ctypes.POINTER(ctypes.c_ulonglong)]

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def array(self, values):
        return (ctypes.c_double * len(values))(*values)

    def test_clamped_counterfactual_matches_python_scenario(self):
        from unittest.mock import patch
        from traceform.forecasting.sandbox_simulator import SandboxSimulator, ScenarioIntervention
        vocab = ['A', 'B', 'C']
        rng = random.Random(42)
        for probabilities in ([[0.95, 0.03, 0.02], [0.1, 0.85, 0.05], [0.2, 0.1, 0.7]],
                              [[1, 0, 0], [0, 1, 0], [0, 0, 1]]):
            matrix = {source: dict(zip(vocab, row)) for source, row in zip(vocab, probabilities)}
            simulator = SandboxSimulator().fit_from_transition_matrix(matrix, vocab)
            cdf = []
            for source, row in zip(vocab, probabilities):
                total, cumulative = 0, []
                for value in row:
                    total += value
                    cumulative.append(total)
                cumulative[-1] = 1
                cdf.extend(cumulative)
                # Native tables use vocabulary indexes; align the reference's sampling order.
                simulator._global_cdf[source] = (cumulative, vocab)
            for horizon in (1, 2, 30):
                for limit in (1, 3, 99):
                    runs = 40
                    draws = [rng.random() for _ in range(runs * (horizon - 1))]
                    class FixedRandom:
                        def __init__(self):
                            self.values = iter(draws)
                        def random(self):
                            return next(self.values)
                        def choice(self, values):
                            return values[int(self.random() * len(values))]
                    expected = []
                    for intervention in (None, ScenarioIntervention(action_type='CLAMP_DWELL', max_dwell_steps=limit)):
                        with patch('traceform.forecasting.sandbox_simulator.random.Random', return_value=FixedRandom()):
                            branch = simulator.simulate_rollouts('A', horizon, runs, intervention=intervention, max_sample_paths=0)
                        expected.extend(round(row.get(state, 0) * runs) for row in branch.state_occupancy_by_step for state in vocab)
                    output = (ctypes.c_ulonglong * (2 * horizon * len(vocab)))()
                    arguments = [self.array(cdf * horizon), self.array([value for row in probabilities for value in row]),
                                 len(vocab), horizon, runs, 0, limit, self.array(draws or [0]), self.array(draws or [0]), output]
                    self.assertEqual(self.core.traceform_counterfactual(*arguments), 0)
                    self.assertEqual(list(output), expected)
                    arguments[6] = 0
                    self.assertEqual(self.core.traceform_counterfactual(*arguments), -1)

    def test_counterfactual_decision_thresholds(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        harness = '''package org.traceform.edge;
public final class DecisionTest {
    public static void main(String[] args) {
        CounterfactualDecision.Result positive = CounterfactualDecision.compare(new long[]{100,0,100,0,100,0,0,100}, 2,2,100,new boolean[]{true,false});
        if (!positive.recommendation.equals("RECOMMEND_INTERVENTION") || positive.meanJsd != 0.5 || positive.peakJsd != 1 || positive.peakStep != 1 || positive.terminalRiskChange != -1) throw new AssertionError("positive scenario");
        CounterfactualDecision.Result exactFive = CounterfactualDecision.compare(new long[]{100,0,95,5},2,1,100,new boolean[]{true,false});
        if (exactFive.recommendation.equals("RECOMMEND_INTERVENTION")) throw new AssertionError("exact 5 percent threshold");
        CounterfactualDecision.Result neutral = CounterfactualDecision.compare(new long[]{50,50,50,50},2,1,100,new boolean[]{true,false});
        if (!neutral.recommendation.equals("NEUTRAL") || neutral.meanJsd != 0) throw new AssertionError("neutral");
        CounterfactualDecision.Result maintain = CounterfactualDecision.compare(new long[]{100,0,0,100},2,1,100,new boolean[]{false,false});
        if (!maintain.recommendation.equals("MAINTAIN_STATUS_QUO")) throw new AssertionError("unselected risk");
        boolean rejected = false;
        try { CounterfactualDecision.compare(new long[]{50,50,1,1},2,1,100,new boolean[]{true,false}); } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("invalid mass");
    }
}'''
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'DecisionTest.java'
            source.write_text(harness)
            subprocess.run(['javac', '-d', directory, str(root / 'android/src/org/traceform/edge/CounterfactualDecision.java'), str(source)], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.DecisionTest'], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr.decode())

    def test_normalization_128d_and_energy_parity(self):
        rng = random.Random(42)
        raw = [rng.uniform(-50, 50) for _ in range(128)]
        median, iqr = [0.2] * 128, [2.0] * 128
        output = self.array([0] * 128)
        self.assertEqual(self.core.traceform_normalize(self.array(raw), self.array(median), self.array(iqr), 128, output), 0)
        expected = [round(max(-10, min(10, (value - 0.2) / 2)), 5) for value in raw]
        self.assertEqual(list(output), expected)
        velocity = [rng.uniform(-1, 1) for _ in range(128)]
        nodes = [ManifoldNode(index, (0, index), str(index), [rng.random() for _ in range(128)], [rng.random() for _ in range(128)]) for index in range(16)]
        manifold = DynamicalEnergyManifold(kinetic_weight=0.35)
        energies = [manifold.compute_energy_distance(expected, velocity, node) for node in nodes]
        distance = ctypes.c_double()
        selected = self.core.traceform_quantize(output, self.array(velocity), self.array([value for node in nodes for value in node.center_x]), self.array([value for node in nodes for value in node.center_v]), 16, 128, 0.35, ctypes.byref(distance))
        self.assertEqual(selected, min(range(16), key=energies.__getitem__))
        self.assertAlmostEqual(distance.value, min(energies), places=9)

    def test_shared_draw_rollout_parity_and_invalid_cdf(self):
        import bisect
        horizon, runs, states = 20, 30, 2
        cdf = [0.8, 1, 0.3, 1] * horizon
        rng = random.Random(42)
        draws = [rng.random() for _ in range(runs * (horizon - 1))]
        occupancy = (ctypes.c_ulonglong * (horizon * states))()
        self.assertEqual(self.core.traceform_rollouts(self.array(cdf), states, horizon, runs, 0, self.array(draws), occupancy), 0)
        expected = [0] * (horizon * states)
        for run in range(runs):
            current = 0
            expected[0] += 1
            for step in range(1, horizon):
                current = bisect.bisect_right(cdf[(step * states + current) * states:(step * states + current + 1) * states], draws[run * (horizon - 1) + step - 1])
                expected[step * states + current] += 1
        self.assertEqual(list(occupancy), expected)
        cdf[0] = 2
        self.assertEqual(self.core.traceform_rollouts(self.array(cdf), states, horizon, runs, 0, self.array(draws), occupancy), -1)

    def test_rollout_search_matches_cdf_boundaries_plateaus_and_tail_clamp(self):
        import bisect
        for states in (15, 16, 17):
            for row in ([index / states for index in range(1, states + 1)],
                        [0.0] * (states // 2) + [0.5] * (states - states // 2 - 1) + [1.0],
                        [1.0 - 5e-10] * states):
                draws = [0.0, math.nextafter(1.0, 0.0)]
                for boundary in row:
                    for draw in (math.nextafter(boundary, 0.0), boundary, math.nextafter(boundary, 1.0)):
                        if 0 <= draw < 1:
                            draws.append(draw)
                cdf = row * (2 * states)
                occupancy = (ctypes.c_ulonglong * (2 * states))()
                self.assertEqual(self.core.traceform_rollouts(self.array(cdf), states, 2,
                                 len(draws), 0, self.array(draws), occupancy), 0)
                expected = [0] * (2 * states)
                expected[0] = len(draws)
                for draw in draws:
                    expected[states + min(states - 1, bisect.bisect_right(row, draw))] += 1
                self.assertEqual(list(occupancy), expected)

    def test_quantizer_odd_dimensions_zero_speed_ties_and_nonfinite_centers(self):
        for dimension in (1, 3, 127, 128):
            with self.subTest(dimension=dimension):
                position, velocity = [0.5] * dimension, [0.0] * dimension
                centers = [0.0] * dimension + [1.0] * dimension
                center_velocity = [0.0] * (dimension * 2)
                distance = ctypes.c_double()
                arguments = [self.array(position), self.array(velocity), self.array(centers), self.array(center_velocity), 2, dimension, 0.35, ctypes.byref(distance)]
                self.assertEqual(self.core.traceform_quantize(*arguments), 0)
                self.assertEqual(distance.value, dimension * 0.25)
                centers[-1] = float("nan")
                arguments[2] = self.array(centers)
                self.assertEqual(self.core.traceform_quantize(*arguments), -1)

    def test_normalization_odd_tail_and_degenerate_scales(self):
        raw, median, iqr = [20, -20, 1.234567], [0, 0, 0], [0, -1, 1]
        output = self.array([0] * 3)
        self.assertEqual(self.core.traceform_normalize(self.array(raw), self.array(median), self.array(iqr), 3, output), 0)
        self.assertEqual(list(output), [10, -10, 1.23457])
        iqr[-1] = float("inf")
        self.assertEqual(self.core.traceform_normalize(self.array(raw), self.array(median), self.array(iqr), 3, output), -1)

    def test_periodic_cdf_matches_python_across_timezones(self):
        records = []
        for day, target in [(5, 1), (10, 2)]:
            for minute in range(20):
                for second, token in [(0, 0), (1, target)]:
                    records.append({"timestamp_utc": f"2026-10-{day:02d}T06:{minute:02d}:{second:02d}Z", "state_token": token})
        prior = {0: {1: 0.5, 2: 0.5}, 1: {0: 1.0}, 2: {0: 1.0}}
        for zone in ["UTC", "Asia/Kathmandu", "America/New_York"]:
            kernel = PeriodicTransitionKernel(zone).fit(records, prior)
            observed, sources, targets = [], [], []
            for source, phases in kernel.counts.items():
                for phase, counts in phases.items():
                    for target, count in counts.items():
                        for _ in range(count):
                            observed.extend(phase); sources.append(source); targets.append(target)
            for text in ["2026-10-12T00:00:00+00:00", "2026-11-01T05:59:59+00:00"]:
                start = datetime.datetime.fromisoformat(text)
                instants = [start + datetime.timedelta(seconds=step * 30) for step in range(4)]
                queries = [value for instant in instants for value in kernel.phases(instant)]
                output = self.array([0] * (4 * 3 * 3))
                self.assertEqual(self.core.traceform_periodic_cdf(
                    self.array([0, 0.5, 0.5, 1, 0, 0, 1, 0, 0]), self.array(queries), self.array(observed),
                    (ctypes.c_int * len(sources))(*sources), (ctypes.c_int * len(targets))(*targets), len(sources), 3, 4, output), 0)
                expected = []
                for instant in instants:
                    for source in range(3):
                        row, cumulative = kernel.row(source, instant), 0
                        for target in range(3):
                            cumulative += row.get(target, 0)
                            expected.append(1.0 if target == 2 else cumulative)
                for actual, wanted in zip(output, expected):
                    self.assertAlmostEqual(actual, wanted, places=12)

    def test_periodic_cdf_rejects_invalid_inputs_and_supports_no_pairs(self):
        output = self.array([0] * 4)
        empty = (ctypes.c_int * 0)()
        args = [self.array([1, 0, 0, 1]), self.array([0, 0]), self.array([]), empty, empty, 0, 2, 1, output]
        self.assertEqual(self.core.traceform_periodic_cdf(*args), 0)
        self.assertEqual(list(output), [1, 1, 0, 1])
        args[1] = self.array([float("nan"), 0])
        self.assertEqual(self.core.traceform_periodic_cdf(*args), -1)
        args[1] = self.array([0, 0]); args[0] = self.array([0, 0, 0, 1])
        self.assertEqual(self.core.traceform_periodic_cdf(*args), -1)
        args[0] = self.array([1, 0, 0, 1]); args[5] = 1
        args[2] = self.array([0, 0]); args[3] = (ctypes.c_int * 1)(-1); args[4] = (ctypes.c_int * 1)(0)
        self.assertEqual(self.core.traceform_periodic_cdf(*args), -1)
        args[3] = (ctypes.c_int * 1)(0); args[2] = self.array([float("inf"), 0])
        self.assertEqual(self.core.traceform_periodic_cdf(*args), -1)
        args[1] = self.array([1e308, 0]); args[2] = self.array([-1e308, 0])
        self.assertEqual(self.core.traceform_periodic_cdf(*args), -1)

    def test_periodic_cdf_zero_probability_tails_remain_monotone(self):
        states, horizon, pairs = 16, 120, 78
        sources = [pair % states for pair in range(pairs)]
        targets = [(pair + pair // states + 1) % states for pair in range(pairs)]
        prior = [0.0] * (states * states)
        for source, target in zip(sources, targets):
            prior[source * states + target] += 1
        for source in range(states):
            total = sum(prior[source * states:(source + 1) * states])
            for target in range(states):
                prior[source * states + target] /= total
        observed = [angle for pair in range(pairs) for angle in [2 * math.pi * ((pair * 30 / 86400) % 1), 2 * math.pi * ((pair * 30 / (7 * 86400)) % 1)]]
        queries = [angle for step in range(horizon) for angle in [2 * math.pi * (0.25 + step * 30 / 86400), 2 * math.pi * (0.25 + step * 30 / 86400) / 7]]
        output = self.array([0] * (horizon * states * states))
        self.assertEqual(self.core.traceform_periodic_cdf(self.array(prior), self.array(queries), self.array(observed),
            (ctypes.c_int * pairs)(*sources), (ctypes.c_int * pairs)(*targets), pairs, states, horizon, output), 0)
        for offset in range(0, len(output), states):
            row = list(output[offset:offset + states])
            self.assertLessEqual(max(row), 1.0)
            self.assertEqual(row, sorted(row))
            self.assertEqual(row[-1], 1.0)

    def test_periodic_cdf_random_and_large_phases_match_direct_formula(self):
        rng = random.Random(20261001)
        states, horizon, pairs = 7, 17, 257
        prior = []
        for source in range(states):
            row = [rng.random() if target != source else 0 for target in range(states)]
            total = sum(row)
            prior.extend(value / total for value in row)
        sources = [rng.randrange(states) for _ in range(pairs)]
        targets = [rng.randrange(states) for _ in range(pairs)]
        for scale in (2 * math.pi, 32.0, 1e16, 1e308):
            with self.subTest(scale=scale):
                # Same-sign extreme phases remain finite after subtraction.
                observed = [rng.random() * scale for _ in range(pairs * 2)]
                queries = [rng.random() * scale for _ in range(horizon * 2)]
                output = self.array([0] * (horizon * states * states))
                self.assertEqual(self.core.traceform_periodic_cdf(
                    self.array(prior), self.array(queries), self.array(observed),
                    (ctypes.c_int * pairs)(*sources), (ctypes.c_int * pairs)(*targets),
                    pairs, states, horizon, output), 0)
                expected = []
                for step in range(horizon):
                    matrix = [5 * value for value in prior]
                    for pair, (source, target) in enumerate(zip(sources, targets)):
                        day_delta = queries[step * 2] - observed[pair * 2]
                        week_delta = queries[step * 2 + 1] - observed[pair * 2 + 1]
                        matrix[source * states + target] += math.exp(
                            2 * (math.cos(day_delta) - 1) + 2 * (math.cos(week_delta) - 1))
                    for source in range(states):
                        row = matrix[source * states:(source + 1) * states]
                        total, cumulative = sum(row), 0
                        for target, value in enumerate(row):
                            cumulative += value / total
                            expected.append(1.0 if target == states - 1 else min(1.0, cumulative))
                self.assertLessEqual(max(abs(actual - wanted) for actual, wanted in zip(output, expected)), 1e-12)
