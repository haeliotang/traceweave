"""Offline helpers for evaluating a candidate normalizer against stored observations.

Raw continuous features do not depend on the scaling parameters, so each stored
observation is replayed once and then scaled under every parameter set being
compared. The replay mirrors the daemon's continuity handling: a long gap, a
backwards clock or a missing timestamp resets only the normalizer's geo window.
"""
from __future__ import annotations

import copy
import datetime
from typing import Any, Dict, Iterable, List, Optional, Sequence

from traceform.representation.normalizer import MultimodalNormalizer, NormalizationParams

MAX_GAP_SECONDS = 300.0
SATURATION_FRACTION = 0.5
CLIP_BOUND = 10.0


def _seconds(observation: Dict[str, Any]) -> Optional[float]:
    text = observation.get('timestamp_utc')
    if not isinstance(text, str):
        return None
    try:
        moment = datetime.datetime.fromisoformat(text.replace('Z', '+00:00'))
    except ValueError:
        return None
    return moment.timestamp() if moment.tzinfo is not None else None


def replay_raw_features(observations: Sequence[Dict[str, Any]],
                        normalizer: Optional[MultimodalNormalizer] = None,
                        restart_before: Optional[Iterable[int]] = None) -> List[Dict[str, float]]:
    """Raw continuous feature maps in order, with the daemon's gap rule applied.

    ``restart_before`` lists frame indices at which the daemon process was restarted, i.e. the
    normalizer started from empty state (a restart is not visible to the gap rule alone).
    """
    normalizer = normalizer or MultimodalNormalizer()
    normalizer.reset_state()
    restarts = set(restart_before or ())
    previous: Optional[float] = None
    results: List[Dict[str, float]] = []
    for index, observation in enumerate(observations):
        if index in restarts:
            normalizer.reset_state()
        instant = _seconds(observation)
        if previous is not None and instant is not None:
            gap = instant - previous
            if gap > MAX_GAP_SECONDS or gap < 0:
                normalizer.reset_geo_window()
        elif instant is None:
            normalizer.reset_geo_window()
        previous = instant
        raw, _ = normalizer.extract_raw_features(copy.deepcopy(observation))
        results.append(raw)
    return results


def vectors_from_raw(raws: Sequence[Dict[str, float]], params: NormalizationParams) -> List[List[float]]:
    scaler = MultimodalNormalizer(params)
    return [scaler.scale_raw(raw, params) for raw in raws]


def saturation_summary(vectors: Sequence[Sequence[float]], names: Sequence[str]) -> Dict[str, Any]:
    """Share of frames pinned at the +/-10 bound per dimension, and dimensions that never change."""
    count = len(vectors)
    if count == 0:
        raise ValueError('no vectors')
    clip: Dict[str, float] = {}
    constant: List[str] = []
    saturated: List[str] = []
    for index, name in enumerate(names):
        column = [row[index] for row in vectors]
        fraction = sum(1 for value in column if abs(value) >= CLIP_BOUND - 1e-9) / count
        clip[name] = round(fraction, 6)
        if fraction >= SATURATION_FRACTION:
            saturated.append(name)
        if max(column) - min(column) < 1e-9:
            constant.append(name)
    return {'clip_fraction': clip, 'saturated_dimensions': saturated, 'constant_dimensions': constant,
            'saturated_count': len(saturated), 'constant_count': len(constant), 'frames': count}
