#!/usr/bin/env python3
"""Pixel Sensorium — Physical Observation Control and Diagnostic Client v1."""

import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

# Ensure /root is in sys.path
if "/root" not in sys.path:
    sys.path.insert(0, "/root")

from pixel_physical import config, ledger
from pixel_physical.acquisition import PhysicalAcquisitionEngine


def format_status_terminal(data: Dict[str, Any]) -> str:
    obs = data.get("latest_observation", {})
    trans = data.get("latest_transition")
    ep = data.get("active_episode", {})
    health = data.get("health", {})

    lines: List[str] = [
        "========================================================================",
        "PIXEL SENSORIUM PHYSICAL OBSERVATION STACK v1",
        "========================================================================",
        f"Timestamp:  {data.get('updated_at_utc', 'N/A')}",
        f"Protocol:   {data.get('protocol', config.PROTOCOL)}",
        "",
        "--- CURRENT PHYSICAL FRAME ---",
    ]

    # Motion
    m = obs.get("motion", {})
    lines.append(f"MOTION:        Energy={m.get('motion_energy')} | StationaryLikelihood={m.get('stationary_likelihood')} | Steps={m.get('step_count')}")

    # Place
    p = obs.get("place", {})
    if "latitude" in p:
        lines.append(f"PLACE:         Cell={p.get('spatial_cell')} | Lat={p.get('latitude')}, Lon={p.get('longitude')} (±{p.get('accuracy_m')}m) | Provider={p.get('provider')}")
    else:
        lines.append(f"PLACE:         {p.get('status', 'UNAVAILABLE')}")

    # Environment
    env = obs.get("environment", {})
    lines.append(f"ENVIRONMENT:   Light={env.get('ambient_light_lux')} lx | Pressure={env.get('pressure_hpa')} hPa | Temp={env.get('device_temp_c')} °C")

    # Connectivity
    c = obs.get("connectivity", {})
    lines.append(f"CONNECTIVITY:  WiFi={c.get('wifi_connected')} (fp={c.get('wifi_fingerprint')}, rssi={c.get('wifi_rssi')} dBm, {c.get('wifi_ap_count')} APs) | Cell={c.get('cellular_network_type')}")

    # Device
    d = obs.get("device_relation", {})
    lines.append(f"DEVICE:        Battery={d.get('battery_percentage')}% ({d.get('status')}, {d.get('plugged')}) | Temp={d.get('battery_temp_c')} °C | Headset={d.get('wired_headset')}")

    # Audio
    a = obs.get("audio_context", {})
    lines.append(f"AUDIO CONTEXT: RMS={a.get('rms_loudness')} | ZCR={a.get('zero_crossing_rate')} | Centroid={a.get('spectral_centroid_hz')} Hz | Silence={a.get('silence_likelihood')}")

    lines.append("")
    lines.append("--- TRANSITION & EPISODE ---")
    if trans:
        lines.append(f"Last Transition: {trans.get('transition_id')} | Score={trans.get('transition_score')} | Dominant={trans.get('dominant_changes')}")
        lines.append(f"                 Summary: {trans.get('transition_summary')}")
    else:
        lines.append("Last Transition: None detected yet")

    if ep:
        lines.append(f"Active Episode:  {ep.get('episode_id')} | Started: {ep.get('start_time_utc')} | Obs Count: {len(ep.get('evidence_observation_ids', []))}")
        lines.append(f"                 Dominant Modalities: {ep.get('dominant_modalities')}")
    else:
        lines.append("Active Episode:  None")

    lines.append("")
    lines.append("--- MODALITY HEALTH & TELEMETRY ---")
    for mod, h in health.items():
        lines.append(f"  {mod:<16} Status={h.get('status'):<12} Success={h.get('successful_samples'):<3} Fail={h.get('failed_samples'):<2} Latency={h.get('last_latency_ms')} ms")

    lines.append("========================================================================")
    return "\n".join(lines)


def cmd_status(args) -> int:
    state = ledger.read_live_state()
    if not state:
        # If no live state yet, perform a quick sample to initialize
        engine = PhysicalAcquisitionEngine()
        obs, trans, ep = engine.sample_once(force_audio=False, force_location=True)
        state = ledger.read_live_state()

    if args.json:
        print(json.dumps(state, indent=2))
    else:
        print(format_status_terminal(state or {}))
    return 0


