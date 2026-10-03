#!/usr/bin/env python3
"""Join dataflow-based runtime evidence with the native source index into a per-dimension ledger."""

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from traceform.representation.feature_ledger import build_ledger


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-file', type=Path, required=True)
    parser.add_argument('--native-index-file', type=Path,
                        default=ROOT / 'artifacts/native_android_v1/native_128_feature_source_index.json')
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    ledger = build_ledger(json.loads(args.evidence_file.read_text()), json.loads(args.native_index_file.read_text()))
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(ledger, indent=2, sort_keys=True) + '\n')
    print(json.dumps(ledger['summary']['claim_tier_counts']))


if __name__ == '__main__':
    main()
