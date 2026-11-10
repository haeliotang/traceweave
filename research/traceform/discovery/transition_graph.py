# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Ergodic Transition Graph Construction and Dynamical Topology Extraction.

Builds directed state transition graphs, stationary distributions,
and spectral connectivity measures while strictly respecting gap boundaries.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import datetime
from typing import Any, Dict, List, Tuple

from traceform.forecasting.continuity import same_stream_segment


@dataclass
class TransitionGraphMetrics:
    total_nodes: int
    total_directed_edges: int
    graph_density: float
    reciprocity_ratio: float
    stationary_distribution: Dict[str, float]
    top_transition_edges: List[Dict[str, Any]]
    strongly_connected_components_count: int
    stationarity_residual: float = 0.0
    stationary_converged: bool = False
    stationary_iterations: int = 0
    valid_transition_count: int = 0

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def _strong_component_count(nodes, edges):
    adjacency = {node: [] for node in nodes}
    reverse = {node: [] for node in nodes}
    for edge in edges:
        adjacency[edge["source"]].append(edge["target"])
        reverse[edge["target"]].append(edge["source"])
    visited, order = set(), []
    for node in nodes:
        if node in visited:
            continue
        visited.add(node)
        stack = [(node, iter(adjacency[node]))]
        while stack:
            current, neighbors = stack[-1]
            following = next(neighbors, None)
            if following is None:
                stack.pop()
                order.append(current)
            elif following not in visited:
                visited.add(following)
                stack.append((following, iter(adjacency[following])))
    visited, count = set(), 0
    for node in reversed(order):
        if node in visited:
            continue
        count += 1
        visited.add(node)
        pending = [node]
        while pending:
            for following in reverse[pending.pop()]:
                if following not in visited:
                    visited.add(following)
                    pending.append(following)
    return count


def build_transition_graph(
    records: List[Dict[str, Any]],
    max_gap_seconds: float = 1800.0,  # 30 minutes
) -> Tuple[Dict[str, Dict[str, float]], TransitionGraphMetrics]:
    """
    Constructs the empirical state transition matrix and topological graph metrics,
    skipping steps that span gaps > max_gap_seconds.
    """
    nodes_set = set(r["state_token"] for r in records)
    nodes = sorted(list(nodes_set))

    edge_counts: Dict[str, Dict[str, int]] = {u: {v: 0 for v in nodes} for u in nodes}
    out_degrees: Dict[str, int] = {u: 0 for u in nodes}
    in_degrees: Dict[str, int] = {v: 0 for v in nodes}

    valid_step_count = 0
    for i in range(len(records) - 1):
        t1 = datetime.datetime.fromisoformat(
            records[i]["timestamp_utc"].replace("Z", "+00:00")
        )
        t2 = datetime.datetime.fromisoformat(
            records[i + 1]["timestamp_utc"].replace("Z", "+00:00")
        )
        dt_s = (t2 - t1).total_seconds()

        # Respect audit discipline: do not bridge gaps > 30 minutes
        if (not 0 < dt_s <= max_gap_seconds or not same_stream_segment(records[i], records[i + 1])
                or any(records[i].get(key) != records[i + 1].get(key)
                       for key in ("session_id", "model_sha256", "normalizer_sha256"))):
            continue

        u = records[i]["state_token"]
        v = records[i + 1]["state_token"]

        edge_counts[u][v] += 1
        out_degrees[u] += 1
        in_degrees[v] += 1
        valid_step_count += 1

    # Transition probability matrix P_uv = count(u->v) / sum_k count(u->k)
    trans_matrix: Dict[str, Dict[str, float]] = {u: {} for u in nodes}
    active_edges: List[Dict[str, Any]] = []

    for u in nodes:
        denom = out_degrees[u]
        if denom > 0:
            for v in nodes:
                cnt = edge_counts[u][v]
                if cnt > 0:
                    prob = cnt / denom
                    trans_matrix[u][v] = prob
                    active_edges.append(
                        {
                            "source": u,
                            "target": v,
                            "count": cnt,
                            "probability": prob,
                        }
                    )
        else:
            trans_matrix[u][u] = 1.0

    # Stationary distribution estimation via power iteration
    n_nodes = len(nodes)
    if n_nodes == 0:
        return {}, TransitionGraphMetrics(
            total_nodes=0,
            total_directed_edges=0,
            graph_density=0.0,
            reciprocity_ratio=0.0,
            stationary_distribution={},
            top_transition_edges=[],
            strongly_connected_components_count=0,
        )
    pi = {u: 1.0 / n_nodes for u in nodes}
    residual = float("inf")
    for iteration in range(1, 1001):
        next_pi = {u: 0.0 for u in nodes}
        for u in nodes:
            for v, probability in trans_matrix[u].items():
                next_pi[v] += pi[u] * probability
        residual = sum(abs(next_pi[u] - pi[u]) for u in nodes)
        if residual <= 1e-10 or iteration == 1000:
            break
        # Lazification preserves stationary distributions and removes periodic oscillation.
        pi = {u: (pi[u] + next_pi[u]) / 2 for u in nodes}

    pi_rounded = {u: pi[u] for u in sorted(nodes, key=lambda x: pi[x], reverse=True)}

    # Graph Density and Reciprocity (excluding self-loops)
    non_self_edges = [e for e in active_edges if e["source"] != e["target"]]
    num_possible_edges = n_nodes * (n_nodes - 1) if n_nodes > 1 else 1
    density = round(len(non_self_edges) / num_possible_edges, 4)

    # Reciprocal edges: where (u->v) and (v->u) both exist
    reciprocal_count = 0
    for e in active_edges:
        u, v = e["source"], e["target"]
        if u != v and edge_counts[v][u] > 0:
            reciprocal_count += 1
    reciprocity = round(
        reciprocal_count
        / max(1, len([e for e in active_edges if e["source"] != e["target"]])),
        4,
    )

    # Top transition edges (excluding self-transitions)
    inter_state_edges = [e for e in active_edges if e["source"] != e["target"]]
    inter_state_edges.sort(key=lambda x: x["count"], reverse=True)

    metrics = TransitionGraphMetrics(
        total_nodes=n_nodes,
        total_directed_edges=len(active_edges),
        graph_density=density,
        reciprocity_ratio=reciprocity,
        stationary_distribution=pi_rounded,
        top_transition_edges=inter_state_edges[:10],
        strongly_connected_components_count=_strong_component_count(nodes, active_edges),
        stationarity_residual=residual,
        stationary_converged=residual <= 1e-10,
        stationary_iterations=iteration,
        valid_transition_count=valid_step_count,
    )

    return trans_matrix, metrics
