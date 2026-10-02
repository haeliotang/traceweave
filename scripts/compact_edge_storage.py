#!/usr/bin/env python3
"""Compact the transactional edge store without rewriting source ledgers."""

import argparse
import datetime
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from traceform.streaming.tiered_storage import TieredStorage


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--now", help="Timezone-aware ISO time; defaults to current UTC")
    parser.add_argument("--export-warm", type=Path)
    parser.add_argument("--export-trajectory", type=Path)
    args = parser.parse_args()
    now = datetime.datetime.fromisoformat(args.now.replace("Z", "+00:00")) if args.now else datetime.datetime.now(datetime.timezone.utc)
    store = TieredStorage(args.database)
    try:
        report = store.compact(now)
        if args.export_warm:
            args.export_warm.parent.mkdir(parents=True, exist_ok=True)
            with args.export_warm.open("w", encoding="utf-8") as output:
                for row in store.warm_records():
                    output.write(json.dumps(row) + "\n")
        if args.export_trajectory:
            args.export_trajectory.parent.mkdir(parents=True, exist_ok=True)
            with args.export_trajectory.open("w", encoding="utf-8") as output:
                for row in store.warm_trajectory_records():
                    output.write(json.dumps(row) + "\n")
        print(json.dumps(report))
    finally:
        store.close()


if __name__ == "__main__":
    main()
