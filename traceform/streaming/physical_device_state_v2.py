"""V2 device-state adapter with reported-field and default evidence."""

import json
from pathlib import Path
import subprocess
from typing import Any, Dict, Tuple

from pixel_physical import config


def sample_device_state(timeout: float = config.SUBPROCESS_TIMEOUT_SECONDS) -> Tuple[Dict[str, Any], str]:
    """
    Acquires battery status and audio routing state.
    Returns (device_dict, availability_status).
    """
    dev_obj: Dict[str, Any] = {
        "battery_percentage": None,
        "plugged": "UNKNOWN",
        "status": "UNKNOWN",
        "battery_temp_c": None,
        "voltage_mv": None,
        "current_ua": None,
        "wired_headset": False,
        "bluetooth_a2dp": False,
        "screen_state": "NOT_SUPPORTED",
        "lock_state": "NOT_SUPPORTED",
    }

    field_sources = {name: "unavailable" for name in dev_obj}
    field_sources.update(wired_headset="default_false", bluetooth_a2dp="default_false",
                         screen_state="unsupported", lock_state="unsupported")
    avail = "UNAVAILABLE"

    # 1. Battery status
    if Path(config.BATTERY_BIN).exists():
        try:
            res = subprocess.run([config.BATTERY_BIN], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout)
            if res.returncode == 0 and res.stdout.strip():
                parsed = json.loads(res.stdout.strip())
                if isinstance(parsed, dict) and "percentage" in parsed:
                    avail = "AVAILABLE"
                    dev_obj["battery_percentage"] = parsed.get("percentage")
                    dev_obj["plugged"] = parsed.get("plugged", "UNKNOWN")
                    dev_obj["status"] = parsed.get("status", "UNKNOWN")
                    dev_obj["battery_temp_c"] = parsed.get("temperature")
                    dev_obj["voltage_mv"] = parsed.get("voltage")
                    dev_obj["current_ua"] = parsed.get("current")
                    for field, key in (("battery_percentage", "percentage"), ("plugged", "plugged"),
                                       ("status", "status"), ("battery_temp_c", "temperature"),
                                       ("voltage_mv", "voltage"), ("current_ua", "current")):
                        field_sources[field] = "reported_by_api" if key in parsed and parsed[key] is not None else "unavailable"
        except Exception:
            pass

    # 2. Audio routing status
    if Path(config.AUDIO_INFO_BIN).exists():
        try:
            res = subprocess.run([config.AUDIO_INFO_BIN], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout)
            if res.returncode == 0 and res.stdout.strip():
                parsed = json.loads(res.stdout.strip())
                if isinstance(parsed, dict):
                    avail = "AVAILABLE"
                    dev_obj["wired_headset"] = bool(parsed.get("WIREDHEADSET_IS_CONNECTED", False))
                    dev_obj["bluetooth_a2dp"] = bool(parsed.get("BLUETOOTH_A2DP_IS_ON", False))
                    for field, key in (("wired_headset", "WIREDHEADSET_IS_CONNECTED"),
                                       ("bluetooth_a2dp", "BLUETOOTH_A2DP_IS_ON")):
                        field_sources[field] = "reported_api_bool_coercion" if key in parsed and parsed[key] is not None else "default_false"
        except Exception:
            pass

    dev_obj["_field_provenance"] = field_sources
    return dev_obj, avail
