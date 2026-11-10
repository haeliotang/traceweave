#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Aggregate per-field and per-dimension runtime evidence from a stored SQLite hot tier (read-only)."""

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from traceform.representation.feature_runtime_evidence import collect


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sqlite-file', type=Path, required=True)
    parser.add_argument('--dataflow-file', type=Path, required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    args = parser.parse_args()
    report = collect(args.sqlite_file, json.loads(args.dataflow_file.read_text()))
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'frames': report['frames']['analyzed'], 'rejected': report['frames']['rejected'],
                      'runtime_class_counts': report['runtime_class_counts']}))


if __name__ == '__main__':
    main()
