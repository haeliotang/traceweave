"""Source-bound continuous forward predictions from the latest intact segment."""

import statistics

from traceform.dynamics.continuous import ContinuousDynamics
from traceform.forecasting.continuity import same_stream_segment
from traceform.forecasting.evaluation import timestamp
from traceform.forecasting.sandbox_simulator import SandboxSimulator
from traceform.representation.manifold_lattice import DynamicalEnergyManifold
from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.provenance import content_digest


def predict_latest_segment(records, bundle, horizon_steps=30, num_rollouts=300, hurst=None):
    if not records:
        raise ValueError('latest segment requires two continuous pairs')
    manifold = DynamicalEnergyManifold.from_dict(bundle['manifold'])
    normalizer = MultimodalNormalizer.from_dict(bundle['normalizer'])
    model_hash, normalizer_hash = content_digest(manifold.to_dict()), content_digest(normalizer.to_dict())
    version = content_digest({'manifold': model_hash, 'normalizer': normalizer_hash})
    latest = records[-1]
    if (latest.get('model_sha256') != model_hash or latest.get('normalizer_sha256') != normalizer_hash
            or latest.get('model_version') != version):
        raise ValueError('latest coordinates do not match model bundle binding')
    segment = [latest]
    for first in reversed(records[-64:-1]):
        second = segment[-1]
        if (not same_stream_segment(first, second)
                or any(first.get(field) != latest.get(field) for field in
                       ('model_sha256', 'normalizer_sha256', 'cold_state_namespace'))
                or not 0 < (timestamp(second) - timestamp(first)).total_seconds() <= 300):
            break
        segment.append(first)
    segment.reverse()
    if len(segment) < 3:
        raise ValueError('latest segment requires two continuous pairs')
    model = ContinuousDynamics().fit(segment, max_anchors=8)
    potential = model.infer_potential()
    energies = {node.state_token: model.potential_at(node.center_x, potential)['energy']
                for node in manifold.nodes}
    intervals = [(timestamp(second) - timestamp(first)).total_seconds()
                 for first, second in zip(segment, segment[1:])]
    simulator = SandboxSimulator(step_nominal_seconds=statistics.median(intervals), energy_map=energies)
    simulator.fit_from_transition_matrix({node.state_token: {node.state_token: 1}
                                          for node in manifold.nodes})
    branch = simulator.simulate_continuous_rollouts(
        model, latest, None, horizon_steps=horizon_steps, num_rollouts=num_rollouts, hurst=hurst,
        phase_quantize=lambda vector, velocity: manifold.quantize_phase(vector, velocity)[0],
        velocity_ema_alpha=manifold.ema_alpha)
    return {'event_type': 'CONTINUOUS_SANDBOX_PREDICTION', 'branch': branch.to_dict(),
            'initial_timestamp_utc': latest['timestamp_utc'], 'model_version': version,
            'source_sha256': content_digest(segment), 'record_count': len(segment),
            'fit_pair_count': model.fit_pair_count,
            'noise_sampler': 'fitted_empirical_gaussian_factor' if hurst is None else 'frozen_cholesky_fractional',
            'potential_edge_residual_rms': potential['edge_residual_rms'],
            'energy_scope': 'fitted_graph_projection_not_thermodynamic_or_actuator_energy',
            'scope': 'local_forward_prediction_not_holdout_validation_or_causal_intervention_effect'}