def cmd_sample(args) -> int:
    print("Sampling physical observation frame...")
    engine = PhysicalAcquisitionEngine()
    obs, trans, ep = engine.sample_once(force_audio=True, force_location=True)
    if args.json:
        payload = {
            "observation": obs.to_dict(),
            "transition": trans.to_dict() if trans else None,
            "active_episode": ep.to_dict(),
        }
        print(json.dumps(payload, indent=2))
    else:
        print(f"Acquired: {obs.observation_id} at {obs.timestamp_utc}")
        print(f"Motion Energy: {obs.motion.get('motion_energy')}, Stationary: {obs.motion.get('stationary_likelihood')}")
        print(f"Place Cell:    {obs.place.get('spatial_cell')}")
        print(f"Environment:   {obs.environment.get('ambient_light_lux')} lx, {obs.environment.get('pressure_hpa')} hPa")
        print(f"Connectivity:  WiFi connected={obs.connectivity.get('wifi_connected')}, fp={obs.connectivity.get('wifi_fingerprint')}")
        print(f"Device:        Battery {obs.device_relation.get('battery_percentage')}%, {obs.device_relation.get('plugged')}")
        print(f"Audio Context: RMS={obs.audio_context.get('rms_loudness')}, Silence={obs.audio_context.get('silence_likelihood')}")
        if trans:
            print(f"TRANSITION DETECTED: {trans.transition_id} (Score={trans.transition_score}) -> {trans.transition_summary}")
        else:
            print("Transition: None (state stable)")
        print(f"Active Episode: {ep.episode_id} ({len(ep.evidence_observation_ids)} observations)")
    return 0


def cmd_recent(args) -> int:
    obs_list = ledger.read_recent_observations(limit=args.limit)
    if args.json:
        print(json.dumps([o.to_dict() for o in obs_list], indent=2))
    else:
        print(f"--- Last {len(obs_list)} Physical Observations ---")
        for o in obs_list:
            m = o.motion
            p = o.place
            print(f"[{o.timestamp_utc}] {o.observation_id} | MotionEnergy={m.get('motion_energy')} | Cell={p.get('spatial_cell')} | Light={o.environment.get('ambient_light_lux')} lx")
    return 0


def cmd_transitions(args) -> int:
    t_list = ledger.read_recent_transitions(limit=args.limit)
    if args.json:
        print(json.dumps([t.to_dict() for t in t_list], indent=2))
    else:
        print(f"--- Last {len(t_list)} Physical Transitions ---")
        for t in t_list:
            print(f"[{t.timestamp_utc}] {t.transition_id} | Score={t.transition_score} | Dominant={t.dominant_changes}")
            print(f"  Summary: {t.transition_summary}")
    return 0


def cmd_episodes(args) -> int:
    e_list = ledger.read_recent_episodes(limit=args.limit)
    if args.json:
        print(json.dumps([e.to_dict() for e in e_list], indent=2))
    else:
        print(f"--- Last {len(e_list)} Physical Episodes ---")
        for e in e_list:
            print(f"[{e.start_time_utc} -> {e.end_time_utc}] {e.episode_id} | Reason: {e.episode_boundary_reason}")
            print(f"  Dominant: {e.dominant_modalities} | Obs count: {len(e.evidence_observation_ids)} | Transitions: {len(e.transition_ids)}")
    return 0


RUNNER_LOCK = config.DEFAULT_LEDGER_DIR / "physical_acquisition.lock"
RUNNER_PID = config.DEFAULT_LEDGER_DIR / "physical_acquisition.pid"
RUNNER_SCRIPT = Path("/root/pixel_physical_runner_v1.py")


def is_runner_alive() -> Tuple[bool, Optional[int]]:
    if not RUNNER_PID.exists():
        return False, None
    try:
        pid = int(RUNNER_PID.read_text(encoding="utf-8").strip())
        os.kill(pid, 0)
        return True, pid
    except (ValueError, OSError):
        return False, None


