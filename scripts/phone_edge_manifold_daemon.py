#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

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
import fcntl
import json
import os
import statistics
import signal
from pathlib import Path
import subprocess
import sys
import time
import uuid
from typing import Any, Dict, List, Optional

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.manifold_lattice import DynamicalEnergyManifold
from traceform.streaming.edge_manifold_daemon import EdgeManifoldDaemon, EdgeDaemonEvent

from traceform.intervention.closed_loop import ClosedLoopIntervention, MicroActuator, TRIGGER_EVENT_TYPES, should_consider_intervention
from traceform.forecasting.sandbox_simulator import SandboxSimulator, ScenarioIntervention
from traceform.forecasting.continuity import same_stream_segment
from traceform.evolution.model_registry import ModelRegistry
from traceform.probes.digital_sensorium import DigitalSensoriumProbe
from traceform.streaming.tiered_storage import TieredStorage
from traceform.streaming.rolling_ledger import RollingLedger, RotationAwareTail


def prediction_receipt(job, output, context, started, now=None):
    now = time.monotonic() if now is None else now
    status = job.poll()
    if status is None and now - started < 900:
        return None
    if status is None:
        job.kill()
        status = job.wait()
        result = {'event_type': 'CONTINUOUS_SANDBOX_FAILED', 'reason': '900_second_timeout'}
    else:
        try:
            result = json.loads(output.read_text())
            if not isinstance(result, dict) or result.get('job_id') != context['job_id']:
                raise ValueError('worker receipt job mismatch')
            if any(result.get(key) != context[key] for key in ('through_timestamp', 'registry_version')):
                raise ValueError('worker receipt trigger mismatch')
            if status != 0 and result.get('event_type') != 'CONTINUOUS_SANDBOX_FAILED':
                raise ValueError('successful receipt with failed worker exit')
        except (OSError, ValueError) as error:
            result = {'event_type': 'CONTINUOUS_SANDBOX_FAILED', 'reason': 'missing_or_invalid_worker_receipt',
                      'error_type': type(error).__name__}
    return dict(result, **context, runtime_seconds=now - started, exit_code=status,
                completed_timestamp_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                timeout_seconds=900)


