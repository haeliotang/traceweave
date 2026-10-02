"""V2 connectivity evidence; fingerprint derivation reuses frozen privacy helpers."""

import json
import subprocess
from typing import Any, Dict, Tuple
from pixel_physical import config
from pixel_physical.connectivity import get_device_salt, hash_identifier, hash_environment_set

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

    fields = {name: 'unavailable' for name in conn_obj}
    fields.update(wifi_connected='default_false', wifi_ap_count='default_zero',
                  bluetooth_nearby_environment='not_exposed')
    conn_obj['_field_provenance'] = fields

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
            fields['wifi_connected'] = 'derived_from_api_bssid' if 'bssid' in w_data else 'default_false'
            fields['wifi_fingerprint'] = 'derived_hmac' if conn_obj['wifi_fingerprint'] is not None else 'unavailable'
            for field, key in (('wifi_rssi', 'rssi'), ('wifi_link_speed_mbps', 'link_speed_mbps'),
                               ('wifi_frequency_mhz', 'frequency_mhz')):
                fields[field] = 'reported_by_api' if w_data.get(key) is not None else 'unavailable'
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
                fields['wifi_ap_count'] = 'derived_from_api_scan'
                fields['wifi_environment_fingerprint'] = 'derived_hmac' if conn_obj['wifi_environment_fingerprint'] is not None else 'unavailable'
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
                fields['cellular_network_type'] = 'reported_by_api' if primary.get('type') is not None else 'unavailable'
                fields['cellular_dbm'] = 'reported_by_api' if primary.get('dbm') is not None else 'unavailable'
                fields['cellular_cell_fingerprint'] = 'derived_hmac' if conn_obj['cellular_cell_fingerprint'] is not None else 'unavailable'
    except Exception:
        pass

    return conn_obj, status
