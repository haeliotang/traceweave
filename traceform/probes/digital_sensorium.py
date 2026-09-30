"""
Traceform Digital Sensorium Probe for Android / Pixel 6a.

Extracts non-invasive, unsemantic digital interaction metrics from Android OS:
1. screen_on_ratio: Fraction of time screen was active / interactive (0.0 to 1.0)
2. app_switch_rate: Frequency of activity resumption / task switching
3. unlock_burst_count: Count of screen unlocks / interactive checks
4. touch_interaction_velocity: Touch interaction cadence / motor velocity
5. ambient_social_ble_density: Nearby BLE device count & RF density
"""

from __future__ import annotations

import datetime
from pathlib import Path
import re
import shutil
import subprocess
import time
from typing import Any, Dict, Optional, Tuple


class DigitalSensoriumProbe:
    """
    Lightweight probe extracting passive digital metrics via Android dumpsys.
    """

    def __init__(self, sample_window_seconds: float = 900.0) -> None:
        self.window_seconds = sample_window_seconds
        self.has_dumpsys = shutil.which("dumpsys") is not None

    def sample_screen_state(self) -> str:
        """Checks current display wakefulness: ON, DOZING, OFF, or UNKNOWN."""
        if not self.has_dumpsys:
            return "UNKNOWN"
        try:
            res = subprocess.run(
                ["dumpsys", "power"],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                timeout=1.5,
            )
            if res.returncode == 0:
                for line in res.stdout.splitlines():
                    if "mWakefulness=" in line:
                        if "Awake" in line:
                            return "ON"
                        elif "Dozing" in line:
                            return "DOZING"
                        elif "Asleep" in line:
                            return "OFF"
        except Exception:
            pass
        return "UNKNOWN"

    def sample_social_ble_density(self, fallback_wifi_ap_count: int = 0) -> float:
        """
        Estimates nearby BLE and RF social density.
        """
        ble_count = 0.0
        if self.has_dumpsys:
            try:
                res = subprocess.run(
                    ["dumpsys", "bluetooth_manager"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                    text=True,
                    timeout=1.5,
                )
                if res.returncode == 0 and res.stdout:
                    for line in res.stdout.splitlines():
                        if "Connected count:" in line:
                            try:
                                ble_count += float(line.split(":")[-1].strip())
                            except Exception:
                                pass
                        elif "Bonded devices:" in line or "Other devices:" in line:
                            try:
                                ble_count += float(line.split(":")[-1].strip())
                            except Exception:
                                pass
            except Exception:
                pass

        if ble_count == 0.0 and fallback_wifi_ap_count > 0:
            # Multi-RF fallback: dense WiFi AP environments correlate with social density
            ble_count = round(min(30.0, fallback_wifi_ap_count / 3.0), 2)

        return float(min(50.0, ble_count))

    def sample_digital_interaction_metrics(
        self,
        fallback_wifi_ap_count: int = 0,
    ) -> Dict[str, float]:
        """
        Samples recent digital interaction events from dumpsys usagestats.
        Returns:
            dict containing screen_on_ratio, app_switch_rate, unlock_burst_count,
            touch_interaction_velocity, ambient_social_ble_density.
        """
        metrics = {
            "screen_on_ratio": 0.0,
            "app_switch_rate": 0.0,
            "unlock_burst_count": 0.0,
            "touch_interaction_velocity": 0.0,
            "ambient_social_ble_density": 0.0,
        }

        # Check immediate screen state
        screen_state = self.sample_screen_state()
        if screen_state == "ON":
            metrics["screen_on_ratio"] = 1.0
        elif screen_state == "DOZING":
            metrics["screen_on_ratio"] = 0.2
        else:
            metrics["screen_on_ratio"] = 0.0

        metrics["ambient_social_ble_density"] = self.sample_social_ble_density(fallback_wifi_ap_count)

        if not self.has_dumpsys:
            return metrics

        try:
            # We sample only recent events to avoid parsing megabytes of logs
            res = subprocess.run(
                ["dumpsys", "usagestats"],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                timeout=2.0,
            )
            if res.returncode == 0 and res.stdout:
                lines = res.stdout.splitlines()
                # Focus on recent events before "In-memory daily stats"
                event_lines = []
                for line in lines:
                    if "In-memory daily stats" in line:
                        break
                    if "time=" in line and "type=" in line:
                        event_lines.append(line)
                recent_lines = event_lines[-120:] if len(event_lines) > 120 else event_lines

                app_switches = 0
                unlock_events = 0

                for line in recent_lines:
                    if "type=ACTIVITY_RESUMED" in line:
                        app_switches += 1
                    elif "type=SCREEN_INTERACTIVE" in line or "type=KEYGUARD_HIDDEN" in line:
                        unlock_events += 1

                # Normalize metrics
                metrics["app_switch_rate"] = float(min(50, app_switches))
                metrics["unlock_burst_count"] = float(min(20, unlock_events))

                # Touch velocity cadence estimation: combines screen active state with switch flux
                if screen_state == "ON":
                    metrics["touch_interaction_velocity"] = round(min(10.0, 1.0 + app_switches * 0.4 + unlock_events * 0.3), 3)
                else:
                    metrics["touch_interaction_velocity"] = 0.0
        except Exception:
            pass

        return metrics
