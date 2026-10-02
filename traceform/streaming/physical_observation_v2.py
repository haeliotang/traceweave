"""Versioned frame acquisition with server-bounded location requests.

Forked from frozen v1 to preserve its source and sensing schema.
"""

import hashlib
from dataclasses import dataclass, field
import json
import time
from typing import Any, Dict, Optional, Tuple

from pixel_physical import config
from traceform.streaming import physical_audio_v2 as audio_features
from traceform.streaming import physical_connectivity_v2 as connectivity
from traceform.streaming import physical_device_state_v2 as device_state
from traceform.streaming import physical_sensors_v2 as sensors
from traceform.streaming import physical_location_v2 as location


from pixel_physical.observation import PhysicalObservation as FrozenPhysicalObservation


@dataclass
class PhysicalObservation(FrozenPhysicalObservation):
    feature_provenance: Dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, value):
        base = FrozenPhysicalObservation.from_dict(value)
        return cls(**base.to_dict(), feature_provenance=value.get('feature_provenance', {}))


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
    sensor_fields = {'motion': motion_dict.pop('_field_provenance', {}),
                     'environment': env_dict.pop('_field_provenance', {})}
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
    sensor_fields['connectivity'] = conn_dict.pop('_field_provenance', {})
    latencies["connectivity"] = round((time.perf_counter() - t_start) * 1000, 2)
    availability["connectivity"] = conn_avail

    # 4. Device Relation (Battery, Audio Route)
    t_start = time.perf_counter()
    dev_dict, dev_avail = device_state.sample_device_state()
    sensor_fields['device_relation'] = dev_dict.pop('_field_provenance', {})
    latencies["device_relation"] = round((time.perf_counter() - t_start) * 1000, 2)
    availability["device_relation"] = dev_avail

    # 5. Audio Context
    if include_audio:
        t_start = time.perf_counter()
        audio_dict, audio_avail = audio_features.sample_audio_context()
        sensor_fields['audio_context'] = audio_dict.pop('_field_provenance', {})
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
        feature_provenance={name: {
            'adapter': adapter,
            'status': availability[name],
            'measurement_scope': 'adapter_return_not_per_field_direct_measurement',
            'limitations': limitations,
            'fields': sensor_fields.get(name, {}),
        } for name, adapter, limitations in (
            ('motion', 'traceform.streaming.physical_sensors_v2.sample_hardware_sensors',
             ['derived_motion_summaries', 'missing_gyroscope_may_be_zero_filled']),
            ('environment', 'traceform.streaming.physical_sensors_v2.sample_hardware_sensors',
             ['group_availability_does_not_establish_each_sensor']),
            ('place', 'traceform.streaming.physical_location_v2.sample_location',
             ['location_request_and_age_are_recorded_in_place_when_available']),
            ('connectivity', 'traceform.streaming.physical_connectivity_v2.sample_connectivity',
             ['group_availability_does_not_establish_each_field']),
            ('device_relation', 'traceform.streaming.physical_device_state_v2.sample_device_state',
             ['audio_route_defaults_may_be_present', 'group_availability_does_not_establish_each_field']),
            ('audio_context', 'traceform.streaming.physical_audio_v2.sample_audio_context',
             ['derived_audio_summaries']),
        )},
    )
