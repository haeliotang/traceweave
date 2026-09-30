"""Append-only source ledger management for Physical Observation Stack v1."""

import json
import os
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import config
from .episodes import PhysicalEpisode
from .observation import PhysicalObservation
from .transition import PhysicalTransition


def ensure_ledger_dir(ledger_dir: Path = config.DEFAULT_LEDGER_DIR) -> None:
    """Ensures the storage directory exists with proper permissions."""
    ledger_dir.mkdir(parents=True, exist_ok=True)


def _append_line_sync(file_path: Path, line: str) -> None:
    """Appends a single line to a file and fsyncs to ensure durability."""
    ensure_ledger_dir(file_path.parent)
    with file_path.open("a", encoding="utf-8") as f:
        f.write(line + "\n")
        f.flush()
        try:
            os.fsync(f.fileno())
        except OSError:
            pass


def append_observation(obs: PhysicalObservation, ledger_path: Path = config.OBSERVATIONS_LEDGER) -> None:
    """Appends an authoritative PhysicalObservation to the observations ledger."""
    _append_line_sync(ledger_path, obs.to_json())


def append_transition(trans: PhysicalTransition, ledger_path: Path = config.TRANSITIONS_LEDGER) -> None:
    """Appends an authoritative PhysicalTransition to the transitions ledger."""
    _append_line_sync(ledger_path, trans.to_json())


def append_episode(ep: PhysicalEpisode, ledger_path: Path = config.EPISODES_LEDGER) -> None:
    """Appends an authoritative PhysicalEpisode to the episodes ledger."""
    _append_line_sync(ledger_path, ep.to_json())


def update_live_state(state: Dict[str, Any], state_path: Path = config.LIVE_STATE_PATH) -> None:
    """Atomically updates the current physical live state snapshot via rename."""
    ensure_ledger_dir(state_path.parent)
    temp_path = state_path.with_name(f"{state_path.name}.tmp.{os.getpid()}")
    with temp_path.open("w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)
        f.flush()
        try:
            os.fsync(f.fileno())
        except OSError:
            pass
    temp_path.replace(state_path)


def read_live_state(state_path: Path = config.LIVE_STATE_PATH) -> Optional[Dict[str, Any]]:
    """Reads the current live physical state snapshot."""
    if not state_path.exists():
        return None
    try:
        with state_path.open("r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def _read_recent_jsonl(file_path: Path, limit: int = 20) -> List[Dict[str, Any]]:
    """Reads the last N valid lines from a jsonl file, ignoring corrupted tail lines."""
    if not file_path.exists():
        return []

    lines: List[str] = []
    try:
        with file_path.open("r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
    except Exception:
        return []

    results: List[Dict[str, Any]] = []
    for line in reversed(lines):
        line = line.strip()
        if not line:
            continue
        try:
            item = json.loads(line)
            if isinstance(item, dict):
                results.append(item)
                if len(results) >= limit:
                    break
        except Exception:
            # Tolerates incomplete or truncated lines at tail
            continue

    return list(reversed(results))


def read_recent_observations(limit: int = 20, ledger_path: Path = config.OBSERVATIONS_LEDGER) -> List[PhysicalObservation]:
    raw_list = _read_recent_jsonl(ledger_path, limit=limit)
    return [PhysicalObservation.from_dict(d) for d in raw_list]


def read_recent_transitions(limit: int = 20, ledger_path: Path = config.TRANSITIONS_LEDGER) -> List[PhysicalTransition]:
    raw_list = _read_recent_jsonl(ledger_path, limit=limit)
    return [PhysicalTransition.from_dict(d) for d in raw_list]


def read_recent_episodes(limit: int = 20, ledger_path: Path = config.EPISODES_LEDGER) -> List[PhysicalEpisode]:
    raw_list = _read_recent_jsonl(ledger_path, limit=limit)
    return [PhysicalEpisode.from_dict(d) for d in raw_list]
