#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Write the static observation-to-feature dataflow analysis for the normalizer."""

import argparse
import collections
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from traceform.representation.feature_dataflow import analyze_source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--normalizer-file', type=Path, default=ROOT / 'research/traceform/representation/normalizer.py')
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    result = analyze_source(args.normalizer_file.read_text())
    try:
        result['normalizer_file'] = str(args.normalizer_file.resolve().relative_to(ROOT))
    except ValueError:
        result['normalizer_file'] = str(args.normalizer_file)
    result['class_counts'] = dict(sorted(collections.Counter(
        item['input_class'] for item in result['features']).items()))
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'features': result['feature_count'], 'class_counts': result['class_counts']}))


if __name__ == '__main__':
    main()
