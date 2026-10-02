"""Versioned frame acquisition with server-bounded location requests.

Forked from frozen v1 to preserve its source and sensing schema.
"""

import hashlib
import json
import time
from typing import Any, Dict, Optional, Tuple

from pixel_physical import audio_features, config, connectivity, device_state, sensors
from traceform.streaming import physical_location_v2 as location


from pixel_physical.observation import PhysicalObservation


def build_observation_id(timestamp_utc: str, monotonic_ns: int, raw_payload: str) -> str:
    """Generates a unique deterministic observation ID."""
    h = hashlib.sha256(f"{timestamp_utc}:{monotonic_ns}:{raw_payload}".encode("utf-8")).hexdigest()
    ts_compact = int(time.time() * 1000)
    return f"obs-{ts_compact}-{h[:12]}"


def acquire_observation_frame(
    include_audio: bool = True,
    include_location: bool = True,
    last_coords: Optional[Tuple[float, float]] = None,
) -> PhysicalObservation:
    """
    Acquires an authoritative multimodal physical observation frame from physical sensors.
    """
    t0_mono = time.monotonic_ns()
    ts_utc = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()) + f".{int((time.time() % 1) * 1e6):06d}Z"
    latencies: Dict[str, float] = {}
    availability: Dict[str, str] = {}

    # 1. Hardware Sensors (Motion & Environment)
    t_start = time.perf_counter()
    motion_dict, env_dict, motion_avail, env_avail = sensors.sample_hardware_sensors()
    lat_hw = round((time.perf_counter() - t_start) * 1000, 2)
    latencies["motion"] = lat_hw
    latencies["environment"] = lat_hw
    availability["motion"] = motion_avail
    availability["environment"] = env_avail

    # 2. Location (Place)
    if include_location:
        t_start = time.perf_counter()
        place_dict, place_avail = location.sample_location(last_coords=last_coords)
        latencies["place"] = round((time.perf_counter() - t_start) * 1000, 2)
        availability["place"] = place_avail
    else:
        place_dict = {"status": "NOT_SAMPLED"}
        latencies["place"] = 0.0
        availability["place"] = "NOT_SAMPLED"

    # 3. Connectivity (Wi-Fi, Cellular)
    t_start = time.perf_counter()
    conn_dict, conn_avail = connectivity.sample_connectivity()
    latencies["connectivity"] = round((time.perf_counter() - t_start) * 1000, 2)
    availability["connectivity"] = conn_avail

    # 4. Device Relation (Battery, Audio Route)
    t_start = time.perf_counter()
    dev_dict, dev_avail = device_state.sample_device_state()
    latencies["device_relation"] = round((time.perf_counter() - t_start) * 1000, 2)
    availability["device_relation"] = dev_avail

    # 5. Audio Context
    if include_audio:
        t_start = time.perf_counter()
        audio_dict, audio_avail = audio_features.sample_audio_context()
        latencies["audio_context"] = round((time.perf_counter() - t_start) * 1000, 2)
        availability["audio_context"] = audio_avail
    else:
        audio_dict = {"status": "NOT_SAMPLED"}
        latencies["audio_context"] = 0.0
        availability["audio_context"] = "NOT_SAMPLED"

    preview_payload = f"{ts_utc}:{motion_dict.get('motion_energy')}:{place_dict.get('spatial_cell')}"
    obs_id = build_observation_id(ts_utc, t0_mono, preview_payload)

    return PhysicalObservation(
        protocol=config.PROTOCOL,
        observation_id=obs_id,
        timestamp_utc=ts_utc,
        monotonic_ns=t0_mono,
        availability=availability,
        motion=motion_dict,
        place=place_dict,
        environment=env_dict,
        connectivity=conn_dict,
        device_relation=dev_dict,
        audio_context=audio_dict,
        sample_latencies_ms=latencies,
    )