def cmd_start(args) -> int:
    alive, pid = is_runner_alive()
    if alive:
        res = {"ok": True, "action": "start", "already_running": True, "pid": pid}
        if args.json:
            print(json.dumps(res))
        else:
            print(f"[physicalctl] Physical acquisition runner is already running (PID={pid}).")
        return 0

    if not RUNNER_SCRIPT.exists():
        res = {"ok": False, "error": f"Runner script missing: {RUNNER_SCRIPT}"}
        if args.json:
            print(json.dumps(res))
        else:
            print(f"[physicalctl] ERROR: {res['error']}")
        return 1

    proc = subprocess.Popen(
        [sys.executable, str(RUNNER_SCRIPT)],
        start_new_session=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    # Wait briefly for runner to acquire lock and write PID
    for _ in range(10):
        time.sleep(0.2)
        alive, pid = is_runner_alive()
        if alive:
            break

    res = {"ok": alive, "action": "start", "pid": pid}
    if args.json:
        print(json.dumps(res))
    else:
        if alive:
            print(f"[physicalctl] Started physical acquisition runner (PID={pid}).")
        else:
            print("[physicalctl] ERROR: Failed to confirm runner startup.")
    return 0 if alive else 1


def cmd_stop(args) -> int:
    alive, pid = is_runner_alive()
    if not alive:
        # Clean up stale pid file if present
        if RUNNER_PID.exists():
            try:
                RUNNER_PID.unlink()
            except Exception:
                pass
        res = {"ok": True, "action": "stop", "already_stopped": True}
        if args.json:
            print(json.dumps(res))
        else:
            print("[physicalctl] Physical acquisition runner is already stopped.")
        return 0

    try:
        os.kill(pid, signal.SIGTERM)
    except OSError as e:
        res = {"ok": False, "error": f"Failed to signal PID {pid}: {e}"}
        if args.json:
            print(json.dumps(res))
        else:
            print(f"[physicalctl] ERROR: {res['error']}")
        return 1

    stopped = False
    for _ in range(25):
        time.sleep(0.2)
        alive, _ = is_runner_alive()
        if not alive:
            stopped = True
            break

    if not stopped:
        try:
            os.kill(pid, signal.SIGKILL)
            time.sleep(0.2)
        except OSError:
            pass
        if RUNNER_PID.exists():
            try:
                RUNNER_PID.unlink()
            except Exception:
                pass
        stopped = True

    res = {"ok": True, "action": "stop", "pid": pid, "clean_stop": stopped}
    if args.json:
        print(json.dumps(res))
    else:
        print(f"[physicalctl] Stopped physical acquisition runner (PID={pid}).")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="physicalctl",
        description="Pixel Sensorium Physical Observation Control and Diagnostics v1",
    )
    subparsers = parser.add_subparsers(dest="command", help="Physical stack commands")

    # start
    p_start = subparsers.add_parser("start", help="Start the background physical acquisition runner")
    p_start.add_argument("--json", action="store_true", help="Emit structured JSON output")
    p_start.set_defaults(func=cmd_start)

    # stop
    p_stop = subparsers.add_parser("stop", help="Stop the background physical acquisition runner")
    p_stop.add_argument("--json", action="store_true", help="Emit structured JSON output")
    p_stop.set_defaults(func=cmd_stop)

    # status
    p_status = subparsers.add_parser("status", help="Show physical stack status and current frame")
    p_status.add_argument("--json", action="store_true", help="Emit structured JSON output")
    p_status.set_defaults(func=cmd_status)

    # sample
    p_sample = subparsers.add_parser("sample", help="Trigger an immediate multimodal observation frame")
    p_sample.add_argument("--json", action="store_true", help="Emit structured JSON output")
    p_sample.set_defaults(func=cmd_sample)

    # recent
    p_recent = subparsers.add_parser("recent", help="Show recent observation frames")
    p_recent.add_argument("-n", "--limit", type=int, default=10, help="Number of observations to show")
    p_recent.add_argument("--json", action="store_true", help="Emit structured JSON output")
    p_recent.set_defaults(func=cmd_recent)

    # transitions
    p_trans = subparsers.add_parser("transitions", help="Show recent detected physical transitions")
    p_trans.add_argument("-n", "--limit", type=int, default=10, help="Number of transitions to show")
    p_trans.add_argument("--json", action="store_true", help="Emit structured JSON output")
    p_trans.set_defaults(func=cmd_transitions)

    # episodes
    p_ep = subparsers.add_parser("episodes", help="Show recent physical episodes")
    p_ep.add_argument("-n", "--limit", type=int, default=10, help="Number of episodes to show")
    p_ep.add_argument("--json", action="store_true", help="Emit structured JSON output")
    p_ep.set_defaults(func=cmd_episodes)

    args = parser.parse_args()
    if not hasattr(args, "func"):
        parser.print_help()
        return 1
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
