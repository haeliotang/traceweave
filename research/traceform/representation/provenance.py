"""Evidence bindings; field presence is not proof of direct measurement."""

import hashlib
import json
import math


def content_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def input_inventory(observation):
    supplied, missing = [], []

    def visit(value, path):
        if isinstance(value, dict):
            for key, child in value.items():
                visit(child, f"{path}.{key}" if path else key)
        elif value is None or isinstance(value, float) and not math.isfinite(value):
            missing.append(path)
        else:
            supplied.append(path)

    visit(observation, "")
    return {"supplied_paths": sorted(supplied), "missing_paths": sorted(missing),
            "measurement_status": "unverified_unless_upstream_provenance_supplied"}
