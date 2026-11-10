#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Fit a continuous model from version-bound vectors and simulate future paths."""

import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from traceform.dynamics.continuous import ContinuousDynamics
from traceform.forecasting.evaluation import timestamp
from traceform.streaming.tiered_storage import TieredStorage


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--records-file", type=Path)
    source.add_argument("--storage-database", type=Path)
    parser.add_argument("--output-file", type=Path, required=True)
    parser.add_argument("--duration-seconds", type=float, default=30)
    parser.add_argument("--dt-seconds", type=float, default=1)
    parser.add_argument("--rollouts", type=int, default=20)
    parser.add_argument("--bandwidth", type=float, default=1)
    parser.add_argument("--hurst", type=float, help="Use additive fractional noise with this assumed Hurst exponent")
    parser.add_argument("--memory-records-file", type=Path, help="Independent later regular segment for residual-memory diagnostics")
    parser.add_argument("--memory-method", choices=("regular", "irregular"), default="regular")
    parser.add_argument("--memory-coordinate", type=int, default=0)
    parser.add_argument("--persist-potential-namespace", help="Save the fitted graph projection in an existing cold lineage of the source database")
    args = parser.parse_args()
    if args.persist_potential_namespace and not args.storage_database:
        parser.error("potential persistence requires --storage-database")
    if args.records_file:
        payload = args.records_file.read_bytes()
        records = [json.loads(line) for line in payload.splitlines() if line.strip()]
        source_hash = hashlib.sha256(payload).hexdigest()
    else:
        if not args.storage_database.is_file():
            parser.error("storage database does not exist")
        store = TieredStorage(args.storage_database)
        try:
            records = store.hot_records()
        finally:
            store.close()
        source_hash = hashlib.sha256(json.dumps(records, sort_keys=True).encode()).hexdigest()
    model = ContinuousDynamics(args.bandwidth).fit(records)
    memory = None
    if args.memory_records_file:
        memory_payload = args.memory_records_file.read_bytes()
        memory_records = [json.loads(line) for line in memory_payload.splitlines() if line.strip()]
        if not memory_records or min(timestamp(row) for row in memory_records) <= max(timestamp(row) for row in records):
            parser.error("memory records must be strictly later than all training records")
        memory = (model.estimate_memory(memory_records) if args.memory_method == "regular"
                  else model.estimate_memory_irregular(memory_records, args.memory_coordinate))
        memory["source_sha256"] = hashlib.sha256(memory_payload).hexdigest()
    rollout = model.rollout if args.hurst is None else model.rollout_fractional
    options = {} if args.hurst is None else {"hurst": args.hurst}
    result = rollout(records[-1]["continuous_vector"], args.duration_seconds,
                     args.dt_seconds, args.rollouts, **options)
    result["model"] = model.to_dict()
    result["graph_potential"] = model.infer_potential()
    result["initial_potential"] = model.potential_at(records[-1]["continuous_vector"], result["graph_potential"])
    result["source_sha256"] = source_hash
    if args.persist_potential_namespace:
        store = TieredStorage(args.storage_database)
        try:
            store.save_cold_potential(args.persist_potential_namespace, result["graph_potential"], source_hash)
        finally:
            store.close()
        result["potential_persistence"] = {"namespace": args.persist_potential_namespace,
                                           "scope": "local_source_bound_graph_projection_not_bifurcation_identification"}
    if memory is not None:
        result["memory_diagnostic"] = memory
    result["limitations"] = ["Finite-lag local moment estimates need empirical holdout validation.",
                             "A fitted drift need not be the gradient of a potential.",
                             "A supplied Hurst exponent is an assumption, not an empirical memory estimate.",
                             "Fractional simulation freezes diffusion at the initial vector; dense temporal factorization is not an edge performance claim."]
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"dimension": model.dimension, "fit_pair_count": model.fit_pair_count,
                      "output_file": str(args.output_file.resolve())}))


if __name__ == "__main__":
    main()
