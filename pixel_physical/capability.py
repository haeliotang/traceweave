"""Capability detection and inventory inspection for Pixel 6a physical stack."""

import json
from pathlib import Path
from typing import Any, Dict

INVENTORY_PATH = Path("/root/pixel_physical_capability_inventory_v1.json")


def load_capability_inventory() -> Dict[str, Any]:
    """Loads the pre-probed hardware and modality capability inventory."""
    if not INVENTORY_PATH.exists():
        return {"error": "INVENTORY_FILE_NOT_FOUND"}
    try:
        with INVENTORY_PATH.open("r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as exc:
        return {"error": str(exc)}


def get_working_sensors_count() -> int:
    inv = load_capability_inventory()
    return int(inv.get("summary", {}).get("working_sensors_count", 0))


def get_total_sensors_count() -> int:
    inv = load_capability_inventory()
    return int(inv.get("summary", {}).get("total_sensors_listed", 0))


def get_permissions_report() -> Dict[str, str]:
    inv = load_capability_inventory()
    return inv.get("permissions", {})
