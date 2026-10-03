#!/usr/bin/env python3
"""Build the per-dimension observability registry from the dataflow analysis (and optionally a runtime ledger)."""

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from traceform.representation.feature_dataflow import analyze_source
from traceform.representation.observability_registry import build_registry, producer_literals

PRODUCER_ROOTS = ('pixel_physical', 'traceform/streaming', 'traceform/probes')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ledger-file', type=Path, help='device-derived runtime ledger (output stays local)')
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    dataflow = analyze_source((ROOT / 'traceform/representation/normalizer.py').read_text())
    ledger = json.loads(args.ledger_file.read_text()) if args.ledger_file else None
    registry = build_registry(dataflow, producer_literals([ROOT / root for root in PRODUCER_ROOTS]), ledger)
    registry['producer_roots'] = list(PRODUCER_ROOTS)
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(registry, indent=2, sort_keys=True) + '\n')
    print(json.dumps(registry['summary']))


if __name__ == '__main__':
    main()
