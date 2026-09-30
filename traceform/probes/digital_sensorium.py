"""
Traceform Digital Sensorium Probe for Android / Pixel 6a.

Extracts non-invasive, unsemantic digital interaction metrics from Android OS:
1. screen_on_ratio: Fraction of time screen was active / interactive (0.0 to 1.0)
2. app_switch_rate: Frequency of activity resumption / task switching
3. unlock_burst_count: Count of screen unlocks / interactive checks
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

    def sample_digital_interaction_metrics(
        self,
    ) -> Dict[str, float]:
        """
        Samples recent digital interaction events from dumpsys usagestats.
        Returns:
            dict containing screen_on_ratio, app_switch_rate, unlock_burst_count.
        """
        metrics = {
            "screen_on_ratio": 0.0,
            "app_switch_rate": 0.0,
            "unlock_burst_count": 0.0,
        }

        # Check immediate screen state
        screen_state = self.sample_screen_state()
        if screen_state == "ON":
            metrics["screen_on_ratio"] = 1.0
        elif screen_state == "DOZING":
            metrics["screen_on_ratio"] = 0.2
        else:
            metrics["screen_on_ratio"] = 0.0

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
                # Focus on recent 120 lines of events to prevent CPU overhead
                recent_lines = lines[:120] if len(lines) > 120 else lines

                app_switches = 0
                unlock_events = 0

                for line in recent_lines:
                    if "type=ACTIVITY_RESUMED" in line:
                        app_switches += 1
                    elif "type=SCREEN_INTERACTIVE" in line or "type=KEYGUARD_HIDDEN" in line:
                        unlock_events += 1

                # Normalize switch rate to count per window
                metrics["app_switch_rate"] = float(min(50, app_switches))
                metrics["unlock_burst_count"] = float(min(20, unlock_events))
        except Exception:
            pass

        return metrics
