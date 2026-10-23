"""Chronological candidate calibration against the active representation."""

import math

from traceform.discovery.transition_graph import build_transition_graph
from traceform.forecasting.evaluation import timestamp
from traceform.forecasting.continuity import same_stream_segment
from traceform.representation.manifold_lattice import DynamicalEnergyManifold
from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.provenance import content_digest


def assess_topology(model_data, normalizer_data, observations):
    model = DynamicalEnergyManifold.from_dict(model_data)
    normalizer = MultimodalNormalizer.from_dict(normalizer_data)
    distances, tokens = [], set()
    quantized = []
    nonadjacent = 0
    nearest_pairs = {}
    previous = None
    previous_observation = None
    for observation in observations:
        instant = timestamp(observation)
        gap = (instant - previous).total_seconds() if previous else 30.0
        if previous and (not 0 < gap <= 300 or not same_stream_segment(previous_observation, observation)
                         or any(previous_observation.get(key) != observation.get(key)
                                for key in ('session_id', 'model_sha256', 'normalizer_sha256'))):
            model.last_obs_vector = None
            model.last_velocity = None
            normalizer.reset_state()
        token, _, distance = model.quantize(observation, normalizer, dt_seconds=gap if gap > 0 else 30)
        distances.append(distance)
        tokens.add(token)
        record = {key: observation[key] for key in (
            'timestamp_utc', 'model_version', 'boot_id', 'continuity_epoch', 'source_mode',
            'session_id', 'model_sha256', 'normalizer_sha256', 'continuous_from_previous') if key in observation}
        record['state_token'] = token
        quantized.append(record)
        if len(model.nodes) >= 2:
            nearest = sorted(model.nodes, key=lambda node: model.compute_energy_distance(model.last_obs_vector, model.last_velocity, node))[:2]
            pair = tuple(sorted(node.state_token for node in nearest))
            nearest_pairs[pair] = nearest_pairs.get(pair, 0) + 1
            separation = sum(abs(left - right) for left, right in zip(nearest[0].grid_coord, nearest[1].grid_coord))
            nonadjacent += separation > 1
        previous = instant
        previous_observation = observation
    if not distances:
        raise ValueError("topology assessment requires observations")
    matrix, graph = build_transition_graph(quantized, max_gap_seconds=300)
    ordered = sorted(distances)
    return {"records": len(distances), "mean_energy_distance": sum(distances) / len(distances),
            "p95_energy_distance": ordered[math.ceil(0.95 * len(ordered)) - 1],
            "occupied_states": len(tokens),
            "topographic_error": nonadjacent / len(distances) if len(model.nodes) >= 2 else None,
            "nearest_pair_counts": [{"tokens": list(pair), "count": count}
                                    for pair, count in sorted(nearest_pairs.items())],
            "transition_structure": {"transition_matrix": matrix, "metrics": graph.to_dict(),
                "model_sha256": content_digest(model_data), "normalizer_sha256": content_digest(normalizer_data),
                "source_sha256": content_digest(observations)},
            "scope": "energy_nearest_two_grid_adjacency_diagnostic_not_causal_or_stability_proof"}


def adapt_grid_structure(candidate, normalizer_data, observations):
    """Strictly decrease training nearest-pair nonadjacency by coordinate swaps."""
    assessment = assess_topology(candidate.to_dict(), normalizer_data, observations)
    pairs = assessment["nearest_pair_counts"]
    nodes = sorted(candidate.nodes, key=lambda node: node.state_token)
    coordinates = {node.state_token: node.grid_coord for node in nodes}
    def loss():
        return sum(pair["count"] for pair in pairs
                   if sum(abs(left - right) for left, right in zip(
                       coordinates[pair["tokens"][0]], coordinates[pair["tokens"][1]])) > 1)
    before = current = loss()
    swaps = []
    while True:
        best, selected = current, None
        for index, first in enumerate(nodes):
            for second in nodes[index + 1:]:
                left, right = first.state_token, second.state_token
                coordinates[left], coordinates[right] = coordinates[right], coordinates[left]
                proposal = loss()
                coordinates[left], coordinates[right] = coordinates[right], coordinates[left]
                if proposal < best:
                    best, selected = proposal, (left, right)
        if selected is None:
            break
        left, right = selected
        coordinates[left], coordinates[right] = coordinates[right], coordinates[left]
        swaps.append([left, right])
        current = best
    for node in candidate.nodes:
        node.grid_coord = coordinates[node.state_token]
    candidate.token_to_coord = coordinates
    return {"changed": bool(swaps), "coordinate_swaps": swaps,
            "training_nonadjacent_pairs_before": before, "training_nonadjacent_pairs_after": current,
            "training_source_sha256": content_digest(observations),
            "scope": "greedy_existing_node_grid_structure_not_global_optimum_or_new_state_growth"}


