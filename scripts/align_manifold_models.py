#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Compare two locally supplied manifolds without a network exchange."""

import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from traceform.dynamics.manifold_alignment import align_metric_spaces, node_distance_matrix
from traceform.dynamics.alignment_statement import quantize_alignment_witness


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("first", type=Path)
    parser.add_argument("second", type=Path)
    parser.add_argument("--output-file", type=Path, required=True)
    parser.add_argument("--integer-statement", action="store_true", help="Include local exact arithmetic and quantization error summary; no cryptographic proof.")
    args = parser.parse_args()
    payloads = [path.read_bytes() for path in (args.first, args.second)]
    models = [json.loads(payload) for payload in payloads]
    models = [model.get("manifold", model) for model in models]
    metrics = [node_distance_matrix(model) for model in models]
    report = align_metric_spaces(*metrics)
    if args.integer_statement:
        _, report["integer_statement"] = quantize_alignment_witness(*metrics, report["coupling"])
    report["source_sha256"] = [hashlib.sha256(payload).hexdigest() for payload in payloads]
    report["privacy"] = {"network_calls": 0, "raw_observations_exported": False,
                         "cryptographic_zero_knowledge": False,
                         "warning": "Metric structure and correspondence may reveal personal habits; local access is required."}
    report["normalization"] = "Each distance matrix divided by its diameter; uniform node masses."
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("squared_gw_objective", "marginal_error", "converged")}))


if __name__ == "__main__":
    main()
