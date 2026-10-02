#!/usr/bin/env python3
"""Compare fixed forecasting models on a chronological JSONL holdout."""

import argparse
import datetime
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from traceform.forecasting.evaluation import evaluate_forecasts, timestamp


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--records-file", type=Path, required=True)
    parser.add_argument("--split-time", required=True, help="Timezone-aware ISO timestamp")
    parser.add_argument("--timezone", default="UTC")
    parser.add_argument("--output-file", type=Path, required=True)
    parser.add_argument("--segment", type=int, help="Explicit zero-based chronological segment; never sorts replayed records")
    args = parser.parse_args()
    source = args.records_file.read_bytes()
    records = [json.loads(line) for line in source.splitlines() if line.strip()]
    original_count = len(records)
    if args.segment is not None:
        boundaries = [0] + [index for index in range(1, len(records)) if timestamp(records[index]) < timestamp(records[index - 1])] + [len(records)]
        if not 0 <= args.segment < len(boundaries) - 1:
            parser.error("segment index outside source")
        records = records[boundaries[args.segment]:boundaries[args.segment + 1]]
    split = datetime.datetime.fromisoformat(args.split_time.replace("Z", "+00:00"))
    report = evaluate_forecasts(records, split, args.timezone)
    report["source_sha256"] = hashlib.sha256(source).hexdigest()
    report["source_path"] = str(args.records_file.resolve())
    report["selected_segment"] = args.segment
    report["original_record_count"] = original_count
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
