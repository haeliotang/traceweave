"""Device state and power relationship acquisition for Pixel 6a."""

import json
from pathlib import Path
import subprocess
from typing import Any, Dict, Tuple

from . import config


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
        except Exception:
            pass

    return dev_obj, avail
