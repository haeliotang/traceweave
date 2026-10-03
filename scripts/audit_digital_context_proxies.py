#!/usr/bin/env python3
"""Check stored digital_context fields against the probe's fixed formulas (read-only, aggregates only)."""

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from traceform.representation.proxy_identities import check_digital_context_identities


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sqlite-file', type=Path, required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    report = check_digital_context_identities(args.sqlite_file)
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'frames': report['frames_with_digital_context'],
                      'identities': {k: f"{v['matches']}/{v['checked']}" for k, v in report['identities'].items()}}))


if __name__ == '__main__':
    main()
