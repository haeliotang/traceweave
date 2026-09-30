"""Location and Spatial Cell acquisition for Pixel 6a."""

import json
import math
from pathlib import Path
import subprocess
from typing import Any, Dict, Optional, Tuple

from . import config

# Base32 characters for geohashing
BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"


def encode_geohash(latitude: float, longitude: float, precision: int = 7) -> str:
    """Encodes latitude and longitude into a geohash string without external dependencies."""
    lat_interval = [-90.0, 90.0]
    lon_interval = [-180.0, 180.0]
    geohash = []
    bits = [16, 8, 4, 2, 1]
    bit = 0
    ch = 0
    even = True

    while len(geohash) < precision:
        if even:
            mid = (lon_interval[0] + lon_interval[1]) / 2.0
            if longitude > mid:
                ch |= bits[bit]
                lon_interval[0] = mid
            else:
                lon_interval[1] = mid
        else:
            mid = (lat_interval[0] + lat_interval[1]) / 2.0
            if latitude > mid:
                ch |= bits[bit]
                lat_interval[0] = mid
            else:
                lat_interval[1] = mid

        even = not even
        if bit < 4:
            bit += 1
        else:
            geohash.append(BASE32[ch])
            bit = 0
            ch = 0

    return "".join(geohash)


def calculate_haversine_distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculates geodesic distance in meters between two WGS-84 coordinates."""
    r_earth = 6371000.0  # Earth radius in meters
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)

    a = (math.sin(delta_phi / 2.0) ** 2 +
         math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2)
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return r_earth * c


def sample_location(
    last_coords: Optional[Tuple[float, float]] = None,
    timeout: float = config.LOCATION_TIMEOUT_SECONDS,
) -> Tuple[Dict[str, Any], str]:
    """
    Acquires GPS / Network location via termux-location.
    Returns (place_dict, availability_status).
    """
    if not Path(config.LOCATION_BIN).exists():
        return {"error": "LOCATION_BIN_NOT_FOUND"}, "UNAVAILABLE"

    # Attempt primary high-accuracy single shot
    cmd = [config.LOCATION_BIN, "-r", "once"]
    res_data: Optional[Dict[str, Any]] = None

    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout)
        if res.returncode == 0 and res.stdout.strip():
            parsed = json.loads(res.stdout.strip())
            if isinstance(parsed, dict) and "latitude" in parsed and "longitude" in parsed:
                res_data = parsed
    except subprocess.TimeoutExpired:
        pass
    except Exception:
        pass

    # Fallback to last known network/gps location if once timed out
    if not res_data:
        try:
            fb_res = subprocess.run(
                [config.LOCATION_BIN, "-p", "network", "-r", "last"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=3.0,
            )
            if fb_res.returncode == 0 and fb_res.stdout.strip():
                parsed = json.loads(fb_res.stdout.strip())
                if isinstance(parsed, dict) and "latitude" in parsed:
                    res_data = parsed
        except Exception:
            pass

    if not res_data:
        return {"error": "LOCATION_ACQUISITION_FAILED"}, "TEMPORARILY_UNAVAILABLE"

    lat = float(res_data["latitude"])
    lon = float(res_data["longitude"])
    accuracy = float(res_data.get("accuracy", 50.0))
    altitude = float(res_data.get("altitude")) if res_data.get("altitude") is not None else None
    speed = float(res_data.get("speed")) if res_data.get("speed") is not None else None
    bearing = float(res_data.get("bearing")) if res_data.get("bearing") is not None else None
    provider = str(res_data.get("provider", "unknown"))

    spatial_cell = encode_geohash(lat, lon, precision=7)  # ~153m x ~153m cell

    dist_delta = 0.0
    if last_coords is not None:
        dist_delta = round(calculate_haversine_distance_m(last_coords[0], last_coords[1], lat, lon), 2)

    place_obj = {
        "latitude": round(lat, 6),
        "longitude": round(lon, 6),
        "accuracy_m": round(accuracy, 2),
        "altitude_m": round(altitude, 2) if altitude is not None else None,
        "speed_mps": round(speed, 2) if speed is not None else None,
        "bearing_deg": round(bearing, 2) if bearing is not None else None,
        "provider": provider,
        "spatial_cell": spatial_cell,
        "distance_delta_m": dist_delta,
    }

    return place_obj, "AVAILABLE"
