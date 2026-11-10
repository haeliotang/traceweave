# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Check whether digital_context fields are exact functions of other stored fields.

The edge daemon builds digital_context from ``dumpsys`` text and fixed formulas
(``traceform/probes/digital_sensorium.py``). If a field equals its formula on
every stored frame, it carries no information beyond the formula's inputs.
Aggregates only; the database is opened read-only.
"""
from __future__ import annotations

import json
import math
import sqlite3
from pathlib import Path
from typing import Any, Dict

TOLERANCE = 1e-9
IDENTITIES = (
    'rf_entropy_from_wifi_ap_count',
    'ble_density_equals_wifi_ap_count_over_3',
    'ble_variance_from_ble_density',
    'touch_velocity_from_app_switch_and_unlock_when_screen_on',
    'touch_jitter_from_velocity_when_screen_on',
    'touch_zero_when_screen_not_on',
)


def check_digital_context_identities(sqlite_path) -> Dict[str, Any]:
    checks = {name: {'checked': 0, 'matches': 0, 'max_abs_error': 0.0} for name in IDENTITIES}

    def record(name: str, error: float) -> None:
        item = checks[name]
        item['checked'] += 1
        item['matches'] += error <= TOLERANCE
        item['max_abs_error'] = max(item['max_abs_error'], round(error, 12))

    frames = 0
    connection = sqlite3.connect(f'file:{Path(sqlite_path)}?mode=ro', uri=True)
    try:
        connection.execute('BEGIN')
        for (raw,) in connection.execute('SELECT raw FROM hot ORDER BY ts'):
            try:
                observation = json.loads(raw)
                digital = observation.get('digital_context')
                if not isinstance(digital, dict) or not digital:
                    continue
                aps = (observation.get('connectivity') or {}).get('wifi_ap_count') or 0
                density = digital['ambient_social_ble_density']
                app, unlock = digital['app_switch_rate'], digital['unlock_burst_count']
                velocity = digital['touch_interaction_velocity']
                jitter = digital['touch_interval_jitter']
                entropy = digital['ambient_rf_shannon_entropy']
                variance = digital['ble_rssi_spatial_variance']
                screen_on = digital['screen_on_ratio'] == 1.0
            except (ValueError, KeyError, TypeError, AttributeError):
                continue
            frames += 1
            expected = round(min(5.0, math.log2(aps + 1.0) * 0.85), 3) if aps > 0 else 0.0
            record('rf_entropy_from_wifi_ap_count', abs(entropy - expected))
            if aps > 0:
                record('ble_density_equals_wifi_ap_count_over_3', abs(density - round(min(30.0, aps / 3.0), 2)))
            expected = round(min(25.0, density * 1.6), 2) if density > 1.0 else 0.0
            record('ble_variance_from_ble_density', abs(variance - expected))
            if screen_on and app < 23 and unlock < 20:  # below the probe's own caps
                expected = round(min(10.0, 1.0 + app * 0.4 + unlock * 0.3), 3)
                record('touch_velocity_from_app_switch_and_unlock_when_screen_on', abs(velocity - expected))
                expected = round(min(2.0, (app * 0.25 + unlock * 0.2) / (1.0 + velocity * 0.2)), 3)
                record('touch_jitter_from_velocity_when_screen_on', abs(jitter - expected))
            elif not screen_on:
                record('touch_zero_when_screen_not_on', max(abs(velocity), abs(jitter)))
    finally:
        connection.close()
    return {
        'scope': 'exact_formula_identities_in_stored_digital_context_not_measurement_validation',
        'formula_source': 'traceform/probes/digital_sensorium.py',
        'frames_with_digital_context': frames,
        'identities': checks,
    }
