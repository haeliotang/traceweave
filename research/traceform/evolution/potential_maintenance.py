# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Bounded weekly drift-projection persistence from the latest intact segment."""

from traceform.dynamics.continuous import ContinuousDynamics
from traceform.forecasting.continuity import same_stream_segment
from traceform.forecasting.evaluation import timestamp
from traceform.representation.provenance import content_digest


def persist_latest_potential(store):
    records = store.hot_records(limit=64)
    if not records:
        return {"saved": False, "reason": "no_retained_frames"}
    latest = records[-1]
    fields = ('model_version', 'model_sha256', 'normalizer_sha256', 'cold_state_namespace')
    segment = [latest]
    for first in reversed(records[:-1]):
        second = segment[-1]
        if (any(first.get(field) != latest.get(field) for field in fields)
                or not same_stream_segment(first, second)
                or not 0 < (timestamp(second) - timestamp(first)).total_seconds() <= 300):
            break
        segment.append(first)
    segment.reverse()
    if len(segment) < 3:
        return {"saved": False, "reason": "fewer_than_two_continuous_pairs", "record_count": len(segment)}
    if any(not record.get('continuous_vector') for record in segment):
        return {"saved": False, "reason": "missing_continuous_coordinates"}
    if not latest.get('normalizer_sha256'):
        return {"saved": False, "reason": "missing_normalizer_binding"}
    model = ContinuousDynamics().fit(segment, max_anchors=8)
    potential = model.infer_potential()
    source_digest = content_digest(segment)
    namespace = latest.get('cold_state_namespace', latest['model_version'])
    store.save_cold_potential(namespace, potential, source_digest)
    return {"saved": True, "namespace": namespace, "model_version": model.model_version,
            "source_sha256": source_digest, "record_count": len(segment),
            "fit_pair_count": model.fit_pair_count, "anchors": len(potential['centers']),
            "edge_residual_rms": potential['edge_residual_rms'],
            "conservative_fraction": potential['conservative_fraction'],
            "scope": "latest_segment_drift_projection_not_bifurcation_or_equilibrium"}
