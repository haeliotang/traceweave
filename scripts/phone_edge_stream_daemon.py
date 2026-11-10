#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Traceform Phone Edge Stream Daemon.

Designed for on-device execution on GrapheneOS (Pixel 6a Termux runtime)
or local edge development. Performs continuous streaming quantization
on incoming raw physical observation records with sub-millisecond latency
and triggers ambient resonance events (dwell alerts, entropy spikes) in real time.

Usage:
  python3 scripts/phone_edge_stream_daemon.py --simulate --max-frames 200
  python3 scripts/phone_edge_stream_daemon.py --follow
"""

from __future__ import annotations

import argparse
from collections import deque
import json
import math
from pathlib import Path
import signal
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from traceform.streaming.edge_quantizer import EdgeStreamQuantizer
from traceform.intervention.resonance_hooks import AmbientResonanceEngine, ResonanceEvent


def compute_sliding_entropy(tokens: deque[str]) -> float:
    """Computes Shannon entropy in bits over a sliding token buffer."""
    if not tokens:
        return 0.0
    total = len(tokens)
    counts: Dict[str, int] = {}
    for t in tokens:
        counts[t] = counts.get(t, 0) + 1
    ent = 0.0
    for cnt in counts.values():
        p = cnt / total
        if p > 0:
            ent -= p * math.log2(p)
    return round(ent, 4)


class EdgeDaemon:
    def __init__(
        self,
        quantizer: EdgeStreamQuantizer,
        resonance_engine: AmbientResonanceEngine,
        output_stream_path: Path,
        events_path: Path,
        buffer_size: int = 20,
    ):
        self.quantizer = quantizer
        self.resonance_engine = resonance_engine
        self.output_stream_path = output_stream_path
        self.events_path = events_path
        self.buffer_size = buffer_size
        self.recent_tokens: deque[str] = deque(maxlen=buffer_size)
        self.processed_count = 0
        self.running = True

        self.output_stream_path.parent.mkdir(parents=True, exist_ok=True)
        self.events_path.parent.mkdir(parents=True, exist_ok=True)

    def process_observation(self, obs: Dict[str, Any]) -> Tuple[Dict[str, Any], Optional[ResonanceEvent]]:
        """Processes a single raw physical frame with sub-millisecond latency."""
        t_start = time.time()
        token, sig, topo = self.quantizer.process_raw_frame(obs, allow_new_states=True)
        latency_ms = round((time.time() - t_start) * 1000.0, 3)

        self.recent_tokens.append(token)
        entropy = compute_sliding_entropy(self.recent_tokens)

        self.processed_count += 1
        ts_utc = obs.get("timestamp_utc", "")
        obs_id = obs.get("observation_id", f"obs-{self.processed_count}")

        # Check resonance hooks
        event = self.resonance_engine.process_step(
            step_index=self.processed_count,
            timestamp_utc=ts_utc,
            state_token=token,
            regime_token="R01",
            sliding_entropy=entropy,
        )

        record = {
            "step_index": self.processed_count,
            "timestamp_utc": ts_utc,
            "observation_id": obs_id,
            "state_token": token,
            "signature": sig,
            "sliding_entropy_bits": entropy,
            "latency_ms": latency_ms,
        }

        return record, event

    def run_simulate(self, raw_ledger_path: Path, max_frames: int = 100) -> Dict[str, Any]:
        """Runs batch simulation over existing historical ledger frames."""
        print(f"[*] Simulating real-time streaming on {raw_ledger_path.name} (limit={max_frames})...")
        latencies: List[float] = []
        events_fired: List[ResonanceEvent] = []

        with open(raw_ledger_path, "r", encoding="utf-8") as in_f, \
             open(self.output_stream_path, "w", encoding="utf-8") as out_f, \
             open(self.events_path, "w", encoding="utf-8") as evt_f:

            for line in in_f:
                if not line.strip():
                    continue
                obs = json.loads(line.strip())
                rec, evt = self.process_observation(obs)
                latencies.append(rec["latency_ms"])

                out_f.write(json.dumps(rec) + "\n")

                if evt:
                    events_fired.append(evt)
                    evt_f.write(json.dumps(evt.to_dict()) + "\n")
                    print(f"    [RESONANCE TRIGGER] {evt.trigger_type.value}: state={evt.current_state_token}, dwell={evt.consecutive_dwell_steps}, msg={evt.payload.get('message')}")

                if self.processed_count >= max_frames:
                    break

        avg_lat = sum(latencies) / len(latencies) if latencies else 0.0
        max_lat = max(latencies) if latencies else 0.0

        print(f"\n[+] Simulation Complete:")
        print(f"    - Frames Processed:        {self.processed_count}")
        print(f"    - Average Edge Latency:    {avg_lat:.3f} ms/frame")
        print(f"    - Peak Edge Latency:       {max_lat:.3f} ms/frame")
        print(f"    - Resonance Events Fired:  {len(events_fired)}")
        print(f"    - Realtime Stream Path:    {self.output_stream_path}")
        print(f"    - Resonance Events Path:   {self.events_path}")

        return {
            "frames_processed": self.processed_count,
            "average_latency_ms": avg_lat,
            "peak_latency_ms": max_lat,
            "events_fired_count": len(events_fired),
        }

    def run_follow(self, raw_ledger_path: Path, poll_interval: float = 0.5) -> None:
        """Tails a live sensor ledger file, streaming new frames as they are appended."""
        print(f"[*] Following live ledger at {raw_ledger_path} (polling every {poll_interval}s)...")
        print("    Press Ctrl+C to terminate daemon cleanly.\n")

        with open(raw_ledger_path, "r", encoding="utf-8") as in_f, \
             open(self.output_stream_path, "a", encoding="utf-8") as out_f, \
             open(self.events_path, "a", encoding="utf-8") as evt_f:

            # Seek to end of file initially
            in_f.seek(0, 2)

            while self.running:
                line = in_f.readline()
                if not line:
                    time.sleep(poll_interval)
                    continue

                line = line.strip()
                if not line:
                    continue

                try:
                    obs = json.loads(line)
                    rec, evt = self.process_observation(obs)
                    out_f.write(json.dumps(rec) + "\n")
                    out_f.flush()

                    print(f"[{rec['timestamp_utc']}] Quantized: {rec['state_token']} (lat={rec['latency_ms']:.3f}ms, H={rec['sliding_entropy_bits']:.2f}b)")

                    if evt:
                        evt_f.write(json.dumps(evt.to_dict()) + "\n")
                        evt_f.flush()
                        print(f"  >>> [ALERT] {evt.trigger_type.value}: {evt.payload.get('message')}")
                except Exception as e:
                    print(f"[!] Parse error on line: {e}", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description="Traceform On-Device Edge Stream Daemon")
    parser.add_argument("--spec-file", type=Path, default=REPO_ROOT / "artifacts" / "representation_v1" / "representation_spec.json")
    parser.add_argument("--raw-ledger", type=Path, default=REPO_ROOT / "data" / "raw" / ".pixel_sensorium" / "physical_observations.jsonl")
    parser.add_argument("--output-stream", type=Path, default=REPO_ROOT / "data" / "realtime_state_stream.jsonl")
    parser.add_argument("--events-file", type=Path, default=REPO_ROOT / "data" / "resonance_events.jsonl")
    parser.add_argument("--simulate", action="store_true", help="Simulate real-time streaming over existing raw ledger frames")
    parser.add_argument("--follow", action="store_true", help="Continuously tail the raw ledger for live sensor stream")
    parser.add_argument("--max-frames", type=int, default=150, help="Max frames to process in simulate mode")
    parser.add_argument("--max-dwell", type=int, default=30, help="Max consecutive dwell steps before prolonged dwell trigger")
    parser.add_argument("--entropy-threshold", type=float, default=2.0, help="Entropy threshold for high entropy flux trigger")
    args = parser.parse_args()

    print("=" * 70)
    print(" Traceform On-Device Edge Stream Quantizer Daemon")
    print(" Ambient Resonance & Real-Time Dynamical Closed Loop")
    print("=" * 70)

    if not args.spec_file.is_file():
        print(f"Error: Missing representation specification at {args.spec_file}")
        sys.exit(1)

    print(f"[*] Initializing EdgeStreamQuantizer from {args.spec_file.name}...")
    t0 = time.time()
    quantizer = EdgeStreamQuantizer.from_spec_file(args.spec_file)
    print(f"    Loaded {quantizer.w1_vocab_size} core states in {(time.time() - t0)*1000:.1f}ms.")

    resonance_engine = AmbientResonanceEngine(
        max_regime_dwell_steps=args.max_dwell,
        entropy_spike_threshold=args.entropy_threshold,
    )

    daemon = EdgeDaemon(
        quantizer=quantizer,
        resonance_engine=resonance_engine,
        output_stream_path=args.output_stream,
        events_path=args.events_file,
    )

    def sig_handler(sig, frame):
        print("\n[*] Interrupted by user. Shutting down daemon cleanly...")
        daemon.running = False
        sys.exit(0)

    signal.signal(signal.SIGINT, sig_handler)
    signal.signal(signal.SIGTERM, sig_handler)

    if args.follow:
        daemon.run_follow(args.raw_ledger)
    else:
        # Default or --simulate mode
        daemon.run_simulate(args.raw_ledger, max_frames=args.max_frames)


if __name__ == "__main__":
    main()
