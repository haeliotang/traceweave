"""Location acquisition whose API worker has its own finite update deadline."""

import json
import math
from pathlib import Path
import subprocess
import time

from pixel_physical import config
from pixel_physical.location import calculate_haversine_distance_m, encode_geohash


def _latest_reply(text):
    decoder = json.JSONDecoder()
    remaining = text.strip()
    latest = None
    while remaining:
        value, end = decoder.raw_decode(remaining)
        remaining = remaining[end:].lstrip()
        if isinstance(value, dict) and 'latitude' in value and 'longitude' in value:
            latest = value
    return latest


def sample_location(last_coords=None):
    if not Path(config.LOCATION_BIN).exists():
        return {'error': 'LOCATION_BIN_NOT_FOUND'}, 'UNAVAILABLE'
    payload = None
    request = 'updates'
    # API v0.53.0 ends this worker after 30 seconds even without a GPS fix.
    # Killing a client of the unbounded `once` worker does not cancel its request.
    for command, timeout in [([config.LOCATION_BIN, '-r', 'updates'], 35.0),
                             ([config.LOCATION_BIN, '-p', 'network', '-r', 'last'], 3.0)]:
        started = time.monotonic()
        try:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    text=True, timeout=timeout)
            candidate = _latest_reply(result.stdout) if result.returncode == 0 else None
            if candidate is not None:
                age = float(candidate.get('elapsedMs', float('inf')))
                age_upper_bound = age + (time.monotonic() - started) * 1000
                lat, lon = float(candidate['latitude']), float(candidate['longitude'])
                if (math.isfinite(age) and age >= 0 and age_upper_bound <= 120000 and math.isfinite(lat)
                        and math.isfinite(lon) and -90 <= lat <= 90 and -180 <= lon <= 180):
                    payload = candidate
                    break
        except (OSError, subprocess.TimeoutExpired, ValueError, TypeError):
            pass
        request = 'last'
    if payload is None:
        return {'error': 'NO_RECENT_LOCATION', 'max_age_ms': 120000}, 'TEMPORARILY_UNAVAILABLE'
    lat, lon = float(payload['latitude']), float(payload['longitude'])
    place = {
        'latitude': round(lat, 6), 'longitude': round(lon, 6),
        'accuracy_m': round(float(payload.get('accuracy', 50.0)), 2),
        'provider': str(payload.get('provider', 'unknown')),
        'spatial_cell': encode_geohash(lat, lon, precision=7),
        'distance_delta_m': round(calculate_haversine_distance_m(*last_coords, lat, lon), 2) if last_coords is not None else 0.0,
        'location_age_ms': float(payload['elapsedMs']),
        'location_age_upper_bound_ms': age_upper_bound, 'location_request': request,
    }
    for source, destination in [('altitude', 'altitude_m'), ('speed', 'speed_mps'), ('bearing', 'bearing_deg')]:
        place[destination] = round(float(payload[source]), 2) if payload.get(source) is not None else None
    return place, 'AVAILABLE'
