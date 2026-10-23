"""Join static dataflow, runtime input evidence and the native source index per dimension.

The ledger classifies how much evidence each of the 128 dimensions has that its
inputs were supplied and varied in stored frames, and what a native port would
have to do. It is an evidence ledger, not measurement validation: a dimension
whose inputs vary can still be a proxy, and the tiers never assert that a
quantity was physically measured.
"""
from __future__ import annotations

import collections
from typing import Any, Dict, List

SATURATION_THRESHOLD = 0.5  # share of frames pinned at the +/-10 robust-scaling bound
LOCATED = {'literal_assignment_found', 'name_reference_only'}
TIER_BY_RUNTIME_CLASS = {
    'all_listed_inputs_present_and_varying': 'supplied_inputs_vary',
    'partial_inputs': 'proxy_from_supplied_inputs',
    'inputs_absent_or_constant': 'no_informative_input',
    'constant_output': 'no_informative_input',
    'no_listed_inputs': 'no_informative_input',
}


def _action(tier: str, native_status: str) -> str:
    if tier == 'no_informative_input':
        return 'do_not_port_as_measurement'
    if native_status in LOCATED:
        return 'located_verify_on_hardware'
    return 'port_formula_and_inputs' if tier == 'supplied_inputs_vary' else 'port_proxy_with_proxy_label'


def build_ledger(evidence: Dict[str, Any], native_index: Dict[str, Any]) -> Dict[str, Any]:
    runtime = {item['feature']: item for item in evidence['features']}
    native = {item['feature']: item['static_status'] for item in native_index['features']}
    if set(runtime) != set(native):
        raise ValueError('runtime evidence and native index describe different feature sets')
    entries: List[Dict[str, Any]] = []
    for name in sorted(runtime, key=lambda n: runtime[n]['index']):
        item = runtime[name]
        tier = TIER_BY_RUNTIME_CLASS[item['runtime_class']]
        entries.append({
            'index': item['index'],
            'feature': name,
            'claim_tier': tier,
            'native_status': native[name],
            'native_action': _action(tier, native[name]),
            'runtime_class': item['runtime_class'],
            'static_input_class': item['input_class'],
            'driving_observation_inputs': list(item['varying_present_inputs']),
            'never_present_inputs': list(item['never_present_inputs']),
            'constant_present_inputs': list(item['constant_present_inputs']),
            'output_constant': item['output_constant'],
            'clip_fraction': item.get('output', {}).get('clip_fraction', 0.0),
            'saturated_at_clip': item.get('output', {}).get('clip_fraction', 0.0) >= SATURATION_THRESHOLD,
        })
    cross: Dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    for entry in entries:
        cross[entry['native_status']][entry['claim_tier']] += 1
    driving = sorted({path for entry in entries if entry['claim_tier'] != 'no_informative_input'
                      for path in entry['driving_observation_inputs']})
    return {
        'scope': 'input_evidence_tiers_and_native_port_actions_not_measurement_validation',
        'frames_analyzed': evidence['frames']['analyzed'],
        'features': entries,
        'summary': {
            'claim_tier_counts': dict(sorted(collections.Counter(e['claim_tier'] for e in entries).items())),
            'native_action_counts': dict(sorted(collections.Counter(e['native_action'] for e in entries).items())),
            'native_status_by_tier': {status: dict(sorted(counts.items())) for status, counts in sorted(cross.items())},
            'driving_observation_inputs': driving,
            'driving_observation_input_count': len(driving),
            'saturated_dimension_count': sum(e['saturated_at_clip'] for e in entries),
            'effective_dimension': evidence.get('effective_dimension'),
        },
        'tier_meanings': {
            'supplied_inputs_vary': 'every listed input appeared and changed; the quantity itself is not validated',
            'proxy_from_supplied_inputs': 'some alternative direct inputs never appeared; value comes from the supplied subset',
            'no_informative_input': 'inputs absent or constant, or the output never changed; carries no information here',
        },
        'saturation_note': 'saturated_at_clip marks outputs pinned at the +/-10 scaling bound in at least half of the frames; '
                           'their inputs may still vary but the stored dimension no longer follows them',
        'limitations': [
            'tiers describe stored inputs, not what the sensors physically measured',
            'a single window of one device cannot show generalization across days or subjects',
        ],
    }
