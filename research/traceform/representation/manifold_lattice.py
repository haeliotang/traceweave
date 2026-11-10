# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Dynamical Energy-Guided Topological Manifold (DETM) Lattice.

Combines 2D self-organizing topological maps (SOM) with kinetic energy-based
metrics on continuous multimodal sensor observables and their temporal phase velocities.
Discovers discrete latent states as coordinate patches on a continuous non-linear manifold.
"""

from __future__ import annotations

from dataclasses import dataclass
import math
import random
from typing import Any, Dict, List, Optional, Tuple

from traceform.forecasting.continuity import same_stream_segment


def _sq_dist(v1: List[float], v2: List[float]) -> float:
    return sum((a - b) ** 2 for a, b in zip(v1, v2))


def _norm(v: List[float]) -> float:
    return math.sqrt(sum(a * a for a in v))


def _dot(v1: List[float], v2: List[float]) -> float:
    return sum(a * b for a, b in zip(v1, v2))


@dataclass
class ManifoldNode:
    node_id: int
    grid_coord: Tuple[int, int]
    state_token: str
    center_x: List[float]
    center_v: List[float]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "node_id": self.node_id,
            "grid_coord": list(self.grid_coord),
            "state_token": self.state_token,
            "center_x": [round(x, 5) for x in self.center_x],
            "center_v": [round(v, 5) for v in self.center_v],
        }

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> ManifoldNode:
        return cls(
            node_id=d["node_id"],
            grid_coord=(d["grid_coord"][0], d["grid_coord"][1]),
            state_token=d["state_token"],
            center_x=list(d["center_x"]),
            center_v=list(d["center_v"]),
        )


class DynamicalEnergyManifold:
    """
    Self-organizing 2D topological manifold lattice guided by kinetic energy and
    spatial feature distance.
    """

    def __init__(
        self,
        grid_height: int = 4,
        grid_width: int = 4,
        kinetic_weight: float = 0.35,
        ema_alpha: float = 0.4,
        **kwargs: Any,
    ) -> None:
        self.height = grid_height
        self.width = grid_width
        self.k_states = grid_height * grid_width
        self.beta = float(kwargs.get("beta_kinetic", kinetic_weight))
        self.ema_alpha = ema_alpha

        self.nodes: List[ManifoldNode] = []
        self.token_to_coord: Dict[str, Tuple[int, int]] = {}
        self.last_obs_vector: Optional[List[float]] = None
        self.last_velocity: Optional[List[float]] = None

    def _compute_phase_velocities(
        self, vectors: List[List[float]], timestamps: Optional[List[float]] = None,
        discontinuities: Optional[List[bool]] = None,
    ) -> List[List[float]]:
        """Computes smoothed phase velocities v(t) = dx/dt across feature frames."""
        n = len(vectors)
        if n == 0:
            return []
        dim = len(vectors[0])
        velocities: List[List[float]] = [[0.0] * dim]

        smooth_v = [0.0] * dim

        for i in range(1, n):
            dt = 1.0
            if timestamps and i < len(timestamps):
                dt = timestamps[i] - timestamps[i - 1]
            if not 0 < dt <= 300 or (discontinuities is not None and discontinuities[i]):
                smooth_v = [0.0] * dim
                velocities.append(list(smooth_v))
                continue
            dt = max(0.1, dt)

            raw_v = [(vectors[i][d] - vectors[i - 1][d]) / dt for d in range(dim)]
            # EMA smoothing to suppress high-frequency differential jitter
            smooth_v = [
                self.ema_alpha * raw_v[d] + (1.0 - self.ema_alpha) * smooth_v[d]
                for d in range(dim)
            ]
            velocities.append(list(smooth_v))

        return velocities

    def compute_energy_distance(
        self,
        x: List[float],
        v: List[float],
        node: ManifoldNode,
    ) -> float:
        """
        Computes energy-geodesic distance D = Position_Distance + Kinetic_Divergence.
        """
        pos_dist = _sq_dist(x, node.center_x)

        norm_v = _norm(v)
        norm_cv = _norm(node.center_v)

        if norm_v < 1e-4 or norm_cv < 1e-4:
            kin_dist = _sq_dist(v, node.center_v)
        else:
            cosine = max(-1.0, min(1.0, _dot(v, node.center_v) / (norm_v * norm_cv)))
            # Momentum direction penalty scaled by magnitude
            kin_dist = (1.0 - cosine) * norm_v + 0.1 * abs(norm_v - norm_cv)

        return pos_dist + self.beta * kin_dist

    @staticmethod
    def compute_neighborhood_weight(
        coord1: Tuple[int, int],
        coord2: Tuple[int, int],
        sigma: float,
    ) -> float:
        """
        Computes Gaussian topological neighborhood weight on the 2D lattice.
        """
        grid_dist_sq = (coord1[0] - coord2[0]) ** 2 + (coord1[1] - coord2[1]) ** 2
        return math.exp(-grid_dist_sq / max(1e-6, 2.0 * sigma * sigma))

    def fit_discovery_window(
        self,
        observations: List[Dict[str, Any]],
        normalizer: Any,
        epochs: int = 6,
        seed: int = 42,
        warm_start: bool = False,
    ) -> List[str]:
        """
        Fits 2D dynamical manifold over discovery observations using self-organizing
        energy-topological descent.
        """
        if not observations:
            return []

        rng = random.Random(seed)
        # Parse timestamps for velocity estimation
        timestamps: List[float] = []
        for obs in observations:
            ts_str = obs.get("timestamp_utc", "")
            try:
                # Basic second conversion
                import datetime

                dt = datetime.datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
                timestamps.append(dt.timestamp())
            except Exception:
                timestamps.append(float(len(timestamps) * 30.0))

        discontinuities = [True] + [
            not 0 < timestamps[index] - timestamps[index - 1] <= 300
            or not same_stream_segment(observations[index - 1], observations[index])
            or any(observations[index - 1].get(key) != observations[index].get(key)
                   for key in ("session_id", "model_sha256", "normalizer_sha256"))
            for index in range(1, len(observations))]
        vectors = []
        reset_state = getattr(normalizer, 'reset_state', None)
        for observation, discontinuous in zip(observations, discontinuities):
            if discontinuous and reset_state is not None:
                reset_state()
            vectors.append(normalizer.transform(observation)[0])
        n_points = len(vectors)
        dim = len(vectors[0])
        velocities = self._compute_phase_velocities(vectors, timestamps, discontinuities=discontinuities)

        # 1. Initialize 2D grid nodes by sampling observations
        if warm_start:
            if (len(self.nodes) != self.k_states or any(
                    len(node.center_x) != dim or len(node.center_v) != dim for node in self.nodes)):
                raise ValueError("incremental fit requires a complete dimension-matched manifold")
        else:
            sample_indices = rng.sample(range(n_points), min(self.k_states, n_points))
            while len(sample_indices) < self.k_states:
                sample_indices.append(rng.choice(range(n_points)))

            self.nodes = []
            node_id = 0
            for u in range(self.height):
                for v in range(self.width):
                    idx = sample_indices[node_id]
                    token = f"M{node_id + 1:02d}"
                    node = ManifoldNode(
                        node_id=node_id,
                        grid_coord=(u, v),
                        state_token=token,
                        center_x=list(vectors[idx]),
                        center_v=list(velocities[idx]),
                    )
                    self.nodes.append(node)
                    self.token_to_coord[token] = (u, v)
                    node_id += 1

        # 2. Self-Organizing Topological Adaptation Loop
        initial_lr = 0.1 if warm_start else 0.5
        initial_sigma = max(self.height, self.width) / 2.0
        total_steps = epochs * n_points

        step = 0
        indices = list(range(n_points))

        for epoch in range(epochs):
            rng.shuffle(indices)
            for idx in indices:
                x = vectors[idx]
                vel = velocities[idx]

                # Progressive cooling of learning rate and topological neighborhood radius
                frac = step / max(1, total_steps)
                lr = initial_lr * (1.0 - frac)
                sigma = max(0.5, initial_sigma * (1.0 - frac))

                # Find Best Matching Unit (BMU) minimizing energy distance
                best_node = min(
                    self.nodes, key=lambda nd: self.compute_energy_distance(x, vel, nd)
                )
                bu, bv = best_node.grid_coord

                # Adapt BMU and its topological neighbors on the 2D grid
                for nd in self.nodes:
                    influence = self.compute_neighborhood_weight(
                        nd.grid_coord, (bu, bv), sigma
                    )
                    weight = lr * influence

                    if weight > 1e-4:
                        for d in range(dim):
                            nd.center_x[d] += weight * (x[d] - nd.center_x[d])
                            nd.center_v[d] += weight * (vel[d] - nd.center_v[d])

                step += 1

        if warm_start:
            return [node.state_token for node in self.nodes]

        # Sort/Rank node tokens by stationary frequency so M01 is most visited
        node_counts = [0] * len(self.nodes)
        for idx in range(n_points):
            bmu = min(
                self.nodes,
                key=lambda nd: self.compute_energy_distance(
                    vectors[idx], velocities[idx], nd
                ),
            )
            node_counts[bmu.node_id] += 1

        # Sort nodes descending by visit frequency, re-assign canonical tokens
        ranked = sorted(
            enumerate(self.nodes), key=lambda pair: node_counts[pair[0]], reverse=True
        )
        for rank, (_, nd) in enumerate(ranked):
            nd.state_token = f"M{rank + 1:02d}"
        self.nodes = [nd for _, nd in ranked]
        for new_id, nd in enumerate(self.nodes):
            nd.node_id = new_id

        return [nd.state_token for nd in self.nodes]

    def quantize_phase(
        self, vector: List[float], velocity: List[float],
    ) -> Tuple[str, Tuple[int, int], float]:
        """Quantize supplied phase coordinates without changing sensor history."""
        if not self.nodes:
            raise ValueError("fitted manifold nodes required")
        dimension = len(self.nodes[0].center_x)
        if (len(vector) != dimension or len(velocity) != dimension
                or not all(math.isfinite(value) for value in list(vector) + list(velocity))
                or any(len(node.center_x) != dimension or len(node.center_v) != dimension
                       for node in self.nodes)):
            raise ValueError("finite dimension-matched phase coordinates required")
        best_node = min(self.nodes, key=lambda node: self.compute_energy_distance(vector, velocity, node))
        return (best_node.state_token, best_node.grid_coord,
                round(self.compute_energy_distance(vector, velocity, best_node), 4))

    def quantize(
        self,
        obs: Dict[str, Any],
        normalizer: Any,
        dt_seconds: float = 30.0,
    ) -> Tuple[str, Tuple[int, int], float]:
        """
        Maps a real-time observation frame to its nearest manifold energy node.
        Returns: (state_token, grid_coord, energy_distance)
        """
        if not self.nodes:
            return "M00", (0, 0), 0.0

        vector, _ = normalizer.transform(obs)
        dim = len(vector)

        # Estimate instantaneous velocity from previous state
        if self.last_obs_vector is not None and len(self.last_obs_vector) == dim:
            raw_v = [
                (vector[d] - self.last_obs_vector[d]) / max(0.1, dt_seconds)
                for d in range(dim)
            ]
            if self.last_velocity is not None and len(self.last_velocity) == dim:
                velocity = [
                    self.ema_alpha * raw_v[d]
                    + (1.0 - self.ema_alpha) * self.last_velocity[d]
                    for d in range(dim)
                ]
            else:
                velocity = raw_v
        else:
            velocity = [0.0] * dim

        self.last_obs_vector = list(vector)
        self.last_velocity = list(velocity)

        best_node = min(
            self.nodes,
            key=lambda nd: self.compute_energy_distance(vector, velocity, nd),
        )
        dist = self.compute_energy_distance(vector, velocity, best_node)

        return best_node.state_token, best_node.grid_coord, round(dist, 4)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "grid_height": self.height,
            "grid_width": self.width,
            "k_states": self.k_states,
            "kinetic_weight": self.beta,
            "ema_alpha": self.ema_alpha,
            "nodes": [nd.to_dict() for nd in self.nodes],
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> DynamicalEnergyManifold:
        inst = cls(
            grid_height=data.get("grid_height", 4),
            grid_width=data.get("grid_width", 4),
            kinetic_weight=data.get("kinetic_weight", 0.35),
            ema_alpha=data.get("ema_alpha", 0.4),
        )
        inst.nodes = [ManifoldNode.from_dict(d) for d in data.get("nodes", [])]
        inst.token_to_coord = {nd.state_token: nd.grid_coord for nd in inst.nodes}
        return inst
