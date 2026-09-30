import hashlib
import hmac
import json
import os
from pathlib import Path
import stat
import subprocess
from typing import Any, Dict, List, Optional, Tuple

from . import config

SALT_FILE = config.DEFAULT_LEDGER_DIR / ".device_fingerprint_salt"
_CACHED_SALT: Optional[bytes] = None


def get_device_salt() -> bytes:
    """Retrieves or initializes the device-local privacy HMAC salt.
    
    Security & Continuity Invariants:
      - Must be a regular file, never a symlink.
      - Must have 0600 permissions.
      - Never silently rotates if an established trajectory already exists.
    """
    global _CACHED_SALT
    if _CACHED_SALT is not None:
        return _CACHED_SALT

    if SALT_FILE.is_symlink():
        raise RuntimeError("FINGERPRINT_SECRET_SYMLINK_REJECTED: salt file cannot be a symbolic link")

    if not SALT_FILE.exists():
        # Critical continuity defense: do NOT silently regenerate salt if an established ledger exists
        if config.OBSERVATIONS_LEDGER.exists() and config.OBSERVATIONS_LEDGER.stat().st_size > 0:
            raise RuntimeError(
                "FINGERPRINT_SECRET_MISSING_AFTER_ESTABLISHED_TRAJECTORY: "
                "Salt file is missing but established physical trajectory exists. "
                "Automatic replacement refused to prevent silent fingerprint rotation."
            )
        config.DEFAULT_LEDGER_DIR.mkdir(parents=True, exist_ok=True)
        salt = os.urandom(32)
        fd = os.open(str(SALT_FILE), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(salt)
            f.flush()
            os.fsync(f.fileno())
        _CACHED_SALT = salt
        return salt

    # File exists: verify it is a regular file
    st = SALT_FILE.stat()
    if not stat.S_ISREG(st.st_mode):
        raise RuntimeError("FINGERPRINT_SECRET_NOT_REGULAR_FILE: salt path is not a regular file")

    if (st.st_mode & 0o077) != 0:
        try:
            os.chmod(SALT_FILE, 0o600)
        except OSError:
            pass

    try:
        data = SALT_FILE.read_bytes()
        if len(data) >= 16:
            _CACHED_SALT = data
            return data
    except Exception as e:
        raise RuntimeError(f"FINGERPRINT_SECRET_READ_ERROR: {e}") from e

    raise RuntimeError("FINGERPRINT_SECRET_INVALID: salt data too short")


def hash_identifier(val: Optional[str]) -> Optional[str]:
    """Generates a deterministic keyed HMAC-SHA256 privacy fingerprint of raw identifiers."""
    if not val or val == "02:00:00:00:00:00" or val == "<unknown ssid>":
        return None
    try:
        salt = get_device_salt()
    except RuntimeError:
        return None
    h = hmac.new(salt, val.encode("utf-8"), hashlib.sha256).hexdigest()
    return h[:config.MAX_FINGERPRINT_LEN]


def hash_environment_set(bssids: List[str]) -> Optional[str]:
    """Generates a deterministic keyed HMAC fingerprint for an entire visible AP environment."""
    if not bssids:
        return None
    sorted_bssids = sorted([b.lower() for b in bssids if b and b != "00:00:00:00:00:00"])
    if not sorted_bssids:
        return None
    payload = "|".join(sorted_bssids)
    try:
        salt = get_device_salt()
    except RuntimeError:
        return None
    return hmac.new(salt, payload.encode("utf-8"), hashlib.sha256).hexdigest()[:config.MAX_FINGERPRINT_LEN]


def sample_connectivity(timeout: float = config.SUBPROCESS_TIMEOUT_SECONDS) -> Tuple[Dict[str, Any], str]:
    """
    Acquires Wi-Fi and Cellular network context via Termux API.
    Returns (connectivity_dict, availability_status).
    """
    conn_obj: Dict[str, Any] = {
        "wifi_connected": False,
        "wifi_fingerprint": None,
        "wifi_rssi": None,
        "wifi_link_speed_mbps": None,
        "wifi_frequency_mhz": None,
        "wifi_ap_count": 0,
        "wifi_environment_fingerprint": None,
        "cellular_network_type": None,
        "cellular_cell_fingerprint": None,
        "cellular_dbm": None,
        "bluetooth_nearby_environment": "NOT_EXPOSED",
    }

    # Verify salt access
    try:
        get_device_salt()
    except RuntimeError:
        return conn_obj, "DEGRADED_FINGERPRINT_KEY_ERROR"

    status = "AVAILABLE"

    # 1. Wi-Fi Connection Info
    try:
        res = subprocess.run(
            ["termux-wifi-connectioninfo"],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        if res.returncode == 0 and res.stdout.strip():
            w_data = json.loads(res.stdout)
            bssid = w_data.get("bssid")
            ssid = w_data.get("ssid")
            raw_id = f"{bssid}:{ssid}" if bssid and ssid else (bssid or ssid)
            conn_obj["wifi_connected"] = (bssid is not None and bssid != "02:00:00:00:00:00")
            conn_obj["wifi_fingerprint"] = hash_identifier(raw_id)
            conn_obj["wifi_rssi"] = w_data.get("rssi")
            conn_obj["wifi_link_speed_mbps"] = w_data.get("link_speed_mbps")
            conn_obj["wifi_frequency_mhz"] = w_data.get("frequency_mhz")
    except Exception:
        status = "PARTIALLY_AVAILABLE"

    # 2. Wi-Fi Scan Info (Environment Fingerprint)
    try:
        res = subprocess.run(
            ["termux-wifi-scaninfo"],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        if res.returncode == 0 and res.stdout.strip():
            scan_data = json.loads(res.stdout)
            if isinstance(scan_data, list):
                bssids = [ap.get("bssid") for ap in scan_data if isinstance(ap, dict) and ap.get("bssid")]
                conn_obj["wifi_ap_count"] = len(bssids)
                conn_obj["wifi_environment_fingerprint"] = hash_environment_set(bssids)
    except Exception:
        pass

    # 3. Telephony Cell Info
    try:
        res = subprocess.run(
            ["termux-telephony-cellinfo"],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        if res.returncode == 0 and res.stdout.strip():
            cell_data = json.loads(res.stdout)
            if isinstance(cell_data, list) and len(cell_data) > 0:
                primary = cell_data[0]
                conn_obj["cellular_network_type"] = primary.get("type")
                cid = primary.get("cid") or primary.get("ci")
                tac = primary.get("tac") or primary.get("lac")
                if cid is not None:
                    conn_obj["cellular_cell_fingerprint"] = hash_identifier(f"{cid}:{tac}")
                conn_obj["cellular_dbm"] = primary.get("dbm")
    except Exception:
        pass

    return conn_obj, status
