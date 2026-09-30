#!/usr/bin/env python3
"""
Traceform Phone Edge Stream Daemon.

Designed for on-device execution on GrapheneOS (Pixel 6a Termux runtime)
or local edge development. Performs continuous streaming quantization
on incoming raw physical observation records with sub-millisecond latency.

Usage:
  python3 scripts/phone_edge_stream_daemon.py --simulate
  python3 scripts/phone_edge_stream_daemon.py --follow
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import time

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from traceform.streaming.edge_quantizer import EdgeStreamQuantizer


def main() -> None:
    parser = argparse.ArgumentParser(description="Traceform On-Device Edge Stream Daemon")
    parser.add_argument("--spec-file", type=Path, default=REPO_ROOT / "artifacts" / "representation_v1" / "representation_spec.json")
    parser.add_argument("--raw-ledger", type=Path, default=REPO_ROOT / "data" / "raw" / ".pixel_sensorium" / "physical_observations.jsonl")
    parser.add_argument("--output-stream", type=Path, default=REPO_ROOT / "data" / "realtime_state_stream.jsonl")
    parser.add_argument("--simulate", action="store_true", help="Simulate real-time streaming over existing raw ledger frames")
    parser.add_argument("--max-frames", type=int, default=100, help="Max frames to process in simulate mode")
    args = parser.parse_args()

    print("=" * 70)
    print(" Traceform On-Device Edge Stream Quantizer Daemon")
    print(" Lightweight Edge Inference on GrapheneOS / Termux")
    print("=" * 70)

    if not args.spec_file.is_file():
        print(f"Error: Missing representation specification at {args.spec_file}")
        sys.exit(1)

    print(f"[*] Initializing EdgeStreamQuantizer from {args.spec_file.name}...")
    t0 = time.time()
    quantizer = EdgeStreamQuantizer.from_spec_file(args.spec_file)
    print(f"    Loaded {quantizer.w1_vocab_size} core states in {(time.time() - t0)*1000:.1f}ms.")

    args.output_stream.parent.mkdir(parents=True, exist_ok=True)

    if args.simulate:
        print(f"[*] Simulating real-time streaming on {args.raw_ledger.name} (limit={args.max_frames})...")
        processed = 0
        latencies = []

        with open(args.raw_ledger, "r", encoding="utf-8") as in_f, open(args.output_stream, "w", encoding="utf-8") as out_f:
            for line in in_f:
                if not line.strip():
                    continue
                obs = json.loads(line.strip())

                t_frame_0 = time.time()
                token, sig, topo = quantizer.process_raw_frame(obs, allow_new_states=True)
                dt_ms = (time.time() - t_frame_0) * 1000.0
                latencies.append(dt_ms)

                record = {
                    "timestamp_utc": obs.get("timestamp_utc"),
                    "observation_id": obs.get("observation_id"),
                    "state_token": token,
                    "signature": sig,
                    "latency_ms": round(dt_ms, 3),
                }
                out_f.write(json.dumps(record) + "\n")
                processed += 1
                if processed >= args.max_frames:
                    break

        avg_lat = sum(latencies) / len(latencies) if latencies else 0.0
        max_lat = max(latencies) if latencies else 0.0
        print(f"[+] Processed {processed} live frames.")
        print(f"    Average On-Device Latency: {avg_lat:.3f} ms/frame")
        print(f"    Peak Latency:             {max_lat:.3f} ms/frame")
        print(f"    Output written to:        {args.output_stream}")
        print("\nEdge Stream Daemon simulation successful.")


if __name__ == "__main__":
    main()
