#!/usr/bin/env python3
"""
Traceform Edge Manifold Daemon Runner for Pixel 6a (Termux).

Executes continuous on-device DETM quantization and online CSD tipping detection:
1. Normalizes physical sensor frames using MultimodalNormalizer.
2. Quantizes frames onto the 2D Dynamical Energy Manifold (DETM) using Riemannian energy geodesics (< 0.1 ms).
3. Evaluates online sliding-window CSD tipping (variance surge + AR(1) surge) and attractor dwell.
4. Triggers Termux vibration / notification resonance hooks on critical phase transitions.

Usage:
  # Simulation mode over 100 historical frames:
  python3 scripts/phone_edge_manifold_daemon.py --simulate --max-frames 100

  # Live continuous background tailing mode on Pixel 6a:
  python3 scripts/phone_edge_manifold_daemon.py --follow
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Any, Dict, List, Optional

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.manifold_lattice import DynamicalEnergyManifold
from traceform.streaming.edge_manifold_daemon import EdgeManifoldDaemon, EdgeDaemonEvent


def trigger_termux_haptic(pattern_ms: int = 150) -> None:
    """Invokes Termux vibration API if running on Android/Termux."""
    try:
        subprocess.run(["termux-vibrate", "-d", str(pattern_ms)], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


def trigger_termux_notification(title: str, content: str) -> None:
    """Invokes Termux notification API if running on Android/Termux."""
    try:
        subprocess.run(
            ["termux-notification", "--title", title, "--content", content, "--id", "traceform_csd"],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        pass


def main():
    parser = argparse.ArgumentParser(description="Traceform Edge Manifold Daemon for Pixel 6a.")
    parser.add_argument(
        "--raw-ledger",
        type=Path,
        default=PROJECT_ROOT / "data" / "raw" / ".pixel_sensorium" / "physical_observations.jsonl",
        help="Path to raw sensor observations JSONL ledger",
    )
    parser.add_argument(
        "--normalizer-file",
        type=Path,
        default=PROJECT_ROOT / "artifacts" / "representation_v1" / "normalizer_v1.json",
        help="Path to pre-trained MultimodalNormalizer spec",
    )
    parser.add_argument(
        "--model-file",
        type=Path,
        default=PROJECT_ROOT / "artifacts" / "manifold_dynamics_v1" / "manifold_lattice_model.json",
        help="Path to pre-trained DETM model file",
    )
    parser.add_argument(
        "--output-stream",
        type=Path,
        default=PROJECT_ROOT / "data" / "realtime_manifold_stream.jsonl",
        help="Output path for quantized manifold stream",
    )
    parser.add_argument(
        "--events-file",
        type=Path,
        default=PROJECT_ROOT / "data" / "manifold_events.jsonl",
        help="Output path for CSD tipping and dwell alert events",
    )
    parser.add_argument("--simulate", action="store_true", help="Replay historical ledger rather than waiting")
    parser.add_argument("--follow", action="store_true", help="Tail file continuously in real time")
    parser.add_argument("--max-frames", type=int, default=100, help="Maximum frames to process in simulation mode")
    parser.add_argument("--buffer-size", type=int, default=60, help="Ring buffer size for CSD estimation")
    parser.add_argument("--dwell-steps", type=int, default=60, help="Dwell alert threshold steps")
    args = parser.parse_args()

    print("=" * 76)
    print(" Traceform Edge Manifold Daemon (DETM & Online CSD Tipping)")
    print(f" Mode: {'SIMULATION' if args.simulate else 'LIVE FOLLOW'} | Target: {args.raw_ledger}")
    print("=" * 76)

    # 1. Load Normalizer
    normalizer = None
    if args.normalizer_file.is_file():
        with args.normalizer_file.open("r", encoding="utf-8") as f:
            d = json.load(f)
            normalizer = MultimodalNormalizer.from_dict(d.get("normalization_parameters", d))
        print(f"[*] Loaded Normalizer from: {args.normalizer_file}")
    else:
        rep_spec = PROJECT_ROOT / "artifacts" / "representation_v1" / "representation_spec.json"
        if rep_spec.is_file():
            with rep_spec.open("r", encoding="utf-8") as f:
                d = json.load(f)
                normalizer = MultimodalNormalizer.from_dict(d["normalization_parameters"])
            print(f"[*] Loaded Normalizer parameters from: {rep_spec}")
        else:
            print("[!] Error: No frozen normalizer found.")
            sys.exit(1)

    # 2. Load DETM Model
    if args.model_file.is_file():
        with args.model_file.open("r", encoding="utf-8") as f:
            manifold = DynamicalEnergyManifold.from_dict(json.load(f))
        print(f"[*] Loaded DETM Model ({manifold.height}x{manifold.width}, {len(manifold.nodes)} nodes) from: {args.model_file}")
    else:
        print(f"[!] Error: Model file not found at {args.model_file}")
        sys.exit(1)

    daemon = EdgeManifoldDaemon(
        normalizer=normalizer,
        manifold=manifold,
        ring_buffer_size=args.buffer_size,
        dwell_threshold_steps=args.dwell_steps,
        csd_threshold_sigma=2.5,
    )

    args.output_stream.parent.mkdir(parents=True, exist_ok=True)
    args.events_file.parent.mkdir(parents=True, exist_ok=True)

    stream_out = args.output_stream.open("a", encoding="utf-8")
    events_out = args.events_file.open("a", encoding="utf-8")

    processed_count = 0
    total_latency_ms = 0.0
    total_events_count = 0

    try:
        with args.raw_ledger.open("r", encoding="utf-8") as in_f:
            if args.follow:
                in_f.seek(0, os.SEEK_END)

            while True:
                line = in_f.readline()
                if not line:
                    if args.simulate:
                        break
                    time.sleep(0.5)
                    continue

                line_str = line.strip()
                if not line_str:
                    continue

                try:
                    obs = json.loads(line_str)
                except Exception:
                    continue

                payload, events = daemon.process_observation(obs)
                processed_count += 1
                total_latency_ms += payload["inference_ms"]

                stream_out.write(json.dumps(payload) + "\n")
                stream_out.flush()

                if events:
                    for ev in events:
                        total_events_count += 1
                        ev_dict = ev.to_dict()
                        events_out.write(json.dumps(ev_dict) + "\n")
                        events_out.flush()
                        print(f"  >>> [{ev.warning_level}] {ev.event_type} at {ev.timestamp_utc}: {ev.message}")

                        if ev.warning_level in ["CRITICAL", "ELEVATED"]:
                            trigger_termux_haptic(pattern_ms=250)
                            trigger_termux_notification(
                                title=f"Traceform {ev.event_type}",
                                content=ev.message,
                            )

                if processed_count % 25 == 0 or processed_count == 1:
                    avg_lat = total_latency_ms / processed_count
                    print(
                        f"[*] [Frame {processed_count:05d}] Token: {payload['state_token']} | "
                        f"Coord: {payload['grid_coord']} | Dwell: {payload['dwell_steps']} | "
                        f"Lat: {payload['inference_ms']:.3f} ms (Avg: {avg_lat:.3f} ms)"
                    )

                if args.simulate and processed_count >= args.max_frames:
                    print(f"[*] Reached simulation limit ({args.max_frames} frames). Exiting.")
                    break

    except KeyboardInterrupt:
        print("\n[*] Daemon interrupted by user.")
    finally:
        stream_out.close()
        events_out.close()

    avg_lat = total_latency_ms / max(1, processed_count)
    print("\n" + "=" * 76)
    print(f" [✓] Edge Manifold Daemon Finished: {processed_count} frames processed.")
    print(f"     Average Quantization Latency: {avg_lat:.3f} ms / frame")
    print(f"     Total Resonance Events Fired: {total_events_count}")
    print(f"     Output Stream: {args.output_stream}")
    print(f"     Events Ledger: {args.events_file}")
    print("=" * 76)


if __name__ == "__main__":
    main()