def intervention_gate_receipts(store, intervention_loop, payload, ev, inserted, dwell_threshold, timezone_name):
    gate = {'event_type': 'INTERVENTION_GATE', 'candidate_event_type': ev.event_type,
            'timestamp_utc': payload['timestamp_utc'], 'model_version': payload['model_version'],
            'source_mode': payload.get('source_mode'), 'dwell_steps': payload['dwell_steps'],
            'dwell_threshold': dwell_threshold,
            'actuator_snapshot': intervention_loop.actuator.availability_snapshot(),
            'recorded_timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'evidence_scope': 'natural_candidate_gate_diagnostic_not_delivery_or_causal_effect'}
    if not inserted:
        return [dict(gate, status='blocked', reason='duplicate_frame')]
    if not should_consider_intervention(ev.event_type, payload['dwell_steps'], dwell_threshold, intervention_loop.mode):
        return [dict(gate, status='blocked', reason='mode_off' if intervention_loop.mode == 'off' else 'dwell_below_threshold')]
    recent = store.hot_records(limit=2048)
    start = len(recent) - 1
    while start > 0 and same_stream_segment(recent[start - 1], recent[start]):
        start -= 1
    recent = recent[start:]
    gate.update(continuous_history_frames=len(recent), minimum_history_frames=20)
    if len(recent) < 20:
        return [dict(gate, status='blocked', reason='insufficient_continuous_history')]
    intervals = [row['frame_interval_seconds'] for row in recent if row.get('frame_interval_seconds')]
    nominal_step = statistics.median(intervals) if intervals else 30.0
    simulator = SandboxSimulator(step_nominal_seconds=nominal_step, timezone_name=timezone_name).fit_from_records(recent, max_gap_seconds=300)
    instant = datetime.datetime.fromisoformat(payload['timestamp_utc'].replace('Z', '+00:00'))
    comparison = simulator.simulate_counterfactual(
        payload['state_token'], ScenarioIntervention(action_type='CLAMP_DWELL', max_dwell_steps=10),
        horizon_steps=30, num_rollouts=100, high_risk_states={payload['state_token']}, start_time_utc=instant)
    phase = simulator.periodic_kernel.phases(instant)[0]
    decision = intervention_loop.propose(payload, ev.event_type, comparison, int(phase * 4 / (2 * 3.141592653589793)))
    gate['counterfactual_recommended_path'] = comparison.recommended_path
    if decision:
        return [dict(gate, status='decision_logged', reason='policy_decision', trial_id=decision['trial_id'], action=decision['action']), decision]
    return [dict(gate, status='blocked', **intervention_loop.last_proposal_block)]


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
    parser.add_argument("--csd-input-space", choices=["grid", "continuous"], default="grid", help="Continuous mode retains within-token normalized feature variation; not empirical CSD identification")
    parser.add_argument("--buffer-size", type=int, default=60, help="Ring buffer size for CSD estimation")
    parser.add_argument("--dwell-steps", type=int, default=60, help="Dwell alert threshold steps")
    parser.add_argument("--disable-haptics", action="store_true", help="Disable haptic vibration and notification feedback")
    parser.add_argument("--storage-mode", choices=["mirror", "tiered"], default="mirror", help="Tiered mode omits unbounded manifold JSONL output")
    parser.add_argument("--storage-database", type=Path, help="Transactional hot/warm/cold store")
    parser.add_argument("--intervention-mode", choices=["off", "shadow", "active"], default="shadow")
    parser.add_argument("--intervention-actions", nargs="+", choices=["pulse", "notification", "volume_duck"], default=["pulse", "notification"])
    parser.add_argument("--timezone", default="UTC", help="IANA timezone for predictive time kernels")
    parser.add_argument("--model-registry", type=Path, help="Versioned active model bundles and rollback pointer")
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

    registry = ModelRegistry(args.model_registry or args.output_stream.parent / "model_registry")
    active_bundle = registry.active()
    if active_bundle is None:
        baseline_version = registry.register(manifold.to_dict(), normalizer.to_dict(), {"accepted": True, "evidence_scope": "supplied_baseline_not_new_scientific_validation"})
        registry.activate(baseline_version)
        active_bundle = registry.active()
    manifold = DynamicalEnergyManifold.from_dict(active_bundle["manifold"])
    normalizer = MultimodalNormalizer.from_dict(active_bundle["normalizer"])
    active_registry_version = active_bundle["version"]
    last_registry_check = time.monotonic()

    daemon = EdgeManifoldDaemon(
        normalizer=normalizer,
        manifold=manifold,
        ring_buffer_size=args.buffer_size,
        dwell_threshold_steps=args.dwell_steps,
        csd_threshold_sigma=2.5,
        csd_input_space=args.csd_input_space,
        boot_epoch_file=args.output_stream.parent / "boot_epoch.json",
        source_mode="historical_replay" if args.simulate else "live_follow",
    )

    args.output_stream.parent.mkdir(parents=True, exist_ok=True)
    args.events_file.parent.mkdir(parents=True, exist_ok=True)

    # Acquire single-instance process lock to prevent duplicate daemons
    lock_path = args.output_stream.parent / "edge_manifold_daemon.lock"
    pid_path = args.output_stream.parent / "edge_manifold_daemon.pid"
    lock_file = lock_path.open("w")
    try:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except (BlockingIOError, OSError):
        existing_pid = pid_path.read_text(encoding="utf-8").strip() if pid_path.exists() else "unknown"
        print(f"[*] Edge manifold daemon already active (PID={existing_pid}). Exiting gracefully.")
        return 0

    pid_path.write_text(str(os.getpid()), encoding="utf-8")

    # Ensure raw ledger exists before attempting to open
    args.raw_ledger.parent.mkdir(parents=True, exist_ok=True)
    if not args.raw_ledger.exists():
        args.raw_ledger.touch()

    stream_out = args.output_stream.open("a", encoding="utf-8") if args.storage_mode == "mirror" else None
    events_out = RollingLedger(args.events_file, max_bytes=8 * 1024 * 1024, retention_seconds=30 * 86400)
    storage_database = args.storage_database or args.output_stream.parent / "edge_storage.sqlite"
    store = TieredStorage(storage_database)
    last_compaction = time.monotonic()

    processed_count = 0
    total_latency_ms = 0.0
    total_events_count = 0
    intervention_loop = ClosedLoopIntervention(
        args.output_stream.parent / "interventions.sqlite",
        MicroActuator(enabled=not args.disable_haptics, silent_file=args.output_stream.parent / ".silent"),
        mode=args.intervention_mode, actions=args.intervention_actions,
    )


    digital_probe = DigitalSensoriumProbe()
    continuous_job = None
    continuous_job_started = None
    continuous_job_context = None
    continuous_output = args.output_stream.parent / 'continuous_prediction.json'

    def stop_daemon(signum, frame):
        raise KeyboardInterrupt

    previous_stop_handler = signal.signal(signal.SIGTERM, stop_daemon)

    try:
        reader = RotationAwareTail(args.raw_ledger, follow=True) if args.follow else args.raw_ledger.open("r", encoding="utf-8")
        with reader as in_f:
            while True:
                if continuous_job is not None:
                    receipt = prediction_receipt(continuous_job, continuous_output, continuous_job_context, continuous_job_started)
                    if receipt is not None:
                        events_out.append(receipt)
                        continuous_job = None
                line = in_f.read_line() if args.follow else in_f.readline()
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

                if args.follow and in_f.discontinuous:
                    daemon.reset_continuity()
                    in_f.discontinuous = False

                if "digital_context" not in obs and args.follow:
                    wifi_cnt = obs.get("connectivity", {}).get("wifi_ap_count", 0)
                    obs["digital_context"] = digital_probe.sample_digital_interaction_metrics(fallback_wifi_ap_count=wifi_cnt)

                if time.monotonic() - last_registry_check >= 30:
                    updated = registry.active()
                    if updated["version"] != active_registry_version:
                        manifold = DynamicalEnergyManifold.from_dict(updated["manifold"])
                        normalizer = MultimodalNormalizer.from_dict(updated["normalizer"])
                        daemon = EdgeManifoldDaemon(normalizer, manifold, ring_buffer_size=args.buffer_size,
                                                    dwell_threshold_steps=args.dwell_steps,
                                                    source_mode="historical_replay" if args.simulate else "live_follow",
                                                    csd_input_space=args.csd_input_space)
                        daemon.continuity_epoch = int(time.time_ns())
                        active_bundle = updated
                        active_registry_version = updated["version"]
                        events_out.append({"event_type": "MODEL_ACTIVATED", "registry_version": active_registry_version})
                    last_registry_check = time.monotonic()
                payload, events = daemon.process_observation(obs)
                payload["cold_state_namespace"] = active_bundle["validation"].get("cold_state_namespace", daemon.model_version)
                processed_count += 1
                total_latency_ms += payload["inference_ms"]

                inserted = store.append(obs, payload)
                if inserted:
                    for outcome in intervention_loop.observe(payload):
                        events_out.append(outcome)
                if stream_out is not None:
                    stream_out.write(json.dumps(payload) + "\n")
                    stream_out.flush()
                if time.monotonic() - last_compaction >= 3600:
                    store.compact(datetime.datetime.now(datetime.timezone.utc))
                    last_compaction = time.monotonic()

                if events:
                    for ev in events:
                        total_events_count += 1
                        ev_dict = ev.to_dict()
                        events_out.append(ev_dict)
                        print(f"  >>> [{ev.warning_level}] {ev.event_type} at {ev.timestamp_utc}: {ev.message}")

                        if inserted and ev.event_type == 'CSD_TIPPING' and continuous_job is None:
                            continuous_output.unlink(missing_ok=True)
                            continuous_job_started = time.monotonic()
                            continuous_job_context = {'job_id': uuid.uuid4().hex,
                                'through_timestamp': payload['timestamp_utc'], 'registry_version': active_registry_version,
                                'started_timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
                            try:
                                continuous_job = subprocess.Popen([
                                    sys.executable, str(PROJECT_ROOT / 'scripts/predict_continuous_edge.py'),
                                    '--database', str(storage_database),
                                    '--model-registry', str(registry.root),
                                    '--registry-version', active_registry_version,
                                    '--through-timestamp', payload['timestamp_utc'],
                                    '--job-id', continuous_job_context['job_id'],
                                    '--output-file', str(continuous_output),
                                ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                                events_out.append(dict(continuous_job_context, event_type='CONTINUOUS_SANDBOX_STARTED', timeout_seconds=900))
                            except OSError as error:
                                events_out.append({'event_type': 'CONTINUOUS_SANDBOX_FAILED',
                                                   **continuous_job_context, 'runtime_seconds': time.monotonic() - continuous_job_started,
                                                   'completed_timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                                   'reason': 'worker_start_failed', 'error_type': type(error).__name__})

                        if ev.event_type in TRIGGER_EVENT_TYPES:
                            for receipt in intervention_gate_receipts(store, intervention_loop, payload, ev, inserted,
                                                                     args.dwell_steps, args.timezone):
                                events_out.append(receipt)

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
        signal.signal(signal.SIGTERM, previous_stop_handler)
        if continuous_job is not None and continuous_job.poll() is None:
            continuous_job.terminate()
            try:
                continuous_job.wait(timeout=2)
            except subprocess.TimeoutExpired:
                continuous_job.kill()
                continuous_job.wait()
            events_out.append(dict(continuous_job_context, event_type='CONTINUOUS_SANDBOX_FAILED', reason='daemon_shutdown',
                                   runtime_seconds=time.monotonic() - continuous_job_started,
                                   completed_timestamp_utc=datetime.datetime.now(datetime.timezone.utc).isoformat()))
        if stream_out is not None:
            stream_out.close()
        store.close()
        intervention_loop.close()
        try:
            if pid_path.exists():
                pid_path.unlink()
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)
            lock_file.close()
        except Exception:
            pass


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
