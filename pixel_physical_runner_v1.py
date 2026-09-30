#!/usr/bin/env python3
"""Pixel Sensorium — Physical Observation Stack v1 Acquisition Runner.

Authority:
  PHYSICAL_ACQUISITION_SCHEDULING_AUTHORITY=1
  MAX_PHYSICAL_ACQUISITION_RUNNERS=1

This runner provides persistent, background physical-world observation
independent of interactive shell sessions. It maintains a dedicated
physical-layer lock ($HOME/.pixel_sensorium/physical_acquisition.lock) and
never touches frozen Production Runtime or Android JobScheduler job 7701.
"""

import fcntl
import json
import os
from pathlib import Path
import signal
import sys
import time

if "/root" not in sys.path:
    sys.path.insert(0, "/root")

from pixel_physical import config, ledger
from pixel_physical.acquisition import PhysicalAcquisitionEngine

LOCK_PATH = config.DEFAULT_LEDGER_DIR / "physical_acquisition.lock"
PID_PATH = config.DEFAULT_LEDGER_DIR / "physical_acquisition.pid"


class PhysicalRunner:
    def __init__(self, ledger_dir: Path = config.DEFAULT_LEDGER_DIR):
        self.ledger_dir = ledger_dir
        self.lock_fd = None
        self.stop_requested = False
        self.engine = None

    def acquire_lock(self) -> bool:
        self.ledger_dir.mkdir(parents=True, exist_ok=True)
        self.lock_fd = open(LOCK_PATH, "w")
        try:
            fcntl.flock(self.lock_fd.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (BlockingIOError, OSError):
            print("[physical_runner] ERROR: Another physical acquisition runner is active (MAX_PHYSICAL_ACQUISITION_RUNNERS=1)")
            return False

        # Write current PID
        PID_PATH.write_text(str(os.getpid()), encoding="utf-8")
        return True

    def release_lock(self):
        if PID_PATH.exists():
            try:
                PID_PATH.unlink()
            except Exception:
                pass
        if self.lock_fd is not None:
            try:
                fcntl.flock(self.lock_fd.fileno(), fcntl.LOCK_UN)
                self.lock_fd.close()
            except Exception:
                pass
            self.lock_fd = None

    def handle_signal(self, signum, frame):
        print(f"\n[physical_runner] Received signal {signum}, initiating clean shutdown...")
        self.stop_requested = True

    def run(self) -> int:
        signal.signal(signal.SIGTERM, self.handle_signal)
        signal.signal(signal.SIGINT, self.handle_signal)
        signal.signal(signal.SIGHUP, self.handle_signal)

        if not self.acquire_lock():
            return 2

        print(f"[physical_runner] Started successfully (PID={os.getpid()})")
        print(f"[physical_runner] Physical lock held: {LOCK_PATH}")

        self.engine = PhysicalAcquisitionEngine(ledger_dir=self.ledger_dir)

        try:
            while not self.stop_requested:
                t0_mono = time.monotonic()

                try:
                    # Perform one adaptive observation cycle
                    obs, trans, ep = self.engine.sample_once(force_audio=False, force_location=False)
                    stat_like = obs.motion.get("stationary_likelihood", 1.0)
                    is_stationary = (stat_like is not None and float(stat_like) > 0.8)
                    
                    if trans:
                        print(f"[physical_runner] Transition: {trans.transition_id} -> {trans.transition_summary}")
                except Exception as exc:
                    print(f"[physical_runner] WARNING: Sampling error: {exc}")
                    is_stationary = True

                # Adaptive scheduling interval
                target_interval = 30.0 if is_stationary else 10.0
                next_deadline = t0_mono + target_interval

                # Monotonic sleep with frequent wakeups to check stop_requested
                while not self.stop_requested and time.monotonic() < next_deadline:
                    time.sleep(0.5)

        finally:
            print("[physical_runner] Cleaning up locks and exiting.")
            self.release_lock()

        return 0


def main() -> int:
    runner = PhysicalRunner()
    return runner.run()


if __name__ == "__main__":
    sys.exit(main())
