# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Which of the 128 dimensions the current acquisition can inform, with the basis for each claim.

Two kinds of evidence are kept apart:

* static: no producer code anywhere in the acquisition packages contains the input field's name
  as a string literal, so no frame can ever carry it (a code fact, valid for every device);
* runtime: the field was absent or constant in one device window (a window fact only).

Statuses describe input evidence for the current acquisition, never that a quantity was measured.
"""
from __future__ import annotations

import ast
import collections
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set

UNOBSERVABLE = 'unobservable_in_current_acquisition'
PROXY = 'proxy_from_observed_inputs'
OBSERVED = 'observed_inputs'
NOT_ASSESSED = 'not_assessed_at_runtime'

STATIC_BASIS = 'static_no_producer'
RUNTIME_BASIS = 'runtime_absent_or_constant_device_window'
TIER_STATUS = {
    'supplied_inputs_vary': OBSERVED,
    'proxy_from_supplied_inputs': PROXY,
    'no_informative_input': UNOBSERVABLE,
}


def producer_literals(roots: Iterable[Path]) -> Set[str]:
    """Every string constant in the Python files under the given roots (a superset of produced keys)."""
    literals: Set[str] = set()
    for root in roots:
        files = [Path(root)] if Path(root).is_file() else sorted(Path(root).rglob('*.py'))
        for path in files:
            if '__pycache__' in path.parts:
                continue
            for node in ast.walk(ast.parse(path.read_text())):
                if isinstance(node, ast.Constant) and isinstance(node.value, str):
                    literals.add(node.value)
    return literals


def _inputs(feature: Dict[str, Any]) -> List[str]:
    paths = sorted(set(feature.get('observation_inputs', [])) | set(feature.get('history_observation_inputs', [])))
    return paths or sorted(feature.get('control_observation_inputs', []))


def build_registry(dataflow: Dict[str, Any], literals: Set[str],
                   ledger: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    tiers: Dict[str, Dict[str, Any]] = {}
    if ledger is not None:
        tiers = {item['feature']: item for item in ledger['features']}
        if set(tiers) != {item['feature'] for item in dataflow['features']}:
            raise ValueError('ledger and dataflow describe different feature sets')
    entries: List[Dict[str, Any]] = []
    for feature in dataflow['features']:
        inputs = _inputs(feature)
        unproduced = [path for path in inputs if path.split('.')[-1] not in literals]
        entry: Dict[str, Any] = {'index': feature['index'], 'feature': feature['feature'],
                                 'unproduced_inputs': unproduced}
        if inputs and len(unproduced) == len(inputs):
            entry.update(status=UNOBSERVABLE, basis=STATIC_BASIS)
        elif ledger is None:
            entry.update(status=NOT_ASSESSED, basis='static_only')
        else:
            status = TIER_STATUS[tiers[feature['feature']]['claim_tier']]
            entry.update(status=status, basis=RUNTIME_BASIS if status == UNOBSERVABLE else 'runtime_input_evidence')
        if ledger is not None:  # saturation is independent of how observability was decided
            entry['normalization_saturated'] = bool(tiers[feature['feature']].get('saturated_at_clip', False))
            entry['clip_fraction'] = tiers[feature['feature']].get('clip_fraction', 0.0)
        entries.append(entry)
    counts = collections.Counter(entry['status'] for entry in entries)
    return {
        'scope': 'input_evidence_for_current_acquisition_not_measurement_validation',
        'dataflow_source_sha256': dataflow.get('source_sha256'),
        'runtime_evidence': ledger is not None,
        'features': entries,
        'summary': {
            'status_counts': dict(sorted(counts.items())),
            'statically_unobservable_count': sum(1 for e in entries if e['basis'] == STATIC_BASIS),
            'normalization_saturated_count': sum(1 for e in entries if e.get('normalization_saturated')),
        },
        'status_meanings': {
            OBSERVED: 'every listed input was supplied and varied; the quantity itself is not validated',
            PROXY: 'direct inputs never appeared; the value comes from a formula over the supplied inputs',
            UNOBSERVABLE: 'no usable input: either no producer exists (static) or it was absent/constant in the window',
            NOT_ASSESSED: 'static view only; runtime behaviour not judged',
        },
    }


VALID_STATUSES = {OBSERVED, PROXY, UNOBSERVABLE, NOT_ASSESSED}


def select_indices(registry: Dict[str, Any], statuses: Set[str], exclude_saturated: bool = False) -> List[int]:
    """Dimension indices whose registry status is in ``statuses`` (optionally without saturated ones)."""
    unknown = set(statuses) - VALID_STATUSES
    if unknown:
        raise ValueError(f'unknown status: {sorted(unknown)[0]}')
    chosen = [entry['index'] for entry in registry['features']
              if entry['status'] in statuses and not (exclude_saturated and entry.get('normalization_saturated'))]
    if not chosen:
        raise ValueError('registry selection is empty')
    return sorted(chosen)
