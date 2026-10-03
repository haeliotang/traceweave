#!/usr/bin/env python3
"""Fit a manifold on an evaluated normalizer candidate and register it as a model bundle.

The normalizer evaluation (scripts/evaluate_normalizer_candidate.py) must have passed, and its
report must refer to exactly the active registry normalizer and the supplied candidate file. A new
manifold is then fitted on the same chronological training part and checked on the held-out part:

  coverage          occupied states >= max(min(2, baseline), ceil(coverage_fraction * baseline))
  topography        topographic error <= baseline error + topographic_slack (scale-free adjacency)
  finite_distance   mean energy distance is finite

Mean energy distances of the two models live in different scaled spaces and are not compared.
Only a bundle that passes every gate is accepted, and it is activated only with --activate.
"""
import argparse
import json
import math
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.evaluate_normalizer_candidate import load_hot, params_digest
from traceform.evolution.guarded_calibration import assess_topology
from traceform.evolution.model_registry import ModelRegistry
from traceform.representation.manifold_lattice import DynamicalEnergyManifold
from traceform.representation.normalizer import MultimodalNormalizer, NormalizationParams
from traceform.representation.provenance import content_digest

CALIBRATION_MODE = 'normalizer_refit_robust_range_v2'
SEED = 42


def fail(message):
    print(message, file=sys.stderr)
    raise SystemExit(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sqlite-file', type=Path, required=True)
    parser.add_argument('--registry', type=Path, required=True)
    parser.add_argument('--evaluation-report', type=Path, required=True)
    parser.add_argument('--candidate-normalizer-file', type=Path, required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    parser.add_argument('--activate', action='store_true')
    parser.add_argument('--coverage-fraction', type=float, default=0.5)
    parser.add_argument('--topographic-slack', type=float, default=0.02)
    parser.add_argument('--epochs', type=int, default=6)
    args = parser.parse_args()

    report = json.loads(args.evaluation_report.read_text())
    checks = report['acceptance']['checks']
    if not checks or not all(checks.values()):
        fail('evaluation did not pass: ' + ', '.join(sorted(k for k, v in checks.items() if not v)))
    registry = ModelRegistry(args.registry)
    active = registry.active()
    if active is None:
        fail('registry has no active bundle')
    if content_digest(active['normalizer']) == report['candidate_normalizer_sha256']:
        receipt = {'candidate_version': active['version'], 'baseline_version': active['validation'].get(
            'parent_registry_version'), 'accepted': True, 'activated': False, 'status': 'already_active'}
        args.output_file.parent.mkdir(parents=True, exist_ok=True)
        args.output_file.write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
        print(json.dumps({'status': 'already_active', 'candidate_version': active['version'][:12]}))
        return
    if content_digest(active['normalizer']) != report['baseline_normalizer_sha256']:
        fail('the evaluation was made against a different baseline than the active normalizer')
    candidate_params = NormalizationParams.from_dict(json.loads(args.candidate_normalizer_file.read_text()))
    if params_digest(candidate_params) != report['candidate_normalizer_sha256']:
        fail('the candidate file differs from the candidate that was evaluated')

    observations = [observation for observation, _ in load_hot(args.sqlite_file)]
    split = report['split']['train_records']
    if len(observations) < split + 1:
        fail('stored frames no longer cover the evaluated window')
    train, holdout = observations[:split], observations[split:]

    baseline_manifold = active['manifold']
    candidate_normalizer = MultimodalNormalizer(candidate_params)
    manifold = DynamicalEnergyManifold(
        grid_height=baseline_manifold['grid_height'], grid_width=baseline_manifold['grid_width'],
        kinetic_weight=baseline_manifold['kinetic_weight'], ema_alpha=baseline_manifold['ema_alpha'])
    manifold.fit_discovery_window(train, candidate_normalizer, epochs=args.epochs, seed=SEED)
    candidate_data = manifold.to_dict()
    normalizer_data = candidate_params.to_dict()

    baseline_view = assess_topology(baseline_manifold, active['normalizer'], holdout)
    candidate_view = assess_topology(candidate_data, normalizer_data, holdout)
    needed = max(min(2, baseline_view['occupied_states']),
                 math.ceil(args.coverage_fraction * baseline_view['occupied_states']))
    base_error, cand_error = baseline_view['topographic_error'], candidate_view['topographic_error']
    gates = {
        'coverage': {'passed': candidate_view['occupied_states'] >= needed, 'required': needed,
                     'candidate': candidate_view['occupied_states'], 'baseline': baseline_view['occupied_states']},
        'topography': {'passed': (base_error is None and cand_error is None) or (
            base_error is not None and cand_error is not None and cand_error <= base_error + args.topographic_slack),
                       'baseline_error': base_error, 'candidate_error': cand_error},
        'finite_distance': {'passed': math.isfinite(candidate_view['mean_energy_distance']),
                            'candidate_mean_energy_distance': candidate_view['mean_energy_distance']},
    }
    accepted = all(gate['passed'] for gate in gates.values())
    fidelity = report['replay_fidelity']
    validation = {
        'accepted': accepted,
        'reason': 'evaluation_and_manifold_gates' if accepted else 'manifold_gate_not_met',
        'calibration_mode': CALIBRATION_MODE,
        'parent_registry_version': active['version'],
        'cold_state_namespace': content_digest({'manifold': content_digest(candidate_data),
                                                'normalizer': content_digest(normalizer_data)}),
        'training_records': len(train), 'holdout_records': len(holdout),
        'source_sha256': content_digest(observations),
        'gates': gates,
        'gate_parameters': {'coverage_fraction': args.coverage_fraction,
                            'topographic_slack': args.topographic_slack, 'epochs': args.epochs},
        'normalizer_evaluation': {
            'checks': checks,
            'replay_variant': fidelity['replay_variant'],
            'fidelity_after_warmup': fidelity['fidelity_after_warmup'],
            'fidelity_after_first_restart': fidelity['fidelity_after_first_restart'],
            'pre_restart_short_memory_fidelity': fidelity['pre_restart_short_memory_fidelity'],
            'saturated_before': report['saturation']['baseline']['saturated_count'],
            'saturated_after': report['saturation']['candidate']['saturated_count'],
            'drift_ratio_before': report['forecast']['baseline'].get('drift_mse_ratio_to_persistence'),
            'drift_ratio_after': report['forecast']['candidate'].get('drift_mse_ratio_to_persistence')},
        'evidence_scope': 'representation_candidate_checks_not_prediction_or_causal_validation',
    }
    version = registry.register(candidate_data, normalizer_data, validation)

    receipt = {'candidate_version': version, 'baseline_version': active['version'], 'accepted': accepted,
               'gates': gates, 'gate_parameters': validation['gate_parameters'], 'activated': False}
    if version == active['version']:
        receipt['status'] = 'already_active'
    elif args.activate and accepted:
        registry.activate(version, active['version'])
        receipt.update(activated=True, status='activated')
    else:
        receipt['status'] = 'registered_not_activated'
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(receipt, indent=2, sort_keys=True, allow_nan=False) + '\n')
    print(json.dumps({'status': receipt['status'], 'accepted': accepted,
                      'candidate_version': version[:12]}))


if __name__ == '__main__':
    main()
