# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Local empirical Ito drift/diffusion and additive fractional-noise simulation.

No conservative potential or empirically identified fractional memory is claimed.
"""

import copy
import math
import random

from traceform.forecasting.evaluation import adjacent_examples, timestamp
from traceform.forecasting.continuity import same_stream_segment


def cholesky_psd(matrix):
    dimension = len(matrix)
    lower = [[0.0] * dimension for _ in range(dimension)]
    for row in range(dimension):
        for column in range(row + 1):
            residual = matrix[row][column] - sum(lower[row][index] * lower[column][index] for index in range(column))
            if row == column:
                if residual < -1e-8:
                    raise ValueError("diffusion covariance must be positive semidefinite")
                lower[row][column] = math.sqrt(max(0.0, residual))
            elif lower[column][column] > 1e-12:
                lower[row][column] = residual / lower[column][column]
            elif abs(residual) > 1e-8:
                raise ValueError("singular covariance has inconsistent off-diagonal entries")
    return lower


def _graph_potential_weights(vector, potential):
    """Validate an archived Gaussian potential and recover its query weights."""
    centers, energies = potential.get("centers", []), potential.get("relative_energy", [])
    bandwidth, mobility = potential.get("bandwidth"), potential.get("mobility")
    if (potential.get("protocol") != "traceform.graph-potential.v1"
            or not 2 <= len(centers) <= 16 or len(energies) != len(centers)
            or not 1 <= len(vector) <= 128
            or any(len(center) != len(vector) for center in centers)
            or any(not math.isfinite(value) for center in centers for value in center)
            or any(not math.isfinite(value) for value in energies)
            or any(not math.isfinite(value) for value in vector)
            or any(not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0
                   for value in (bandwidth, mobility))):
        raise ValueError("finite dimension-matched archived potential and positive parameters required")
    variance = bandwidth * bandwidth
    if variance == 0 or not math.isfinite(2 * variance):
        raise ValueError("representable positive bandwidth square required")
    log_weights = [-sum((left - right) * (left - right) for left, right in zip(vector, center))
                   / (2 * variance) for center in centers]
    if any(not math.isfinite(value) for value in log_weights):
        raise ValueError("finite scaled distances required")
    peak = max(log_weights)
    weights = [math.exp(value - peak) for value in log_weights]
    total = sum(weights)
    weights = [weight / total for weight in weights]
    energy = sum(weight * value for weight, value in zip(weights, energies))
    return centers, energies, bandwidth, mobility, weights, energy


def evaluate_graph_potential(vector, potential):
    """Reconstruct the archived Gaussian potential without empirical dynamics."""
    centers, energies, bandwidth, mobility, weights, energy = _graph_potential_weights(vector, potential)
    gradient = [sum(weight * (value - energy) * (center[index] - vector[index])
                    / bandwidth ** 2 for weight, value, center in zip(weights, energies, centers))
                for index in range(len(vector))]
    conservative = [-mobility * value for value in gradient]
    if not all(math.isfinite(value) for value in [energy, *gradient, *conservative]):
        raise ValueError("finite potential energy, gradient and drift required")
    return {"energy": energy, "gradient": gradient,
            "conservative_drift": conservative,
            "evidence_scope": "archived_smoothed_graph_potential_not_empirical_drift_or_bifurcation"}


def graph_potential_curvature(vector, potential):
    """Exact Hessian and conservative-drift Jacobian of the archived smoothing."""
    centers, energies, bandwidth, mobility, weights, energy = _graph_potential_weights(vector, potential)
    dimension = len(vector)
    mean_center = [sum(weight * center[index] for weight, center in zip(weights, centers))
                   for index in range(dimension)]
    centered = [[(value - mean_center[index]) / bandwidth ** 2
                 for index, value in enumerate(center)] for center in centers]
    hessian = [[0.0] * dimension for _ in range(dimension)]
    for row in range(dimension):
        for column in range(row + 1):
            value = sum(weight * (anchor_energy - energy) * slope[row] * slope[column]
                        for weight, anchor_energy, slope in zip(weights, energies, centered))
            hessian[row][column] = hessian[column][row] = value
    jacobian = [[-mobility * value for value in row] for row in hessian]
    if any(not math.isfinite(value) for matrix in (hessian, jacobian) for row in matrix for value in row):
        raise ValueError("finite potential curvature and drift Jacobian required")
    return {"hessian": hessian,
            "conservative_drift_jacobian": jacobian,
            "evidence_scope": "archived_smoothed_potential_curvature_not_equilibrium_or_bifurcation_identification"}


class ContinuousDynamics:
    def __init__(self, bandwidth=1.0):
        if (not math.isfinite(bandwidth) or bandwidth <= 0
                or bandwidth * bandwidth == 0 or not math.isfinite(2 * bandwidth * bandwidth)):
            raise ValueError("bandwidth must be positive")
        self.bandwidth = bandwidth
        self.anchors = []
        self.dimension = 0
        self.model_version = None
        self.fit_pair_count = 0
        self.coordinate_provenance = {}
        self._empirical_noise = None

    def fit(self, records, max_gap_seconds=300.0, max_anchors=16):
        if max_anchors < 1:
            raise ValueError("max_anchors must be positive")
        self._empirical_noise = None
        pairs = []
        versions = set()
        provenance = set()
        for first, second, _ in adjacent_examples(records, max_gap_seconds):
            start, end = first.get("continuous_vector"), second.get("continuous_vector")
            if not start or not end or len(start) != len(end):
                continue
            if not all(math.isfinite(value) for value in start + end):
                continue
            versions.add(first.get("model_version"))
            for record in (first, second):
                provenance.add((record.get("model_sha256"), record.get("normalizer_sha256")))
            dt = (timestamp(second) - timestamp(first)).total_seconds()
            pairs.append((start, [right - left for left, right in zip(start, end)], dt))
        if len(pairs) < 2:
            raise ValueError("at least two continuous adjacent pairs required")
        if len(versions) != 1:
            raise ValueError("fit requires one representation version")
        if None in versions:
            raise ValueError("continuous fitting requires representation provenance")
        if len(provenance) != 1:
            raise ValueError("fit requires one coordinate provenance")
        dimension = len(pairs[0][0])
        if any(len(pair[0]) != dimension for pair in pairs):
            raise ValueError("inconsistent vector dimensions")
        self.dimension = dimension
        self.model_version = next(iter(versions))
        model_digest, normalizer_digest = next(iter(provenance))
        self.coordinate_provenance = {"model_sha256": model_digest, "normalizer_sha256": normalizer_digest}
        self.fit_pair_count = len(pairs)
        self.anchors = []
        scales = []
        stride = max(1, math.ceil(len(pairs) / max_anchors))
        for center, _, _ in pairs[::stride]:
            log_weights = [-sum((a - b) ** 2 for a, b in zip(center, start)) / (2 * self.bandwidth ** 2) for start, _, _ in pairs]
            peak = max(log_weights)
            weights = [math.exp(value - peak) for value in log_weights]
            exposure = sum(weight * dt for weight, (_, _, dt) in zip(weights, pairs))
            scales.append([math.sqrt(weight / exposure) for weight in weights])
            drift = [sum(weight * delta[index] for weight, (_, delta, _) in zip(weights, pairs)) / exposure for index in range(dimension)]
            covariance = [[0.0] * dimension for _ in range(dimension)]
            for weight, (_, delta, dt) in zip(weights, pairs):
                residual = [delta[index] - drift[index] * dt for index in range(dimension)]
                for row in range(dimension):
                    for column in range(row + 1):
                        covariance[row][column] += weight * residual[row] * residual[column] / exposure
            for row in range(dimension):
                for column in range(row):
                    covariance[column][row] = covariance[row][column]
            self.anchors.append({"center": list(center), "drift": drift, "noise_covariance": covariance})
        self._empirical_noise = (copy.deepcopy(self.anchors),
                                 [(list(delta), dt) for _, delta, dt in pairs], scales)
        return self

    def _query_weights(self, vector):
        if len(vector) != self.dimension or not self.anchors:
            raise ValueError("fitted model and matching vector dimension required")
        log_weights = [-sum((a - b) ** 2 for a, b in zip(vector, anchor["center"])) / (2 * self.bandwidth ** 2) for anchor in self.anchors]
        peak = max(log_weights)
        weights = [math.exp(value - peak) for value in log_weights]
        total = sum(weights)
        return [weight / total for weight in weights]

    def drift(self, vector):
        """Evaluate the same fitted drift without constructing noise covariance."""
        weights = self._query_weights(vector)
        return [sum(weight * anchor["drift"][index] for weight, anchor in zip(weights, self.anchors)) for index in range(self.dimension)]

    def coefficients(self, vector):
        weights = self._query_weights(vector)
        drift = [sum(weight * anchor["drift"][index] for weight, anchor in zip(weights, self.anchors)) for index in range(self.dimension)]
        covariance = [[sum(weight * anchor["noise_covariance"][row][column] for weight, anchor in zip(weights, self.anchors)) for column in range(self.dimension)] for row in range(self.dimension)]
        return drift, covariance

    def _empirical_increment(self, vector, rng):
        """Sample the exact fitted mixture covariance using residual factors.

        Independent anchor/pair Gaussians give covariance sum(q_a C_a).
        Collecting delta and drift terms avoids dense per-step factorization.
        """
        _, pairs, scales = self._empirical_noise
        weights = self._query_weights(vector)
        pair_noise = [0.0] * len(pairs)
        drift_noise = []
        for weight, anchor_scales in zip(weights, scales):
            root = math.sqrt(weight)
            coefficients = [root * scale * rng.gauss(0.0, 1.0) for scale in anchor_scales]
            drift_noise.append(sum(value * dt for value, (_, dt) in zip(coefficients, pairs)))
            for index, value in enumerate(coefficients):
                pair_noise[index] += value
        drift = [sum(weight * anchor["drift"][index] for weight, anchor in zip(weights, self.anchors))
                 for index in range(self.dimension)]
        noise = [sum(value * delta[index] for value, (delta, _) in zip(pair_noise, pairs))
                 - sum(value * anchor["drift"][index] for value, anchor in zip(drift_noise, self.anchors))
                 for index in range(self.dimension)]
        return drift, noise

    def rollout(self, initial, duration_seconds, dt_seconds=1.0, num_rollouts=100, seed=42,
                observer=None):
        if not all(math.isfinite(value) and value > 0 for value in
                   (duration_seconds, dt_seconds)) or num_rollouts <= 0:
            raise ValueError("positive finite duration, step and rollout count required")
        empirical = (self._empirical_noise is not None
                     and type(self).coefficients is ContinuousDynamics.coefficients
                     and self.anchors == self._empirical_noise[0])
        rng = random.Random(seed)
        terminals = []
        sample_paths = []
        for run in range(num_rollouts):
            vector = list(initial)
            elapsed = 0.0
            path = [list(vector)]
            index = 0
            if observer is not None:
                observer(run, index, elapsed, tuple(vector))
            while elapsed < duration_seconds:
                next_time = min((index + 1) * dt_seconds, duration_seconds)
                step = next_time - elapsed
                if empirical:
                    drift, increment = self._empirical_increment(vector, rng)
                else:
                    drift, covariance = self.coefficients(vector)
                    lower = cholesky_psd(covariance)
                    noise = [rng.gauss(0.0, 1.0) for _ in vector]
                    increment = [sum(lower[row][column] * noise[column] for column in range(row + 1))
                                 for row in range(self.dimension)]
                vector = [vector[row] + drift[row] * step + math.sqrt(step) * increment[row]
                          for row in range(self.dimension)]
                if not all(math.isfinite(value) for value in vector):
                    raise ValueError("nonfinite SDE trajectory")
                elapsed = next_time
                index += 1
                if observer is not None:
                    observer(run, index, elapsed, tuple(vector))
                if run < 5:
                    path.append(list(vector))
            terminals.append(vector)
            if run < 5:
                sample_paths.append(path)
        mean = [sum(vector[index] for vector in terminals) / num_rollouts for index in range(self.dimension)]
        variance = [sum((vector[index] - mean[index]) ** 2 for vector in terminals) / num_rollouts for index in range(self.dimension)]
        return {"terminal_mean": mean, "terminal_variance": variance, "sample_paths": sample_paths,
                "duration_seconds": duration_seconds, "dt_seconds": dt_seconds, "num_rollouts": num_rollouts,
                "noise_sampler": "fitted_empirical_gaussian_factor" if empirical else "cholesky",
                "evidence_scope": "white_noise_ito_model_not_fractional_memory_or_causal_effect"}

    def to_dict(self):
        return {"protocol": "traceform.continuous-dynamics.v1", "bandwidth": self.bandwidth,
                "dimension": self.dimension, "model_version": self.model_version,
                "fit_pair_count": self.fit_pair_count, "anchors": self.anchors,
                "coordinate_provenance": self.coordinate_provenance,
                "diffusion_convention": "noise_covariance=2D", "potential_inferred": False}

    def infer_potential(self, mobility=1.0):
        """Least-squares graph potential of trapezoidal drift line integrals.

        For scalar constant mobility mu, drift = -mu grad(U) is the working
        convention. Complete-graph projection retains every nonconservative
        edge residual; this does not establish detailed balance or a physical
        thermodynamic energy. U[0] = 0 fixes the arbitrary additive constant.
        """
        if not math.isfinite(mobility) or mobility <= 0:
            raise ValueError("positive finite scalar mobility required")
        count = len(self.anchors)
        if count < 2 or self.dimension < 1:
            raise ValueError("at least two fitted anchors required")
        for anchor in self.anchors:
            if any(len(anchor[key]) != self.dimension or
                   not all(math.isfinite(value) for value in anchor[key])
                   for key in ("center", "drift")):
                raise ValueError("finite dimension-matched anchor coordinates and drift required")
        edges = []
        divergence = [0.0] * count
        for source in range(count):
            for target in range(source + 1, count):
                first, second = self.anchors[source], self.anchors[target]
                difference = -sum((left + right) * (end - start) / (2 * mobility)
                                  for left, right, start, end in zip(
                                      first["drift"], second["drift"],
                                      first["center"], second["center"]))
                if not math.isfinite(difference):
                    raise ValueError("nonfinite drift line integral")
                edges.append({"source": source, "target": target,
                              "observed_energy_difference": difference})
                divergence[source] -= difference
                divergence[target] += difference
        # Complete graph Laplacian is n*I - 11^T; divergence sums to zero.
        energy = [(value - divergence[0]) / count for value in divergence]
        total_square, residual_square = 0.0, 0.0
        for edge in edges:
            projected = energy[edge["target"]] - energy[edge["source"]]
            residual = edge["observed_energy_difference"] - projected
            edge.update({"projected_energy_difference": projected, "residual": residual})
            total_square += edge["observed_energy_difference"] ** 2
            residual_square += residual ** 2
        return {"protocol": "traceform.graph-potential.v1", "model_version": self.model_version,
                "coordinate_provenance": dict(self.coordinate_provenance),
                "centers": [list(anchor["center"]) for anchor in self.anchors],
                "relative_energy": energy, "mobility": mobility, "bandwidth": self.bandwidth, "gauge_anchor": 0,
                "edges": edges, "edge_residual_rms": math.sqrt(residual_square / len(edges)),
                "conservative_fraction": max(0.0, min(1.0, 1 - residual_square / total_square))
                    if total_square else None,
                "evidence_scope": "discrete_drift_projection_not_thermodynamic_energy_or_equilibrium_proof"}

    def potential_at(self, vector, potential):
        """Gaussian-kernel smoothing of graph energies and its exact gradient.

        Smoothing need not reproduce anchor energies or the original drift.
        The returned vector residual makes that distinction explicit.
        """
        if len(vector) != self.dimension or not all(math.isfinite(value) for value in vector):
            raise ValueError("finite dimension-matched query vector required")
        if potential.get("bandwidth") != self.bandwidth:
            raise ValueError("potential bandwidth must match current model")
        centers, energies = potential["centers"], potential["relative_energy"]
        if centers != [anchor["center"] for anchor in self.anchors] or len(energies) != len(centers):
            raise ValueError("potential must match current model anchors")
        if potential["model_version"] != self.model_version or not all(math.isfinite(value) for value in energies):
            raise ValueError("finite potential with matching model version required")
        result = evaluate_graph_potential(vector, potential)
        energy, gradient, conservative = result["energy"], result["gradient"], result["conservative_drift"]
        empirical, _ = self.coefficients(vector)
        return {"energy": energy, "gradient": gradient, "conservative_drift": conservative,
                "empirical_drift": empirical,
                "residual_drift": [value - projected for value, projected in zip(empirical, conservative)],
                "evidence_scope": "smoothed_graph_potential_not_exact_recovery_of_empirical_drift"}

    def estimate_memory(self, records):
        """Variance-time slope of drift residuals in one regular held-out segment.

        This is a diagnostic under stationary additive fGn assumptions. It does
        not distinguish memory from measurement error or omitted drift.
        """
        if self.model_version is None:
            raise ValueError("memory estimation requires fitted representation provenance")
        if len(records) < 65:
            raise ValueError("at least 64 consecutive residual increments required")
        residuals = []
        interval = None
        for first, second in zip(records, records[1:]):
            if any(record.get(field) != digest for record in (first, second)
                   for field, digest in self.coordinate_provenance.items()):
                raise ValueError("memory observations must match fitted coordinate provenance")
            if first.get("model_version") != self.model_version or not same_stream_segment(first, second):
                raise ValueError("one uninterrupted fitted representation segment required")
            dt = (timestamp(second) - timestamp(first)).total_seconds()
            if dt <= 0 or (interval is not None and not math.isclose(dt, interval, rel_tol=1e-6, abs_tol=1e-6)):
                raise ValueError("positive equally spaced observations required")
            interval = dt
            start, end = first["continuous_vector"], second["continuous_vector"]
            if any(len(vector) != self.dimension or not all(math.isfinite(value) for value in vector)
                   for vector in (start, end)):
                raise ValueError("finite dimension-matched observations required")
            drift, _ = self.coefficients(start)
            residuals.append([right - left - velocity * dt
                              for left, right, velocity in zip(start, end, drift)])
        scales = []
        block_size = 1
        while len(residuals) // block_size >= 16:
            blocks = [[sum(row[index] for row in residuals[offset:offset + block_size])
                       for index in range(self.dimension)]
                      for offset in range(0, len(residuals) - block_size + 1, block_size)]
            means = [sum(row[index] for row in blocks) / len(blocks) for index in range(self.dimension)]
            variance = sum(sum((value - mean) ** 2 for value, mean in zip(row, means))
                           for row in blocks) / (len(blocks) - 1)
            if variance <= 0 or not math.isfinite(variance):
                raise ValueError("positive finite residual variance required at every scale")
            scales.append({"block_size": block_size, "blocks": len(blocks), "variance": variance})
            block_size *= 2
        xs = [math.log(row["block_size"]) for row in scales]
        ys = [math.log(row["variance"]) for row in scales]
        x_mean, y_mean = sum(xs) / len(xs), sum(ys) / len(ys)
        slope = sum((x - x_mean) * (y - y_mean) for x, y in zip(xs, ys)) / sum((x - x_mean) ** 2 for x in xs)
        error = sum((y - y_mean - slope * (x - x_mean)) ** 2 for x, y in zip(xs, ys))
        spread = sum((y - y_mean) ** 2 for y in ys)
        return {"protocol": "traceform.residual-memory.v1", "hurst": slope / 2,
                "valid_fractional_range": 0 < slope / 2 < 1,
                "log_variance_fit_r_squared": 1 - error / spread if spread else None,
                "interval_seconds": interval, "residual_count": len(residuals), "scales": scales,
                "model_version": self.model_version,
                "coordinate_provenance": dict(self.coordinate_provenance),
                "evidence_scope": "regular_segment_residual_scaling_diagnostic_not_proof_of_fractional_memory"}

    def estimate_memory_irregular(self, records, coordinate=0, brownian_bootstrap_draws=0, seed=0):
        """Profile Gaussian likelihood on the actual grid for one coordinate.

        Drift is supplied by this fitted model. Noise scale is profiled out;
        no interpolation, pooled independent-coordinate assumption or jitter
        is introduced. Dense factorization is bounded to 128 increments.
        Optional Brownian calibration conditions on the fitted drift and actual
        time grid; it does not account for drift-estimation uncertainty.
        """
        if (type(brownian_bootstrap_draws) is not int or not 0 <= brownian_bootstrap_draws <= 999
                or type(seed) is not int):
            raise ValueError("0 to 999 bootstrap draws and an integer seed required")
        if self.model_version is None or not 33 <= len(records) <= 129:
            raise ValueError("versioned model and 32 to 128 consecutive increments required")
        if not isinstance(coordinate, int) or not 0 <= coordinate < self.dimension:
            raise ValueError("valid coordinate index required")
        times, residuals = [0.0], []
        for first, second in zip(records, records[1:]):
            if any(record.get(field) != digest for record in (first, second)
                   for field, digest in self.coordinate_provenance.items()):
                raise ValueError("memory observations must match fitted coordinate provenance")
            if first.get("model_version") != self.model_version or not same_stream_segment(first, second):
                raise ValueError("one uninterrupted fitted representation segment required")
            dt = (timestamp(second) - timestamp(first)).total_seconds()
            if not math.isfinite(dt) or dt <= 0:
                raise ValueError("positive finite observation intervals required")
            start, end = first["continuous_vector"], second["continuous_vector"]
            if any(len(vector) != self.dimension or not all(math.isfinite(value) for value in vector)
                   for vector in (start, end)):
                raise ValueError("finite dimension-matched observations required")
            drift, _ = self.coefficients(start)
            residuals.append(end[coordinate] - start[coordinate] - drift[coordinate] * dt)
            times.append(times[-1] + dt)
        count = len(residuals)
        unit = times[-1] / count
        normalized_times = [value / unit for value in times]
        factors = []
        for index in range(1, 20):
            hurst = index / 20
            lower = cholesky_psd(self.fractional_increment_covariance(normalized_times, hurst))
            factors.append((hurst, lower))
        profile = self._memory_profile(residuals, factors)
        best = min(profile, key=lambda row: row["negative_log_likelihood_up_to_constant"])
        brownian = next(row for row in profile if row["hurst"] == 0.5)
        gain = brownian["negative_log_likelihood_up_to_constant"] - best["negative_log_likelihood_up_to_constant"]
        result = {"protocol": "traceform.irregular-memory.v1", "hurst": best["hurst"],
                "log_likelihood_gain_over_brownian": gain,
                "comparison_scope": "in_sample_profile_contrast_not_calibrated_significance_or_predictive_gain",
                "profile_scale_variance": best["profile_scale_variance"], "coordinate": coordinate,
                "increment_count": count, "time_unit_seconds": unit, "profile": profile,
                "grid_boundary_optimum": best["hurst"] in (0.05, 0.95),
                "model_version": self.model_version,
                "coordinate_provenance": dict(self.coordinate_provenance),
                "evidence_scope": "single_coordinate_fractional_gaussian_profile_not_empirical_memory_proof"}
        if brownian_bootstrap_draws:
            rng = random.Random(seed)
            gains = []
            for _ in range(brownian_bootstrap_draws):
                null_residuals = [rng.gauss(0, math.sqrt(normalized_times[i + 1] - normalized_times[i]))
                                  for i in range(count)]
                null_profile = self._memory_profile(null_residuals, factors)
                null_brownian = next(row for row in null_profile if row["hurst"] == 0.5)
                gains.append(null_brownian["negative_log_likelihood_up_to_constant"]
                             - min(row["negative_log_likelihood_up_to_constant"] for row in null_profile))
            result["brownian_null_calibration"] = {
                "draws": brownian_bootstrap_draws, "seed": seed,
                "exceedance_count": sum(value >= gain for value in gains),
                "monte_carlo_p_value": (1 + sum(value >= gain for value in gains)) / (1 + len(gains)),
                "null_likelihood_gains": gains,
                "scope": "conditional_fixed_drift_single_coordinate_gaussian_null_not_drift_uncertainty_multiple_testing_or_memory_acceptance"}
        return result

    @staticmethod
    def _memory_profile(residuals, factors):
        count = len(residuals)
        profile = []
        for hurst, lower in factors:
            whitened = []
            for row in range(count):
                whitened.append((residuals[row] - sum(lower[row][column] * whitened[column]
                                                     for column in range(row))) / lower[row][row])
            scale = sum(value ** 2 for value in whitened) / count
            if scale <= 0 or not math.isfinite(scale):
                raise ValueError("positive finite residual noise required")
            score = 0.5 * (count * math.log(scale) + 2 * sum(math.log(lower[row][row]) for row in range(count)))
            profile.append({"hurst": hurst, "profile_scale_variance": scale,
                            "negative_log_likelihood_up_to_constant": score})
        return profile

    @staticmethod
    def fractional_increment_covariance(times, hurst):
        """Exact fBm increment covariance on an arbitrary increasing time grid."""
        if not math.isfinite(hurst) or not 0 < hurst < 1:
            raise ValueError("Hurst exponent must be finite and between zero and one")
        if len(times) < 2 or not all(math.isfinite(time) for time in times):
            raise ValueError("finite time grid with at least two points required")
        if any(right <= left for left, right in zip(times, times[1:])):
            raise ValueError("strictly increasing time grid required")
        intervals = list(zip(times, times[1:]))
        return [[0.5 * (abs(b - c) ** (2 * hurst) + abs(a - d) ** (2 * hurst)
                        - abs(b - d) ** (2 * hurst) - abs(a - c) ** (2 * hurst))
                 for c, d in intervals] for a, b in intervals]

    def rollout_fractional(self, initial, duration_seconds, dt_seconds=1.0,
                           num_rollouts=100, seed=42, hurst=0.25, observer=None):
        """Additive fBm driver with diffusion frozen at the initial location.

        H is an explicit simulation assumption, not estimated from fitted Ito
        coefficients. Freezing the spatial noise factor avoids silently applying
        an Ito discretization to multiplicative fractional noise.
        """
        if not all(math.isfinite(value) and value > 0 for value in
                   (duration_seconds, dt_seconds)) or num_rollouts <= 0:
            raise ValueError("positive finite duration, step and rollout count required")
        _, covariance = self.coefficients(initial)
        spatial = cholesky_psd(covariance)
        times = [0.0]
        while times[-1] < duration_seconds:
            times.append(min(duration_seconds, len(times) * dt_seconds))
        temporal = cholesky_psd(self.fractional_increment_covariance(times, hurst))
        steps = len(temporal)
        rng = random.Random(seed)
        terminals, paths = [], []
        for run in range(num_rollouts):
            independent = [[rng.gauss(0.0, 1.0) for _ in range(steps)]
                           for _ in range(self.dimension)]
            increments = [[sum(temporal[row][column] * noise[column]
                               for column in range(row + 1)) for row in range(steps)]
                          for noise in independent]
            vector = list(initial)
            path = [list(vector)]
            if observer is not None:
                observer(run, 0, 0.0, tuple(vector))
            for index in range(steps):
                drift, _ = self.coefficients(vector)
                step = times[index + 1] - times[index]
                vector = [vector[row] + drift[row] * step
                          + sum(spatial[row][column] * increments[column][index]
                                for column in range(row + 1))
                          for row in range(self.dimension)]
                if not all(math.isfinite(value) for value in vector):
                    raise ValueError("nonfinite fractional trajectory")
                if observer is not None:
                    observer(run, index + 1, times[index + 1], tuple(vector))
                if run < 5:
                    path.append(list(vector))
            terminals.append(vector)
            if run < 5:
                paths.append(path)
        mean = [sum(vector[index] for vector in terminals) / num_rollouts
                for index in range(self.dimension)]
        variance = [sum((vector[index] - mean[index]) ** 2 for vector in terminals)
                    / num_rollouts for index in range(self.dimension)]
        return {"terminal_mean": mean, "terminal_variance": variance,
                "sample_paths": paths, "time_grid_seconds": times, "hurst": hurst,
                "num_rollouts": num_rollouts, "diffusion_frozen_at": list(initial),
                "evidence_scope": "additive_fractional_simulation_assumed_hurst_not_empirical_memory_fit"}
