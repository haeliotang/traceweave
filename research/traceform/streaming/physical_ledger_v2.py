"""Rolling persistence adapter for the v2 physical acquisition fork."""

from collections import deque
import json
from pathlib import Path

from pixel_physical import ledger as frozen_ledger
from traceform.streaming.physical_observation_v2 import PhysicalObservation
from traceform.streaming.rolling_ledger import RollingLedger


def append_observation(observation, ledger_path):
    RollingLedger(ledger_path, timestamp_field='timestamp_utc').append(observation.to_dict())


def append_transition(transition, ledger_path):
    RollingLedger(ledger_path, timestamp_field='timestamp_utc').append(transition.to_dict())


def append_episode(episode, ledger_path):
    RollingLedger(ledger_path, timestamp_field='end_time_utc').append(episode.to_dict())


def read_recent_observations(limit, ledger_path):
    if limit <= 0:
        return []
    path = Path(ledger_path)
    records = deque(maxlen=limit)
    with RollingLedger(path).lock():
        paths = sorted(path.parent.glob(path.name + '.segment.*')) + ([path] if path.exists() else [])
        for segment in paths:
            with segment.open('r', encoding='utf-8') as source:
                for line in source:
                    if not line.endswith('\n'):
                        continue
                    try:
                        value = json.loads(line)
                    except ValueError:
                        continue
                    if isinstance(value, dict):
                        records.append(value)
    return [PhysicalObservation.from_dict(record) for record in records]


read_live_state = frozen_ledger.read_live_state
update_live_state = frozen_ledger.update_live_state