def score_model(model_data, normalizer_data, observations):
    report = assess_topology(model_data, normalizer_data, observations)
    return report["mean_energy_distance"], report["occupied_states"]


def audit_observations(observations):
    records = invalid_timestamps = nonchronological = nonfinite = gaps = 0
    previous = None
    for observation in observations:
        records += 1
        try:
            instant = timestamp(observation)
        except (KeyError, ValueError, TypeError, AttributeError):
            invalid_timestamps += 1
            previous = None
        else:
            if previous is not None:
                gap = (instant - previous).total_seconds()
                nonchronological += gap <= 0
                gaps += gap > 300
            previous = instant
        pending = [observation]
        while pending:
            value = pending.pop()
            if isinstance(value, dict):
                pending.extend(value.values())
            elif isinstance(value, (list, tuple)):
                pending.extend(value)
            elif isinstance(value, float) and not math.isfinite(value):
                nonfinite += 1
    return {"valid": records > 0 and invalid_timestamps == nonchronological == nonfinite == 0,
            "records": records, "invalid_timestamps": invalid_timestamps,
            "nonchronological_pairs": nonchronological, "nonfinite_values": nonfinite,
            "gaps_over_300_seconds": gaps,
            "scope": "timestamp_order_and_numeric_integrity_not_sensor_truth_or_completeness"}


def calibrate_candidate(registry, observations):
    if len(observations) < 100:
        return {"accepted": False, "reason": "at least 100 observations required"}
    audit = audit_observations(observations)
    if not audit["valid"]:
        return {"accepted": False, "reason": "stream_validation_failed", "stream_validation": audit}
    active = registry.active()
    if active is None:
        raise ValueError("initialize active registry before calibration")
    split = int(len(observations) * 0.7)
    train, holdout = observations[:split], observations[split:]
    normalizer = MultimodalNormalizer.from_dict(active["normalizer"])
    reference = DynamicalEnergyManifold.from_dict(active["manifold"])
    candidate = DynamicalEnergyManifold.from_dict(active["manifold"])
    structural_change = adapt_grid_structure(candidate, active["normalizer"], train)
    candidate.fit_discovery_window(train, normalizer, epochs=6, seed=42, warm_start=True)
    migration = {node.state_token: node.state_token for node in candidate.nodes}
    candidate_data = candidate.to_dict()
    baseline_score, baseline_states = score_model(active["manifold"], active["normalizer"], holdout)
    candidate_score, candidate_states = score_model(candidate_data, active["normalizer"], holdout)
    holdout_assessments = {label: assess_topology(data, active["normalizer"], holdout)
                           for label, data in (("baseline", active["manifold"]), ("candidate", candidate_data))}
    baseline_error = holdout_assessments["baseline"]["topographic_error"]
    candidate_error = holdout_assessments["candidate"]["topographic_error"]
    topology_passed = (baseline_error is None and candidate_error is None) or (
        baseline_error is not None and candidate_error is not None and candidate_error <= baseline_error)
    accepted = topology_passed and math.isfinite(candidate_score) and candidate_score < baseline_score * 0.95 and candidate_states >= min(2, baseline_states)
    report = {"accepted": accepted, "reason": "holdout_distance_coverage_and_topology" if accepted else "holdout_gate_not_met",
              "baseline_mean_distance": baseline_score, "candidate_mean_distance": candidate_score,
              "baseline_states": baseline_states, "candidate_states": candidate_states,
              "structural_change": structural_change,
              "holdout_topology_gate": {"passed": topology_passed, "baseline_error": baseline_error,
                                        "candidate_error": candidate_error},
              "training_records": len(train), "holdout_records": len(holdout),
              "stream_validation": audit,
              "full_stream_topological_strain": {
                  "baseline": assess_topology(active["manifold"], active["normalizer"], observations),
                  "candidate": assess_topology(candidate_data, active["normalizer"], observations)},
              "incremental_transition_structure": {
                  phase: {label: assess_topology(data, active["normalizer"], records)["transition_structure"]
                          for label, data in (("baseline", active["manifold"]), ("candidate", candidate_data))}
                  for phase, records in (("training", train), ("holdout", holdout))},
              "source_sha256": content_digest(observations), "token_migration": migration,
              "cold_state_namespace": active["validation"].get("cold_state_namespace", content_digest({
                  "manifold": content_digest(reference.to_dict()), "normalizer": content_digest(normalizer.to_dict())})),
              "parent_registry_version": active["version"],
              "calibration_mode": "incremental_grid_structure_frozen_normalizer",
              "evidence_scope": "representation_distance_gate_not_prediction_or_causal_validation"}
    version = registry.register(candidate_data, active["normalizer"], report)
    report["candidate_version"] = version
    if accepted:
        registry.activate(version, active["version"])
    return report
