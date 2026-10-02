import datetime
import math
import random
import unittest
from unittest import mock

from traceform.dynamics.continuous import ContinuousDynamics, cholesky_psd


class TestContinuousDynamics(unittest.TestCase):
    def test_additive_stochastic_step_refinement_with_shared_brownian_paths(self):
        class OrnsteinUhlenbeck(ContinuousDynamics):
            def coefficients(self, vector):
                return [-vector[0]], [[0.25]]

        model = OrnsteinUhlenbeck()
        model.dimension = 1
        runs, fine_steps = 256, 256
        fine_dt = 1 / fine_steps
        rng = random.Random(814)
        increments, exact = [], []
        # Couple the exact OU integral to each Brownian increment, including
        # the independent Brownian bridge component of that same interval.
        covariance = -math.expm1(-fine_dt)
        variance = -math.expm1(-2 * fine_dt) / 2
        bridge_scale = math.sqrt(variance - covariance ** 2 / fine_dt)
        for _ in range(runs):
            value, path = 1.0, []
            for _ in range(fine_steps):
                normal, bridge = rng.gauss(0, 1), rng.gauss(0, 1)
                path.append(math.sqrt(fine_dt) * normal)
                integral = covariance / math.sqrt(fine_dt) * normal + bridge_scale * bridge
                value = math.exp(-fine_dt) * value + 0.5 * integral
            increments.append(path)
            exact.append(value)
        errors = []
        for steps in (4, 8, 16, 32):
            width = fine_steps // steps
            dt = 1 / steps
            normals = iter(sum(path[start:start + width]) / math.sqrt(dt)
                           for path in increments for start in range(0, fine_steps, width))
            terminals = []
            with mock.patch('traceform.dynamics.continuous.random.Random') as generator:
                generator.return_value.gauss.side_effect = lambda mean, scale: next(normals)
                model.rollout([1], 1, dt_seconds=dt, num_rollouts=runs,
                              observer=lambda run, step, time, vector:
                              terminals.append(vector[0]) if time == 1 else None)
            self.assertEqual(len(terminals), runs)
            errors.append(math.sqrt(sum((actual - reference) ** 2
                                        for actual, reference in zip(terminals, exact)) / runs))
        for coarse, fine in zip(errors, errors[1:]):
            self.assertGreater(coarse / fine, 1.8)
            self.assertLess(coarse / fine, 2.3)
        self.assertLess(errors[-1], 0.01)

    def test_white_noise_rollout_rejects_nonfinite_time_parameters(self):
        model = ContinuousDynamics()
        model.dimension = 1
        model.anchors = [{"center": [0], "drift": [1], "noise_covariance": [[0]]}]
        for duration, step in ((float('nan'), 1), (float('inf'), 1),
                               (1, float('nan')), (1, float('inf'))):
            with self.subTest(duration=duration, step=step):
                with self.assertRaisesRegex(ValueError, 'finite'):
                    model.rollout([0], duration, step, num_rollouts=1)

    def test_smoothing_bandwidth_rejects_nonfinite_values(self):
        for bandwidth in (float('nan'), float('inf'), -float('inf'), 0, -1, 1e-300, 1e300):
            with self.subTest(bandwidth=bandwidth):
                with self.assertRaisesRegex(ValueError, 'bandwidth'):
                    ContinuousDynamics(bandwidth=bandwidth)

    def test_rollout_observer_sees_every_run_and_actual_step_time(self):
        model = ContinuousDynamics()
        model.dimension = 1
        model.anchors = [{"center": [0], "drift": [1], "noise_covariance": [[0]]}]
        for rollout in (model.rollout, model.rollout_fractional):
            seen = []
            result = rollout([0], 2.5, num_rollouts=7,
                             observer=lambda run, step, time, vector: seen.append((run, step, time, list(vector))))
            self.assertEqual(len(seen), 28)
            for run in range(7):
                self.assertEqual([row[2] for row in seen if row[0] == run], [0, 1, 2, 2.5])
            self.assertEqual(seen[-1], (6, 3, 2.5, [2.5]))
            self.assertEqual(len(result['sample_paths']), 5)

    def test_full_128d_fit_and_potential_use_actual_irregular_intervals(self):
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        velocity = [(index - 64) / 64 for index in range(128)]
        records = [{"timestamp_utc": (start + datetime.timedelta(seconds=seconds)).isoformat(),
                    "continuous_vector": [value * seconds for value in velocity],
                    "state_token": "A", "model_version": "128d-fixture",
                    "normalizer_sha256": '1' * 64}
                   for seconds in (0, 1, 3, 6, 10)]
        model = ContinuousDynamics(bandwidth=100).fit(records, max_anchors=4)
        drift, covariance = model.coefficients([0] * 128)
        self.assertEqual(model.dimension, 128)
        for actual, expected in zip(drift, velocity):
            self.assertAlmostEqual(actual, expected, places=12)
        self.assertLess(max(abs(value) for row in covariance for value in row), 1e-20)
        potential = model.infer_potential()
        for center, energy in zip(potential['centers'], potential['relative_energy']):
            self.assertAlmostEqual(energy, -sum(a * b for a, b in zip(center, velocity)), places=10)
        self.assertLess(potential['edge_residual_rms'], 1e-10)
        simulation = model.rollout([0] * 128, 2, num_rollouts=1)
        for actual, expected in zip(simulation['terminal_mean'], velocity):
            self.assertAlmostEqual(actual, 2 * expected, places=10)

    def test_full_128d_irregular_increment_covariance_recovers_known_cross_terms(self):
        dimension = 128
        drift = [(index - 64) / 6400 for index in range(dimension)]
        vector, seconds = [0.0] * dimension, 0.0
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        records = []

        def record():
            records.append({'timestamp_utc': (start + datetime.timedelta(seconds=seconds)).isoformat(),
                            'continuous_vector': list(vector), 'state_token': 'A',
                            'model_version': 'controlled-moment-fixture',
                            'model_sha256': '1' * 64, 'normalizer_sha256': '2' * 64})

        record()
        # Signed Hadamard rows have exactly zero mean and identity second
        # moment. L has diagonal .75 and subdiagonal .25, so L L^T is known.
        # These controlled increments validate moments, not Gaussianity.
        for dt in (0.5, 1.5):
            for row in range(dimension):
                noise = [(-1.0 if (row & column).bit_count() % 2 else 1.0)
                         for column in range(dimension)]
                correlated = [0.75 * noise[index] + (0.25 * noise[index - 1] if index else 0)
                              for index in range(dimension)]
                for sign in (1, -1):
                    vector = [value + velocity * dt + sign * math.sqrt(dt) * increment
                              for value, velocity, increment in zip(vector, drift, correlated)]
                    seconds += dt
                    record()
        model = ContinuousDynamics(bandwidth=1e8).fit(records, max_anchors=1)
        actual_drift, covariance = model.coefficients([0.0] * dimension)
        self.assertEqual(model.fit_pair_count, 512)
        for actual, expected in zip(actual_drift, drift):
            self.assertAlmostEqual(actual, expected, places=9)
        for row in range(dimension):
            for column in range(dimension):
                expected = (0.5625 if row == 0 else 0.625) if row == column else 0.1875 if abs(row - column) == 1 else 0.0
                self.assertAlmostEqual(covariance[row][column], expected, places=9)
        self.assertEqual(model.to_dict()['diffusion_convention'], 'noise_covariance=2D')
        cholesky_psd(covariance)

    def test_full_128d_fractional_driver_preserves_spatial_correlation(self):
        model = ContinuousDynamics()
        model.dimension = 128
        model.anchors = [{"center": [0] * 128, "drift": [0] * 128,
                          "noise_covariance": [[2] * 128 for _ in range(128)]}]
        result = model.rollout_fractional([0] * 128, 4, hurst=.25, num_rollouts=300)
        self.assertAlmostEqual(result['terminal_variance'][0], 4, delta=.7)
        for path in result['sample_paths']:
            for vector in path:
                self.assertEqual(len(vector), 128)
                for value in vector:
                    self.assertAlmostEqual(value, vector[0], places=6)
        for value in result['terminal_variance']:
            self.assertAlmostEqual(value, result['terminal_variance'][0], places=6)

    def test_memory_diagnostics_reject_foreign_coordinate_system(self):
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        records = [{"timestamp_utc": (start + datetime.timedelta(seconds=index)).isoformat(),
                    "continuous_vector": [float(index % 7)], "model_version": "v1", "state_token": "A",
                    "normalizer_sha256": '1' * 64} for index in range(65)]
        model = ContinuousDynamics().fit(records[:4])
        for record in records:
            record['normalizer_sha256'] = '2' * 64
        for diagnostic in (model.estimate_memory, model.estimate_memory_irregular):
            with self.assertRaisesRegex(ValueError, 'coordinate provenance'):
                diagnostic(records)

    def test_fit_rejects_mixed_coordinate_provenance(self):
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        for field in ('normalizer_sha256', 'model_sha256'):
            records = [{"timestamp_utc": (start + datetime.timedelta(seconds=index)).isoformat(),
                        "continuous_vector": [float(index)], "state_token": "A", "model_version": "v1",
                        field: ('1' if index < 3 else '2') * 64}
                       for index in range(6)]
            with self.assertRaisesRegex(ValueError, 'provenance'):
                ContinuousDynamics().fit(records)

    def test_irregular_memory_likelihood_recovers_fractional_driver(self):
        model = ContinuousDynamics()
        model.dimension = 1
        model.model_version = "v1"
        model.anchors = [{"center": [0], "drift": [0], "noise_covariance": [[1]]}]
        times = [0]
        for index in range(128):
            times.append(times[-1] + [0.5, 1, 1.5][index % 3])
        lower = cholesky_psd(model.fractional_increment_covariance(times, 0.25))
        rng = random.Random(71)
        noise = [rng.gauss(0, 1) for _ in lower]
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        records = []
        value = 0
        for index, time in enumerate(times):
            if index:
                value += sum(lower[index - 1][column] * noise[column] for column in range(index))
            records.append({"timestamp_utc": (start + datetime.timedelta(seconds=time)).isoformat(),
                            "continuous_vector": [value], "model_version": "v1"})
        estimate = model.estimate_memory_irregular(records)
        self.assertAlmostEqual(estimate["hurst"], 0.25, delta=0.15)
        self.assertEqual(estimate["increment_count"], 128)
        self.assertGreater(estimate["profile_scale_variance"], 0)
        brownian = next(row for row in estimate["profile"] if row["hurst"] == 0.5)
        optimum = min(row["negative_log_likelihood_up_to_constant"] for row in estimate["profile"])
        self.assertAlmostEqual(estimate["log_likelihood_gain_over_brownian"],
                               brownian["negative_log_likelihood_up_to_constant"] - optimum)
        records[40]["continuous_from_previous"] = False
        with self.assertRaises(ValueError):
            model.estimate_memory_irregular(records)

    def test_brownian_null_calibration_uses_time_grid_and_profiles_scale(self):
        model = ContinuousDynamics()
        model.dimension = 1
        model.model_version = "v1"
        model.anchors = [{"center": [0], "drift": [0], "noise_covariance": [[1]]}]
        rng = random.Random(71)
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        records, time, value = [], 0.0, 0.0
        for index in range(33):
            if index:
                dt = [0.5, 1, 1.5][index % 3]
                time += dt
                value += rng.gauss(0, math.sqrt(dt))
            records.append({"timestamp_utc": (start + datetime.timedelta(seconds=time)).isoformat(),
                            "continuous_vector": [value], "model_version": "v1"})
        original = model.estimate_memory_irregular(records)
        calibrated = model.estimate_memory_irregular(records, brownian_bootstrap_draws=19, seed=12)
        self.assertEqual(original["profile"], calibrated["profile"])
        null = calibrated["brownian_null_calibration"]
        self.assertEqual(len(null["null_likelihood_gains"]), 19)
        self.assertEqual(null["monte_carlo_p_value"], (1 + null["exceedance_count"]) / 20)
        self.assertGreaterEqual(null["monte_carlo_p_value"], 0.05)
        self.assertEqual(calibrated, model.estimate_memory_irregular(records, brownian_bootstrap_draws=19, seed=12))
        scaled = [dict(record, continuous_vector=[record["continuous_vector"][0] * 7]) for record in records]
        rescaled = model.estimate_memory_irregular(scaled, brownian_bootstrap_draws=19, seed=12)
        self.assertAlmostEqual(calibrated["log_likelihood_gain_over_brownian"], rescaled["log_likelihood_gain_over_brownian"])
        self.assertEqual(null["monte_carlo_p_value"], rescaled["brownian_null_calibration"]["monte_carlo_p_value"])
        for invalid in (-1, 1000, True, 1.5):
            with self.assertRaises(ValueError):
                model.estimate_memory_irregular(records, brownian_bootstrap_draws=invalid)

    def test_memory_estimation_uses_drift_residuals_and_rejects_gaps(self):
        model = ContinuousDynamics()
        model.dimension = 1
        model.model_version = "v1"
        model.anchors = [{"center": [0], "drift": [0], "noise_covariance": [[1]]}]
        simulation = model.rollout_fractional([0], 256, hurst=0.25, num_rollouts=1, seed=7)
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        records = [{"timestamp_utc": (start + datetime.timedelta(seconds=index)).isoformat(),
                    "continuous_vector": vector, "model_version": "v1"}
                   for index, vector in enumerate(simulation["sample_paths"][0])]
        estimate = model.estimate_memory(records)
        self.assertAlmostEqual(estimate["hurst"], 0.25, delta=0.15)
        self.assertEqual(estimate["interval_seconds"], 1)
        self.assertEqual(estimate["residual_count"], 256)
        records[50]["continuous_from_previous"] = False
        with self.assertRaises(ValueError):
            model.estimate_memory(records)
        records[50].pop("continuous_from_previous")
        records[50]["timestamp_utc"] = (start + datetime.timedelta(seconds=50.2)).isoformat()
        with self.assertRaises(ValueError):
            model.estimate_memory(records)

    def test_memory_estimation_rejects_unversioned_input(self):
        model = ContinuousDynamics()
        model.dimension = 1
        model.anchors = [{"center": [0], "drift": [0], "noise_covariance": [[1]]}]
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        records = [{"timestamp_utc": (start + datetime.timedelta(seconds=index)).isoformat(),
                    "continuous_vector": [float((index * index) % 17)]}
                   for index in range(65)]
        with self.assertRaises(ValueError):
            model.estimate_memory(records)

    def test_archived_potential_rejects_unrepresentable_finite_inputs(self):
        from traceform.dynamics.continuous import evaluate_graph_potential, graph_potential_curvature
        base = {"protocol": "traceform.graph-potential.v1", "centers": [[-1], [1]],
                "relative_energy": [0, 1], "bandwidth": 0.7, "mobility": 1}
        cases = [dict(base, bandwidth=1e-300), dict(base, bandwidth=1e300),
                 dict(base, centers=[[-1e300], [1e300]]),
                 dict(base, relative_energy=[-1e308, 1e308]),
                 dict(base, relative_energy=[0, 1e100], mobility=1e308)]
        for potential in cases:
            for evaluate in (evaluate_graph_potential, graph_potential_curvature):
                with self.subTest(potential=potential, evaluate=evaluate.__name__):
                    with self.assertRaisesRegex(ValueError, 'finite|representable'):
                        vector = [0.0] if potential['relative_energy'][0] < 0 else [0.2]
                        evaluate(vector, potential)

    def test_archived_potential_curvature_matches_gradient_derivative(self):
        from traceform.dynamics.continuous import evaluate_graph_potential, graph_potential_curvature
        rng = random.Random(20261001)
        for dimension in (1, 3, 128):
            with self.subTest(dimension=dimension):
                potential = {"protocol": "traceform.graph-potential.v1", "bandwidth": 0.7, "mobility": 0.4,
                             "centers": [[rng.uniform(-0.2, 0.2) for _ in range(dimension)] for _ in range(8)],
                             "relative_energy": [rng.uniform(-2, 2) for _ in range(8)]}
                vector = [rng.uniform(-0.1, 0.1) for _ in range(dimension)]
                result = graph_potential_curvature(vector, potential)
                epsilon = 1e-5
                for column in {0, dimension // 2, dimension - 1}:
                    left, right = list(vector), list(vector)
                    left[column] -= epsilon; right[column] += epsilon
                    before = evaluate_graph_potential(left, potential)
                    after = evaluate_graph_potential(right, potential)
                    for row in range(dimension):
                        derivative = (after['gradient'][row] - before['gradient'][row]) / (2 * epsilon)
                        self.assertAlmostEqual(result['hessian'][row][column], derivative, places=8)
                        self.assertEqual(result['hessian'][row][column], result['hessian'][column][row])
                        self.assertAlmostEqual(result['conservative_drift_jacobian'][row][column], -0.4 * derivative, places=8)
                shifted = dict(potential, relative_energy=[value + 9 for value in potential['relative_energy']])
                other = graph_potential_curvature(vector, shifted)
                for actual, expected in zip(result['hessian'], other['hessian']):
                    for first, second in zip(actual, expected):
                        self.assertAlmostEqual(first, second, places=12)

    def test_continuous_potential_gradient_matches_numeric_derivative(self):
        model = ContinuousDynamics(bandwidth=0.7)
        model.dimension = 2
        model.anchors = [{"center": [x, y], "drift": [-x, -2 * y],
                          "noise_covariance": [[1, 0], [0, 1]]}
                         for x, y in [(0, 0), (1, 0), (0, 1), (1, 1)]]
        potential = model.infer_potential()
        vector = [0.3, 0.4]
        with self.assertRaisesRegex(ValueError, "bandwidth"):
            model.potential_at(vector, dict(potential, bandwidth=1.0))
        result = model.potential_at(vector, potential)
        epsilon = 1e-5
        for index in range(2):
            left, right = list(vector), list(vector)
            left[index] -= epsilon
            right[index] += epsilon
            numeric = (model.potential_at(right, potential)["energy"]
                       - model.potential_at(left, potential)["energy"]) / (2 * epsilon)
            self.assertAlmostEqual(result["gradient"][index], numeric, places=8)
            self.assertAlmostEqual(result["conservative_drift"][index]
                                   + result["residual_drift"][index],
                                   result["empirical_drift"][index])
        shifted = dict(potential, relative_energy=[value + 9 for value in potential["relative_energy"]])
        shift = model.potential_at(vector, shifted)
        self.assertAlmostEqual(shift["energy"], result["energy"] + 9)
        for actual, expected in zip(shift["gradient"], result["gradient"]):
            self.assertAlmostEqual(actual, expected)
        for invalid in [[0], [0, float("nan")]]:
            with self.assertRaises(ValueError):
                model.potential_at(invalid, potential)

    def test_potential_recovers_quadratic_and_reports_rotation(self):
        model = ContinuousDynamics()
        model.dimension = 1
        model.anchors = [{"center": [x], "drift": [-x], "noise_covariance": [[1]]}
                         for x in [0, 1, 2]]
        potential = model.infer_potential()
        self.assertEqual(potential["relative_energy"], [0, 0.5, 2])
        self.assertAlmostEqual(potential["edge_residual_rms"], 0)
        model.dimension = 2
        model.anchors = [{"center": [x, y], "drift": [-y, x],
                          "noise_covariance": [[1, 0], [0, 1]]}
                         for x, y in [(1, 0), (0, 1), (-1, 0), (0, -1)]]
        rotation = model.infer_potential()
        self.assertEqual(rotation["relative_energy"], [0, 0, 0, 0])
        self.assertGreater(rotation["edge_residual_rms"], 0.5)
        self.assertAlmostEqual(rotation["conservative_fraction"], 0)
        divergence = [0.0] * 4
        for edge in rotation["edges"]:
            divergence[edge["source"]] -= edge["residual"]
            divergence[edge["target"]] += edge["residual"]
        for value in divergence:
            self.assertAlmostEqual(value, 0)

    def test_potential_requires_finite_fitted_anchors_and_mobility(self):
        model = ContinuousDynamics()
        with self.assertRaises(ValueError):
            model.infer_potential()
        model.dimension = 1
        model.anchors = [{"center": [0], "drift": [0]}, {"center": [1], "drift": [-1]}]
        self.assertEqual(model.infer_potential(mobility=2)["relative_energy"], [0, 0.25])
        for mobility in [0, -1, float("nan"), float("inf")]:
            with self.assertRaises(ValueError):
                model.infer_potential(mobility)
        model.anchors[1]["drift"] = [float("nan")]
        with self.assertRaises(ValueError):
            model.infer_potential()

    def test_fractional_additive_variance_and_memory(self):
        model = ContinuousDynamics()
        model.dimension = 1
        model.anchors = [{"center": [0], "drift": [0], "noise_covariance": [[2]]}]
        report = model.rollout_fractional([0], 4, hurst=0.25, num_rollouts=4000)
        self.assertAlmostEqual(report["terminal_variance"][0], 4, delta=0.3)
        covariance = model.fractional_increment_covariance([0, 1, 2, 2.5], 0.25)
        self.assertAlmostEqual(covariance[0][1], (2 ** 0.5 - 2) / 2)
        self.assertAlmostEqual(covariance[2][2], 0.5 ** 0.5)
        brownian = model.fractional_increment_covariance([0, 1, 2, 2.5], 0.5)
        self.assertEqual(brownian, [[1, 0, 0], [0, 1, 0], [0, 0, 0.5]])

    def test_fractional_invalid_grid_and_hurst(self):
        model = ContinuousDynamics()
        for times, hurst in [([0, 1], 0), ([0, 1], 1), ([0, 1], float("nan")),
                             ([0, 1, 1], 0.25), ([0, float("inf")], 0.25)]:
            with self.assertRaises(ValueError):
                model.fractional_increment_covariance(times, hurst)

    def test_actual_intervals_determine_drift(self):
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        records = [{"timestamp_utc": (start + datetime.timedelta(seconds=seconds)).isoformat(),
                    "continuous_vector": [seconds * 2.0, seconds * -1.0], "state_token": "A", "model_version": "v1"}
                   for seconds in [0, 1, 3, 6]]
        model = ContinuousDynamics(bandwidth=100).fit(records)
        drift, covariance = model.coefficients([0, 0])
        self.assertAlmostEqual(drift[0], 2.0)
        self.assertAlmostEqual(drift[1], -1.0)
        self.assertLess(covariance[0][0], 1e-20)
        first = model.rollout([0, 0], 3, dt_seconds=1, num_rollouts=2)
        refined = model.rollout([0, 0], 3, dt_seconds=0.25, num_rollouts=2)
        self.assertAlmostEqual(first["terminal_mean"][0], 6)
        self.assertAlmostEqual(refined["terminal_mean"][0], 6)

    def test_nonlinear_drift_step_refinement_matches_analytic_solution(self):
        model = ContinuousDynamics(bandwidth=1)
        model.dimension = 1
        model.anchors = [{"center": [-1], "drift": [1], "noise_covariance": [[0]]},
                         {"center": [1], "drift": [-1], "noise_covariance": [[0]]}]
        # Gaussian interpolation gives dx/dt=-tanh(x); sinh(x(t))=sinh(x(0))*exp(-t).
        expected = math.asinh(math.sinh(1) * math.exp(-2))
        errors = []
        for step in (0.25, 0.125, 0.0625, 0.03125):
            report = model.rollout([1], 2, dt_seconds=step, num_rollouts=1)
            errors.append(abs(report['terminal_mean'][0] - expected))
        for coarse, fine in zip(errors, errors[1:]):
            self.assertGreater(coarse / fine, 1.8)
            self.assertLess(coarse / fine, 2.2)
        self.assertLess(errors[-1], 0.005)

    def test_brownian_variance_and_correlated_diffusion(self):
        model = ContinuousDynamics()
        model.dimension = 2
        model.anchors = [{"center": [0, 0], "drift": [0, 0], "noise_covariance": [[2, 2], [2, 2]]}]
        for dt in [1.0, 0.5]:
            report = model.rollout([0, 0], 2, dt_seconds=dt, num_rollouts=3000)
            self.assertAlmostEqual(report["terminal_variance"][0], 4.0, delta=0.3)
            self.assertAlmostEqual(report["terminal_variance"][0], report["terminal_variance"][1])
        with self.assertRaises(ValueError):
            cholesky_psd([[1, 2], [2, 1]])

    def test_no_discrete_only_fit(self):
        with self.assertRaises(ValueError):
            ContinuousDynamics().fit([])

    def test_cross_version_and_missing_provenance_rejected(self):
        start = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
        records = [{"timestamp_utc": (start + datetime.timedelta(seconds=index)).isoformat(),
                    "continuous_vector": [float(index)], "state_token": "A"}
                   for index in range(6)]
        with self.assertRaises(ValueError):
            ContinuousDynamics().fit(records)
        for index, record in enumerate(records):
            record["model_version"] = "v1" if index < 3 else "v2"
        with self.assertRaises(ValueError):
            ContinuousDynamics().fit(records)
