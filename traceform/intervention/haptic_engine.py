"""
Traceform Intervention Layer: Haptic Feedback Engine.

Interfaces with Android/Termux hardware APIs to deliver unsemantic 
structural feedback (vibrations, notifications) triggered by topological events.
Uses asynchronous background queues and debouncing to guarantee sub-millisecond
main-thread throughput.
"""

from __future__ import annotations

import logging
import os
import queue
import shutil
import subprocess
import threading
import time
from typing import Any, Dict, List, Optional

from traceform.intervention.resonance_hooks import ResonanceTriggerType


class HapticEngine:
    def __init__(
        self,
        debounce_seconds: float = 300.0,
        enabled: bool = True,
        silent_flag_file: Optional[str] = None,
    ) -> None:
        self.debounce_seconds = debounce_seconds
        self.enabled = enabled
        self.silent_flag_file = silent_flag_file

        # Ensure Termux bin is in PATH if running on Android/Termux
        termux_bin = "/data/data/com.termux/files/usr/bin"
        if os.path.isdir(termux_bin) and termux_bin not in os.environ.get("PATH", ""):
            os.environ["PATH"] = f"{termux_bin}:{os.environ.get('PATH', '')}"
        
        # Check Termux API availability
        self.has_vibrate = shutil.which("termux-vibrate") is not None
        self.has_notification = shutil.which("termux-notification") is not None
        
        # Action queue and worker thread
        self._queue: queue.Queue[Dict[str, Any]] = queue.Queue(maxsize=50)
        self._last_triggered: Dict[str, float] = {}
        
        self._running = True
        self._worker = threading.Thread(target=self._worker_loop, daemon=True, name="HapticWorker")
        self._worker.start()
        
        if not self.has_vibrate:
            print("[HapticEngine] 'termux-vibrate' not found. Operating in simulation mode.")

    def shutdown(self) -> None:
        self._running = False
        self._queue.put({"type": "SHUTDOWN"})
        self._worker.join(timeout=1.0)

    def trigger(self, event_type: str, level: str, message: str) -> None:
        """
        Enqueues a haptic trigger asynchronously. Applies debouncing and silence checks.
        """
        if not self.enabled:
            return
        if os.environ.get("TRACEFORM_DISABLE_HAPTICS", "").lower() in ("1", "true", "yes"):
            return
        if self.silent_flag_file and os.path.exists(str(self.silent_flag_file)):
            return

        now = time.time()
        last_t = self._last_triggered.get(event_type, 0.0)
        
        if (now - last_t) < self.debounce_seconds:
            return  # Debounced

        self._last_triggered[event_type] = now
        
        try:
            self._queue.put_nowait({"type": event_type, "level": level, "message": message})
        except queue.Full:
            pass # Drop event if queue is completely overwhelmed

    def _worker_loop(self) -> None:
        while self._running:
            try:
                task = self._queue.get(timeout=1.0)
                if task["type"] == "SHUTDOWN":
                    break
                    
                self._execute_haptic(task)
                self._queue.task_done()
            except queue.Empty:
                continue
            except Exception as e:
                logging.error(f"[HapticEngine] Dispatch error: {e}")

    def _execute_haptic(self, task: Dict[str, Any]) -> None:
        etype = task.get("type", "")
        level = task.get("level", "INFO")
        
        # Define patterns: [(duration_ms, sleep_ms), ...]
        pattern: List[tuple[int, int]] = []
        notify = False
        
        if etype == "PROLONGED_DWELL":
            pattern = [(100, 100), (100, 0)]  # Double tap
        elif etype == "HIGH_ENTROPY_FLUX":
            pattern = [(50, 50)] * 4 + [(50, 0)]  # Staccato
        elif etype == "CSD_TIPPING":
            pattern = [(500, 200), (500, 0)]  # Heavy SOS
            if level == "CRITICAL":
                notify = True
        else:
            pattern = [(150, 0)]
            
        # Execute pattern
        for duration, pause in pattern:
            if self.has_vibrate:
                try:
                    subprocess.run(
                        ["termux-vibrate", "-d", str(duration), "-f"],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        timeout=2.0,
                    )
                except Exception as exc:
                    logging.warning(f"[HapticEngine] Vibration dispatch failed: {exc}")
            else:
                print(f"[HapticEngine-SIM] Vibrating for {duration}ms")

            if pause > 0:
                time.sleep(pause / 1000.0)

        # Optional Notification
        if notify and self.has_notification:
            try:
                subprocess.run(
                    [
                        "termux-notification",
                        "-t", "Traceform Intervention",
                        "-c", task.get("message", "Critical dynamic threshold crossed."),
                        "--priority", "high",
                    ],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=2.0,
                )
            except Exception as exc:
                logging.warning(f"[HapticEngine] Notification dispatch failed: {exc}")
        elif notify:
            print(f"[HapticEngine-SIM] NOTIFICATION: {task.get('message')}")
