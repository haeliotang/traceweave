#!/usr/bin/env python3
"""Audit local continuous-fit inputs without fitting or exporting vectors."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from traceform.forecasting.continuity import same_stream_segment
from traceform.forecasting.evaluation import timestamp


def audit(records, dimension=128, max_gap_seconds=300):
    missing, dimensions, legacy_dimensions = Counter(), Counter(), Counter()
    eligible = pairs = 0
    coordinates = set()
    previous = None
    for record in records:
        reasons = []
        vector = record.get('continuous_vector')
        if not isinstance(vector, list) or not vector:
            reasons.append('continuous_vector_missing')
        else:
            dimensions[str(len(vector))] += 1
            if len(vector) != dimension:
                reasons.append('dimension_mismatch')
            if any(not isinstance(v, (int, float)) or isinstance(v, bool) or not math.isfinite(v) for v in vector):
                reasons.append('nonfinite_or_nonnumeric_vector')
        legacy = record.get('normalized_features')
        if isinstance(legacy, list):
            legacy_dimensions[str(len(legacy))] += 1
        for field in ('model_sha256', 'normalizer_sha256'):
            if not isinstance(record.get(field), str) or not re.fullmatch('[0-9a-f]{64}', record[field]):
                reasons.append(field + '_missing_or_invalid')
        for field in ('model_version', 'boot_id', 'continuity_epoch', 'source_mode', 'state_token'):
            if record.get(field) is None or record.get(field) == '':
                reasons.append(field + '_missing')
        try:
            instant = timestamp(record)
        except (ValueError, TypeError, KeyError, AttributeError):
            instant = None
            reasons.append('timestamp_invalid')
        missing.update(reasons)
        if not reasons:
            eligible += 1
            coordinates.add(tuple(record[field] for field in ('model_version', 'model_sha256', 'normalizer_sha256')))
            if previous is not None:
                prior, prior_time = previous
                gap = (instant - prior_time).total_seconds()
                if (0 < gap <= max_gap_seconds and same_stream_segment(prior, record)
                        and all(prior[field] == record[field] for field in ('model_sha256', 'normalizer_sha256'))):
                    pairs += 1
            previous = record, instant
        else:
            previous = None
    return {'required_dimension': dimension, 'eligible_records': eligible, 'eligible_adjacent_pairs': pairs,
            'rejection_counts': dict(missing), 'continuous_dimension_counts': dict(dimensions),
            'legacy_normalized_dimension_counts': dict(legacy_dimensions),
            'coordinate_binding_count': len(coordinates), 'ready_for_fit': pairs >= 2 and len(coordinates) == 1,
            'scope': 'input_readiness_only_not_empirical_accuracy_memory_identification_or_scientific_acceptance'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--records-file', type=Path, required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    payload = args.records_file.read_bytes()
    records = [json.loads(line) for line in payload.splitlines() if line.strip()]
    result = audit(records)
    result.update(record_count=len(records), source_sha256=hashlib.sha256(payload).hexdigest())
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
