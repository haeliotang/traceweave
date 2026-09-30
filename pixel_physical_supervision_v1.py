#!/usr/bin/env python3
"""Pixel Sensorium — Physical Runner Supervision v1.

Provides autonomous process supervision and continuity preservation for the
frozen Physical Observation Stack v1 on Google Pixel 6a.
Durable user intent (intent.json) is authoritative for autonomous recovery.
Supervisor ticks run as bounded, single-transaction evaluations triggered by
Android JobScheduler (Job ID 7702) or CLI invocations.

Authority Matrix:
  PHYSICAL_RECOVERY_AUTHORITY=1
  PHYSICAL_SUPERVISION_INTENT_AUTHORITY=1
  PHYSICAL_SUPERVISION_TRANSACTION_AUTHORITY=1
  PHYSICAL_SUPERVISION_JOB_REGISTRATION_AUTHORITY=1

  PHYSICAL_SENSOR_ACQUISITION_AUTHORITY=0
  PHYSICAL_FEATURE_EXTRACTION_AUTHORITY=0
  PHYSICAL_TRANSITION_DETECTION_AUTHORITY=0
  PHYSICAL_EPISODE_SEGMENTATION_AUTHORITY=0
  PHYSICAL_SOURCE_LEDGER_AUTHORITY=0
  FROZEN_RUNTIME_AUTHORITY=0
  DEPLOYMENT_SUPERVISION_AUTHORITY=0
  CONTROL_PROTOCOL_AUTHORITY=0
  FROZEN_LINEAGE_AUTHORITY=0
  FROZEN_SEMANTIC_IDENTITY_AUTHORITY=0
  HIGH_LEVEL_LIFE_SEMANTIC_AUTHORITY=0
"""

from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
from typing import Any, Dict, Optional, Tuple

SUPERVISION_PROTOCOL = "pixel-sensorium.physical-supervision-intent.v1"
RECOVERY_STATE_PROTOCOL = "pixel-sensorium.physical-supervision-recovery-state.v1"
BOOT_EPOCH_PROTOCOL = "pixel-sensorium.physical-supervision-boot-epoch.v1"

LOCK_FILENAME = "supervision.lock"
INTENT_FILENAME = "intent.json"
RECOVERY_STATE_FILENAME = "recovery_state.json"
BOOT_EPOCH_FILENAME = "boot_epoch_state.json"
TICKS_LOG_FILENAME = "supervision_ticks.jsonl"

DEFAULT_SUPERVISION_ROOT = Path("/root/pixel_physical_supervision")
DEFAULT_PHYSICAL_DIR = Path("/root/.pixel_sensorium")

KERNEL_BOOT_ID_PATH = Path("/proc/sys/kernel/random/boot_id")
UUID_PATTERN = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

PYTHON_BIN = sys.executable
PHYSICALCTL_PATH = "/root/physicalctl.py"

MAX_LOCK_TIMEOUT_SECONDS = 10.0
MAX_INTENT_FILE_BYTES = 65536
MAX_GENERATION = (1 << 63) - 1
STALE_THRESHOLD_SECONDS = 300.0  # 5 minutes (10 stationary cycles)
BACKOFF_INTERVALS = [60.0, 120.0, 300.0, 600.0, 1800.0]


class PhysicalSupervisionError(Exception):
    """Base exception for physical supervision subsystem errors."""
    def __init__(self, code: str, detail: str):
        super().__init__(f"[{code}] {detail}")
        self.code = code
        self.detail = detail


def get_default_supervision_root() -> Path:
    """Returns the default supervision root path."""
    return DEFAULT_SUPERVISION_ROOT


def get_kernel_boot_id(override_path: Optional[Path] = None) -> str:
    """
    Reads and validates the Linux kernel boot_id from /proc/sys/kernel/random/boot_id.
    Validates regular file, non-empty, and strict UUID syntax.
    """
    p = override_path or KERNEL_BOOT_ID_PATH
    if not p.exists():
        raise PhysicalSupervisionError("BOOT_ID_UNAVAILABLE", f"Kernel boot ID path missing: {p}")
    try:
        val = p.read_text(encoding="utf-8").strip().lower()
    except Exception as exc:
        raise PhysicalSupervisionError("BOOT_ID_READ_FAILED", f"Cannot read kernel boot ID: {exc}")

    if not UUID_PATTERN.match(val):
        raise PhysicalSupervisionError("BOOT_ID_SYNTAX_INVALID", f"Invalid boot UUID syntax: {val}")
    return val


class PhysicalSupervisionLock:
    """Context manager acquiring exclusive kernel flock on supervision.lock."""

    def __init__(self, root: Path, timeout: float = MAX_LOCK_TIMEOUT_SECONDS):
        self.root = root
        self.lock_path = root / LOCK_FILENAME
        self.timeout = timeout
        self._fd: Optional[int] = None

    def __enter__(self):
        self.root.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(self.root, 0o700)
        except OSError:
            pass

        if self.lock_path.is_symlink():
            raise PhysicalSupervisionError(
                "SYMLINK_LOCK_REJECTED",
                f"Supervision lock file cannot be a symlink: {self.lock_path}",
            )

        flags = os.O_RDWR | os.O_CREAT | os.O_CLOEXEC
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW

        self._fd = os.open(str(self.lock_path), flags, 0o600)
        start_time = time.monotonic()

        while True:
            try:
                fcntl.flock(self._fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                return self
            except (BlockingIOError, OSError):
                if time.monotonic() - start_time >= self.timeout:
                    os.close(self._fd)
                    self._fd = None
                    raise PhysicalSupervisionError(
                        "SUPERVISION_LOCK_TIMEOUT",
                        f"Timed out after {self.timeout}s waiting for {self.lock_path}",
                    )
                time.sleep(0.05)

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self._fd is not None:
            try:
                fcntl.flock(self._fd, fcntl.LOCK_UN)
            except OSError:
                pass
            try:
                os.close(self._fd)
            except OSError:
                pass
            self._fd = None


def read_intent(root: Path) -> Tuple[Optional[str], Optional[int], Optional[Dict[str, Any]], Optional[str]]:
    """
    Reads and strictly validates intent.json.
    Returns: (desired_state, generation, raw_dict, error_code).
    Missing, corrupt, or invalid intent fails closed with error_code.
    """
    intent_path = root / INTENT_FILENAME
    if not intent_path.exists():
        return None, None, None, "MISSING_INTENT"

    if intent_path.is_symlink():
        return None, None, None, "SYMLINK_INTENT_REJECTED"

    try:
        st = intent_path.stat()
        if not stat.S_ISREG(st.st_mode):
            return None, None, None, "NON_REGULAR_INTENT_FILE"
        if st.st_size == 0:
            return None, None, None, "EMPTY_INTENT_FILE"
        if st.st_size > MAX_INTENT_FILE_BYTES:
            return None, None, None, "OVERSIZED_INTENT_FILE"

        content = intent_path.read_text(encoding="utf-8")
        data = json.loads(content)
        if not isinstance(data, dict):
            return None, None, None, "INVALID_JSON_NON_OBJECT"

        proto = data.get("protocol")
        if proto != SUPERVISION_PROTOCOL:
            return None, None, None, f"UNSUPPORTED_PROTOCOL_{proto}"

        desired = data.get("desired_physical_state")
        if not isinstance(desired, str) or desired not in ("RUNNING", "STOPPED"):
            return None, None, None, f"INVALID_DESIRED_STATE_{desired}"

        gen = data.get("generation")
        if isinstance(gen, bool) or not isinstance(gen, int) or gen < 1 or gen > MAX_GENERATION:
            return None, None, None, f"INVALID_GENERATION_{gen}"

        updated_at = data.get("updated_at_utc")
        if not isinstance(updated_at, str) or not updated_at:
            return None, None, None, "INVALID_UPDATED_AT"

        return desired, gen, data, None
    except Exception as exc:
        return None, None, None, f"CORRUPT_INTENT_{exc}"


def write_intent_atomic(root: Path, desired_state: str, generation: int) -> Dict[str, Any]:
    """
    Atomically writes, flushes, fsyncs, and replaces intent.json.
    Must be called while holding PhysicalSupervisionLock.
    """
    if desired_state not in ("RUNNING", "STOPPED"):
        raise ValueError(f"Invalid desired_state: {desired_state}")
    if isinstance(generation, bool) or not isinstance(generation, int) or generation < 1 or generation > MAX_GENERATION:
        raise ValueError(f"Invalid generation: {generation}")

    root.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(root, 0o700)
    except OSError:
        pass

    now_utc = datetime.now(timezone.utc).isoformat()
    payload = {
        "protocol": SUPERVISION_PROTOCOL,
        "desired_physical_state": desired_state,
        "generation": generation,
        "updated_at_utc": now_utc,
    }

    content = json.dumps(payload, indent=2) + "\n"
    tmp_path = root / f".tmp_intent_{os.getpid()}_{time.time_ns()}"
    final_path = root / INTENT_FILENAME

    fd = os.open(str(tmp_path), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o600)
    try:
        with open(fd, "w", encoding="utf-8", closefd=False) as f:
            f.write(content)
            f.flush()
        os.fsync(fd)
    finally:
        os.close(fd)

    os.replace(str(tmp_path), str(final_path))

    # Directory fsync
    dfd = os.open(str(root), os.O_RDONLY | os.O_CLOEXEC)
    try:
        os.fsync(dfd)
    finally:
        os.close(dfd)

    return payload


def read_recovery_state(root: Path) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    """
    Reads and strictly validates recovery_state.json.
    Classified as NON-AUTHORITATIVE operational retry state.
    Returns: (state_dict, error_code).
    """
    state_file = root / RECOVERY_STATE_FILENAME
    default_state = {
        "protocol": RECOVERY_STATE_PROTOCOL,
        "consecutive_recovery_failures": 0,
        "last_recovery_attempt_utc": None,
        "last_recovery_timestamp": None,
    }
    if not state_file.exists():
        return default_state, None

    if state_file.is_symlink():
        return None, "SYMLINK_RECOVERY_STATE_REJECTED"

    try:
        st = state_file.stat()
        if not stat.S_ISREG(st.st_mode):
            return None, "NON_REGULAR_RECOVERY_STATE_FILE"
        if st.st_size == 0:
            return None, "EMPTY_RECOVERY_STATE_FILE"
        if st.st_size > MAX_INTENT_FILE_BYTES:
            return None, "OVERSIZED_RECOVERY_STATE_FILE"

        content = state_file.read_text(encoding="utf-8")
        data = json.loads(content)
        if not isinstance(data, dict):
            return None, "INVALID_RECOVERY_STATE_NON_OBJECT"

        proto = data.get("protocol")
        if proto != RECOVERY_STATE_PROTOCOL:
            return None, f"UNSUPPORTED_RECOVERY_PROTOCOL_{proto}"

        failures = data.get("consecutive_recovery_failures")
        if isinstance(failures, bool) or not isinstance(failures, int) or failures < 0:
            return None, f"INVALID_FAILURE_COUNT_{failures}"

        last_ts = data.get("last_recovery_timestamp")
        if last_ts is not None:
            if not isinstance(last_ts, (int, float)):
                return None, "INVALID_RECOVERY_TIMESTAMP"
            if float(last_ts) > time.time() + 60.0:
                return None, "FUTURE_RECOVERY_TIMESTAMP"

        return {
            "protocol": RECOVERY_STATE_PROTOCOL,
            "consecutive_recovery_failures": failures,
            "last_recovery_attempt_utc": data.get("last_recovery_attempt_utc"),
            "last_recovery_timestamp": float(last_ts) if last_ts is not None else None,
        }, None
    except Exception as exc:
        return None, f"CORRUPT_RECOVERY_STATE_{exc}"


def write_recovery_state(root: Path, state_data: Dict[str, Any]) -> None:
    """Atomically writes recovery state metadata with fsync."""
    state_file = root / RECOVERY_STATE_FILENAME
    tmp_path = root / f".tmp_rec_{os.getpid()}_{time.time_ns()}"
    state_data["protocol"] = RECOVERY_STATE_PROTOCOL
    content = json.dumps(state_data, indent=2) + "\n"
    fd = os.open(str(tmp_path), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o600)
    try:
        with open(fd, "w", encoding="utf-8", closefd=False) as f:
            f.write(content)
            f.flush()
        os.fsync(fd)
    finally:
        os.close(fd)
    os.replace(str(tmp_path), str(state_file))

    try:
        dfd = os.open(str(root), os.O_RDONLY | os.O_CLOEXEC)
        try:
            os.fsync(dfd)
        finally:
            os.close(dfd)
    except OSError:
        pass


def read_boot_epoch_state(root: Path) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    """
    Reads and strictly validates boot_epoch_state.json.
    Classified as NON-AUTHORITATIVE operational supervision metadata.
    Returns: (state_dict, error_code).
    """
    boot_file = root / BOOT_EPOCH_FILENAME
    if not boot_file.exists():
        return None, "MISSING_BOOT_EPOCH_STATE"

    if boot_file.is_symlink():
        return None, "SYMLINK_BOOT_EPOCH_STATE_REJECTED"

    try:
        st = boot_file.stat()
        if not stat.S_ISREG(st.st_mode):
            return None, "NON_REGULAR_BOOT_EPOCH_STATE_FILE"
        if st.st_size == 0:
            return None, "EMPTY_BOOT_EPOCH_STATE_FILE"
        if st.st_size > MAX_INTENT_FILE_BYTES:
            return None, "OVERSIZED_BOOT_EPOCH_STATE_FILE"

        content = boot_file.read_text(encoding="utf-8")
        data = json.loads(content)
        if not isinstance(data, dict):
            return None, "INVALID_BOOT_EPOCH_STATE_NON_OBJECT"

        proto = data.get("protocol")
        if proto != BOOT_EPOCH_PROTOCOL:
            return None, f"UNSUPPORTED_BOOT_EPOCH_PROTOCOL_{proto}"

        b_id = data.get("boot_id")
        if not isinstance(b_id, str) or not UUID_PATTERN.match(b_id):
            return None, f"INVALID_BOOT_ID_{b_id}"

        first_seen = data.get("first_seen_utc")
        if not isinstance(first_seen, str) or not first_seen:
            return None, "INVALID_FIRST_SEEN_UTC"

        last_seen = data.get("last_seen_utc")
        if not isinstance(last_seen, str) or not last_seen:
            return None, "INVALID_LAST_SEEN_UTC"

        last_obs_id = data.get("last_observation_id")
        last_obs_mono = data.get("last_observation_monotonic_ns")

        return {
            "protocol": BOOT_EPOCH_PROTOCOL,
            "boot_id": b_id,
            "first_seen_utc": first_seen,
            "last_seen_utc": last_seen,
            "last_observation_id": last_obs_id if isinstance(last_obs_id, str) else None,
            "last_observation_monotonic_ns": int(last_obs_mono) if isinstance(last_obs_mono, (int, float)) else None,
        }, None
    except Exception as exc:
        return None, f"CORRUPT_BOOT_EPOCH_STATE_{exc}"


def write_boot_epoch_state(root: Path, state_data: Dict[str, Any]) -> None:
    """Atomically writes boot epoch state metadata with fsync."""
    boot_file = root / BOOT_EPOCH_FILENAME
    tmp_path = root / f".tmp_boot_{os.getpid()}_{time.time_ns()}"
    state_data["protocol"] = BOOT_EPOCH_PROTOCOL
    content = json.dumps(state_data, indent=2) + "\n"
    fd = os.open(str(tmp_path), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o600)
    try:
        with open(fd, "w", encoding="utf-8", closefd=False) as f:
            f.write(content)
            f.flush()
        os.fsync(fd)
    finally:
        os.close(fd)
    os.replace(str(tmp_path), str(boot_file))

    try:
        dfd = os.open(str(root), os.O_RDONLY | os.O_CLOEXEC)
        try:
            os.fsync(dfd)
        finally:
            os.close(dfd)
    except OSError:
        pass


def compute_cooldown(recovery_state: Dict[str, Any], now_epoch: float) -> Tuple[bool, float]:
    """Computes whether recovery is currently rate-limited and remaining seconds."""
    failures = recovery_state.get("consecutive_recovery_failures", 0)
    last_ts = recovery_state.get("last_recovery_timestamp")
    if failures <= 0 or last_ts is None:
        return False, 0.0

    idx = min(failures - 1, len(BACKOFF_INTERVALS) - 1)
    cooldown_window = BACKOFF_INTERVALS[idx]
    elapsed = now_epoch - float(last_ts)
    if elapsed < cooldown_window:
        return True, max(0.0, cooldown_window - elapsed)
    return False, 0.0


def invoke_physicalctl(args: list, timeout: float = 15.0) -> Tuple[int, Dict[str, Any], str]:
    """Invokes frozen physicalctl via fixed argv list and returns (rc, json_data, raw_out)."""
    cmd = [PYTHON_BIN, PHYSICALCTL_PATH] + args
    try:
        res = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            shell=False,
            timeout=timeout,
        )
        raw_out = res.stdout.strip()
        data = {}
        if "--json" in args and raw_out:
            try:
                data = json.loads(raw_out)
            except Exception:
                data = {"ok": False, "error_code": "JSON_PARSE_ERROR", "error_detail": raw_out}
        return res.returncode, data, raw_out
    except subprocess.TimeoutExpired:
        return 124, {"ok": False, "error_code": "PHYSICALCTL_TIMEOUT"}, "physicalctl invocation timed out"
    except Exception as exc:
        return 1, {"ok": False, "error_code": "PHYSICALCTL_EXEC_FAILED", "error_detail": str(exc)}, str(exc)


def observe_physical_runner(
    physical_dir: Optional[Path] = None,
    supervision_root: Optional[Path] = None,
    kernel_boot_id_override: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Non-mutating inspection of the frozen physical runner.
    Checks process PID liveness, lock status, and observation freshness.
    Binds freshness strictly to Linux kernel boot_id from /proc/sys/kernel/random/boot_id.
    Cross-boot observations fail closed as UNKNOWN_BOOT_EPOCH without comparing monotonic clocks.
    """
    p_dir = physical_dir or DEFAULT_PHYSICAL_DIR
    sup_root = supervision_root or get_default_supervision_root()

    pid_file = p_dir / "physical_acquisition.pid"
    lock_file = p_dir / "physical_acquisition.lock"
    obs_file = p_dir / "physical_observations.jsonl"

    runner_info: Dict[str, Any] = {
        "status": "STOPPED",
        "pid": None,
        "lock_held": False,
        "kernel_boot_id": None,
        "supervision_boot_id": None,
        "last_observation_id": None,
        "last_observation_utc": None,
        "last_observation_monotonic_ns": None,
        "freshness_source": "NONE",
        "seconds_since_last_observation": None,
        "boot_epoch_ambiguity": False,
        "healthy": False,
        "health_error": None,
    }

    # 1. Resolve Kernel Boot ID
    try:
        current_boot_id = kernel_boot_id_override or get_kernel_boot_id()
    except PhysicalSupervisionError as e:
        runner_info["health_error"] = f"KERNEL_BOOT_ID_ERROR_{e.code}"
        return runner_info

    runner_info["kernel_boot_id"] = current_boot_id

    # 2. Inspect PID
    pid = None
    pid_alive = False
    if pid_file.exists():
        try:
            val = int(pid_file.read_text().strip())
            os.kill(val, 0)
            pid = val
            pid_alive = True
        except (ValueError, OSError):
            pid = None
            pid_alive = False

    runner_info["pid"] = pid

    # 3. Inspect Lock
    lock_held = False
    if lock_file.exists():
        try:
            fd = os.open(str(lock_file), os.O_RDWR | os.O_CLOEXEC)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(fd, fcntl.LOCK_UN)
                lock_held = False
            except (BlockingIOError, OSError):
                lock_held = True
            finally:
                os.close(fd)
        except OSError:
            lock_held = False

    runner_info["lock_held"] = lock_held

    # 4. Inspect Newest Observation Record
    last_obs_id = None
    last_obs_iso = None
    last_obs_mono = None
    if obs_file.exists() and obs_file.stat().st_size > 0:
        try:
            with obs_file.open("rb") as f:
                f.seek(max(0, obs_file.stat().st_size - 4096))
                lines = f.read().decode("utf-8", errors="ignore").splitlines()
                for line in reversed(lines):
                    line = line.strip()
                    if line:
                        try:
                            obj = json.loads(line)
                            ts = obj.get("timestamp_utc")
                            mono = obj.get("monotonic_ns")
                            oid = obj.get("observation_id")
                            if ts:
                                last_obs_id = oid
                                last_obs_iso = ts
                                if mono is not None:
                                    last_obs_mono = int(mono)
                                break
                        except Exception:
                            continue
        except Exception:
            pass

    runner_info["last_observation_id"] = last_obs_id
    runner_info["last_observation_utc"] = last_obs_iso
    runner_info["last_observation_monotonic_ns"] = last_obs_mono

    # 5. Boot Epoch Identity & Same-Boot Monotonic Freshness
    now_utc_str = datetime.now(timezone.utc).isoformat()
    current_mono_ns = time.monotonic_ns()

    boot_state, boot_err = read_boot_epoch_state(sup_root)

    if boot_err == "MISSING_BOOT_EPOCH_STATE":
        # Initial supervision setup: record current kernel boot ID
        new_boot_state = {
            "protocol": BOOT_EPOCH_PROTOCOL,
            "boot_id": current_boot_id,
            "first_seen_utc": now_utc_str,
            "last_seen_utc": now_utc_str,
            "last_observation_id": last_obs_id if (pid_alive and lock_held) else None,
            "last_observation_monotonic_ns": last_obs_mono if (pid_alive and lock_held) else None,
        }
        write_boot_epoch_state(sup_root, new_boot_state)
        runner_info["supervision_boot_id"] = current_boot_id

        # If runner is currently running and active, historical observations may belong to this boot
        # but if runner is absent, provenance of pre-existing observations cannot be assumed
        if not (pid_alive and lock_held):
            runner_info["boot_epoch_ambiguity"] = True
            runner_info["freshness_source"] = "UNKNOWN_BOOT_EPOCH"
            runner_info["seconds_since_last_observation"] = None
        else:
            if last_obs_mono:
                runner_info["freshness_source"] = "MONOTONIC"
                runner_info["seconds_since_last_observation"] = round(max(0.0, (current_mono_ns - last_obs_mono) / 1e9), 2)
            elif last_obs_iso:
                try:
                    dt = datetime.fromisoformat(last_obs_iso.replace("Z", "+00:00"))
                    runner_info["freshness_source"] = "WALL_CLOCK_FALLBACK"
                    runner_info["seconds_since_last_observation"] = round(max(0.0, datetime.now(timezone.utc).timestamp() - dt.timestamp()), 2)
                except Exception:
                    runner_info["freshness_source"] = "NONE"
            else:
                runner_info["freshness_source"] = "NONE"

    elif boot_err is not None:
        # Corrupt or symlinked boot state fails closed
        runner_info["boot_epoch_ambiguity"] = True
        runner_info["freshness_source"] = "CORRUPT_BOOT_EPOCH_STATE"
        runner_info["health_error"] = f"BOOT_EPOCH_STATE_CORRUPT: {boot_err}"
        runner_info["healthy"] = False
        return runner_info

    else:
        # Valid boot_epoch_state.json exists
        stored_boot_id = boot_state["boot_id"]
        runner_info["supervision_boot_id"] = stored_boot_id

        if stored_boot_id != current_boot_id:
            # Cross-boot discontinuity detected via kernel boot_id mismatch!
            # DO NOT compare old monotonic clock to current monotonic clock!
            runner_info["boot_epoch_ambiguity"] = True
            runner_info["freshness_source"] = "UNKNOWN_BOOT_EPOCH"
            runner_info["health_error"] = "BOOT_EPOCH_CHANGED"
            runner_info["seconds_since_last_observation"] = None

            # If runner has started and produced an observation during this new boot:
            if pid_alive and lock_held and last_obs_id and last_obs_id != boot_state.get("last_observation_id"):
                # Bind new boot ID to fresh observation
                boot_state["boot_id"] = current_boot_id
                boot_state["first_seen_utc"] = now_utc_str
                boot_state["last_seen_utc"] = now_utc_str
                boot_state["last_observation_id"] = last_obs_id
                boot_state["last_observation_monotonic_ns"] = last_obs_mono
                write_boot_epoch_state(sup_root, boot_state)

                runner_info["boot_epoch_ambiguity"] = False
                runner_info["supervision_boot_id"] = current_boot_id
                runner_info["freshness_source"] = "MONOTONIC"
                runner_info["seconds_since_last_observation"] = round(max(0.0, (current_mono_ns - last_obs_mono) / 1e9), 2)
        else:
            # Same boot epoch confirmed!
            # Update last_seen_utc and bind current observation
            if last_obs_id and last_obs_id != boot_state.get("last_observation_id") and (pid_alive and lock_held):
                boot_state["last_seen_utc"] = now_utc_str
                boot_state["last_observation_id"] = last_obs_id
                boot_state["last_observation_monotonic_ns"] = last_obs_mono
                write_boot_epoch_state(sup_root, boot_state)

            if last_obs_mono is not None:
                if last_obs_mono > current_mono_ns:
                    runner_info["boot_epoch_ambiguity"] = True
                    runner_info["freshness_source"] = "UNKNOWN_BOOT_EPOCH"
                    runner_info["health_error"] = "UNKNOWN_BOOT_EPOCH"
                    runner_info["seconds_since_last_observation"] = None
                else:
                    runner_info["freshness_source"] = "MONOTONIC"
                    runner_info["seconds_since_last_observation"] = round(max(0.0, (current_mono_ns - last_obs_mono) / 1e9), 2)
            elif last_obs_iso is not None:
                try:
                    dt = datetime.fromisoformat(last_obs_iso.replace("Z", "+00:00"))
                    runner_info["freshness_source"] = "WALL_CLOCK_FALLBACK"
                    runner_info["seconds_since_last_observation"] = round(max(0.0, datetime.now(timezone.utc).timestamp() - dt.timestamp()), 2)
                except Exception:
                    runner_info["freshness_source"] = "NONE"
            else:
                runner_info["freshness_source"] = "NONE"

    # 6. Runner Health Classification
    sec_since = runner_info["seconds_since_last_observation"]
    if pid_alive and lock_held:
        runner_info["status"] = "RUNNING"
        if runner_info["boot_epoch_ambiguity"]:
            runner_info["healthy"] = False
            runner_info["health_error"] = runner_info.get("health_error") or "UNKNOWN_BOOT_EPOCH"
        elif sec_since is not None and sec_since <= STALE_THRESHOLD_SECONDS:
            runner_info["healthy"] = True
        else:
            runner_info["healthy"] = False
            runner_info["health_error"] = "OBSERVATION_STALE"
    else:
        runner_info["status"] = "STOPPED"
        runner_info["healthy"] = False

    return runner_info


def log_tick_transaction(root: Path, entry: Dict[str, Any]) -> None:
    """Durably logs supervision tick transactions for JobScheduler execution provenance."""
    log_path = root / TICKS_LOG_FILENAME
    line = json.dumps(entry) + "\n"
    try:
        with log_path.open("a", encoding="utf-8") as f:
            f.write(line)
            f.flush()
            os.fsync(f.fileno())
    except OSError:
        pass


def execute_supervised_start(root: Optional[Path] = None) -> Dict[str, Any]:
    """
    Explicit user start command.
    Acquires transaction lock, establishes/advances durable RUNNING intent,
    and idempotently actuates frozen physicalctl start.
    """
    sup_root = root or get_default_supervision_root()
    with PhysicalSupervisionLock(sup_root):
        desired, gen, raw_intent, err = read_intent(sup_root)
        new_gen = (gen + 1) if (err is None and gen is not None) else 1

        write_intent_atomic(sup_root, "RUNNING", new_gen)
        # Clear recovery failure backoff on explicit user start
        write_recovery_state(sup_root, {
            "protocol": RECOVERY_STATE_PROTOCOL,
            "consecutive_recovery_failures": 0,
            "last_recovery_attempt_utc": None,
            "last_recovery_timestamp": None,
        })

        obs = observe_physical_runner(supervision_root=sup_root)
        actuated = False
        if not obs["healthy"]:
            rc, data, raw_out = invoke_physicalctl(["start"])
            actuated = True

        time.sleep(0.5)
        fresh_obs = observe_physical_runner(supervision_root=sup_root)

        sup_state = "RUNNING_HEALTHY" if fresh_obs["healthy"] else ("STARTING" if fresh_obs["status"] == "RUNNING" else "RECOVERY_REQUIRED")

        return {
            "protocol": SUPERVISION_PROTOCOL,
            "supervision_state": sup_state,
            "intent_valid": True,
            "recovery_authorized": True,
            "desired_physical_state": "RUNNING",
            "generation": new_gen,
            "intent_error": None,
            "observed_physical_runner": fresh_obs,
            "actuated_start": actuated,
        }


def execute_supervised_stop(root: Optional[Path] = None) -> Dict[str, Any]:
    """
    Explicit user stop command.
    Acquires transaction lock, durably persists STOPPED intent first,
    then invokes frozen physicalctl stop.
    Durable STOPPED intent permanently forbids autonomous resurrection.
    """
    sup_root = root or get_default_supervision_root()
    with PhysicalSupervisionLock(sup_root):
        desired, gen, raw_intent, err = read_intent(sup_root)
        new_gen = (gen + 1) if (err is None and gen is not None) else 1

        # Durable write MUST succeed before actuation
        write_intent_atomic(sup_root, "STOPPED", new_gen)
        write_recovery_state(sup_root, {
            "protocol": RECOVERY_STATE_PROTOCOL,
            "consecutive_recovery_failures": 0,
            "last_recovery_attempt_utc": None,
            "last_recovery_timestamp": None,
        })

        rc, data, raw_out = invoke_physicalctl(["stop"])

        time.sleep(0.5)
        fresh_obs = observe_physical_runner(supervision_root=sup_root)
        sup_state = "STOPPED" if fresh_obs["status"] == "STOPPED" else "STOPPED_BUT_RUNNER_RUNNING"

        return {
            "protocol": SUPERVISION_PROTOCOL,
            "supervision_state": sup_state,
            "intent_valid": True,
            "recovery_authorized": False,
            "desired_physical_state": "STOPPED",
            "generation": new_gen,
            "intent_error": None,
            "observed_physical_runner": fresh_obs,
            "actuated_stop": True,
        }


def execute_supervision_tick(root: Optional[Path] = None, source: str = "CLI") -> Dict[str, Any]:
    """
    Executes exactly one bounded supervision transaction under exclusive lock.
    Reads durable intent, observes runner, and conditionally performs at most
    one recovery start if authorized. Never autonomously stops.
    Logs execution provenance to supervision_ticks.jsonl.
    """
    sup_root = root or get_default_supervision_root()
    tick_now_utc = datetime.now(timezone.utc).isoformat()
    tick_mono_ns = time.monotonic_ns()

    with PhysicalSupervisionLock(sup_root):
        desired, gen, raw_intent, err = read_intent(sup_root)
        rec_state, rec_err = read_recovery_state(sup_root)

        # Fail closed on corrupt/missing intent
        if err is not None:
            sup_state = "UNCONFIGURED" if err == "MISSING_INTENT" else "INTENT_INVALID"
            obs = observe_physical_runner(supervision_root=sup_root)
            res = {
                "protocol": SUPERVISION_PROTOCOL,
                "supervision_state": sup_state,
                "intent_valid": False,
                "recovery_authorized": False,
                "desired_physical_state": None,
                "generation": gen,
                "intent_error": err,
                "observed_physical_runner": obs,
                "actuated_recovery_start": False,
            }
            log_tick_transaction(sup_root, {
                "timestamp_utc": tick_now_utc,
                "monotonic_ns": tick_mono_ns,
                "source": source,
                "decision": "INTENT_INVALID_OR_MISSING",
                "supervision_state": sup_state,
                "actuated_recovery_start": False,
            })
            return res

        # Fail closed on corrupted recovery state
        if rec_err is not None:
            obs = observe_physical_runner(supervision_root=sup_root)
            res = {
                "protocol": SUPERVISION_PROTOCOL,
                "supervision_state": "ATTENTION_REQUIRED",
                "intent_valid": True,
                "recovery_authorized": False,
                "desired_physical_state": desired,
                "generation": gen,
                "intent_error": f"RECOVERY_STATE_CORRUPT: {rec_err}",
                "observed_physical_runner": obs,
                "actuated_recovery_start": False,
            }
            log_tick_transaction(sup_root, {
                "timestamp_utc": tick_now_utc,
                "monotonic_ns": tick_mono_ns,
                "source": source,
                "decision": "RECOVERY_STATE_CORRUPT_FAIL_CLOSED",
                "supervision_state": "ATTENTION_REQUIRED",
                "actuated_recovery_start": False,
            })
            return res

        # Desired is STOPPED: Never resurrect
        if desired == "STOPPED":
            obs = observe_physical_runner(supervision_root=sup_root)
            sup_state = "STOPPED" if obs["status"] == "STOPPED" else "STOPPED_BUT_RUNNER_RUNNING"
            res = {
                "protocol": SUPERVISION_PROTOCOL,
                "supervision_state": sup_state,
                "intent_valid": True,
                "recovery_authorized": False,
                "desired_physical_state": "STOPPED",
                "generation": gen,
                "intent_error": None,
                "observed_physical_runner": obs,
                "actuated_recovery_start": False,
            }
            log_tick_transaction(sup_root, {
                "timestamp_utc": tick_now_utc,
                "monotonic_ns": tick_mono_ns,
                "source": source,
                "decision": "DESIRED_STOPPED_PREVENTS_START",
                "supervision_state": sup_state,
                "actuated_recovery_start": False,
            })
            return res

        # Desired is RUNNING
        obs = observe_physical_runner(supervision_root=sup_root)
        now_epoch = time.time()
        in_cooldown, remaining_s = compute_cooldown(rec_state or {}, now_epoch)

        if obs["healthy"]:
            if (rec_state or {}).get("consecutive_recovery_failures", 0) > 0:
                rec_state["consecutive_recovery_failures"] = 0
                write_recovery_state(sup_root, rec_state)
            res = {
                "protocol": SUPERVISION_PROTOCOL,
                "supervision_state": "RUNNING_HEALTHY",
                "intent_valid": True,
                "recovery_authorized": True,
                "desired_physical_state": "RUNNING",
                "generation": gen,
                "intent_error": None,
                "observed_physical_runner": obs,
                "actuated_recovery_start": False,
            }
            log_tick_transaction(sup_root, {
                "timestamp_utc": tick_now_utc,
                "monotonic_ns": tick_mono_ns,
                "source": source,
                "decision": "RUNNING_HEALTHY_NO_OP",
                "supervision_state": "RUNNING_HEALTHY",
                "actuated_recovery_start": False,
            })
            return res

        # Process alive but ambiguous boot epoch or stale: ATTENTION_REQUIRED
        if obs["status"] == "RUNNING" and not obs["healthy"]:
            reason = obs.get("health_error", "RUNNER_NOT_HEALTHY")
            res = {
                "protocol": SUPERVISION_PROTOCOL,
                "supervision_state": "ATTENTION_REQUIRED",
                "intent_valid": True,
                "recovery_authorized": True,
                "desired_physical_state": "RUNNING",
                "generation": gen,
                "intent_error": reason,
                "observed_physical_runner": obs,
                "actuated_recovery_start": False,
            }
            log_tick_transaction(sup_root, {
                "timestamp_utc": tick_now_utc,
                "monotonic_ns": tick_mono_ns,
                "source": source,
                "decision": f"ATTENTION_REQUIRED_{reason}",
                "supervision_state": "ATTENTION_REQUIRED",
                "actuated_recovery_start": False,
            })
            return res

        # Runner is absent/stopped: Recovery candidate
        if in_cooldown:
            res = {
                "protocol": SUPERVISION_PROTOCOL,
                "supervision_state": "RECOVERY_COOLDOWN",
                "intent_valid": True,
                "recovery_authorized": True,
                "desired_physical_state": "RUNNING",
                "generation": gen,
                "intent_error": None,
                "observed_physical_runner": obs,
                "cooldown_remaining_seconds": round(remaining_s, 1),
                "actuated_recovery_start": False,
            }
            log_tick_transaction(sup_root, {
                "timestamp_utc": tick_now_utc,
                "monotonic_ns": tick_mono_ns,
                "source": source,
                "decision": "RECOVERY_COOLDOWN_ACTIVE",
                "supervision_state": "RECOVERY_COOLDOWN",
                "actuated_recovery_start": False,
            })
            return res

        # Cooldown expired: Perform AT MOST ONE recovery start
        rec_state = rec_state or {}
        rec_state["last_recovery_timestamp"] = now_epoch
        rec_state["last_recovery_attempt_utc"] = tick_now_utc

        rc, data, raw_out = invoke_physicalctl(["start"])
        time.sleep(0.5)
        fresh_obs = observe_physical_runner(supervision_root=sup_root)

        if fresh_obs["status"] == "RUNNING":
            rec_state["consecutive_recovery_failures"] = 0
            write_recovery_state(sup_root, rec_state)
            sup_state = "RUNNING_HEALTHY" if fresh_obs["healthy"] else "STARTING"
            actuation_res = f"SUCCESS_PID_{fresh_obs.get('pid')}"
        else:
            rec_state["consecutive_recovery_failures"] = rec_state.get("consecutive_recovery_failures", 0) + 1
            write_recovery_state(sup_root, rec_state)
            sup_state = "RECOVERY_FAILED"
            actuation_res = "FAILED_START"

        res = {
            "protocol": SUPERVISION_PROTOCOL,
            "supervision_state": sup_state,
            "intent_valid": True,
            "recovery_authorized": True,
            "desired_physical_state": "RUNNING",
            "generation": gen,
            "intent_error": None,
            "observed_physical_runner": fresh_obs,
            "actuated_recovery_start": True,
        }
        log_tick_transaction(sup_root, {
            "timestamp_utc": tick_now_utc,
            "monotonic_ns": tick_mono_ns,
            "source": source,
            "decision": "RECOVERY_ACTUATION",
            "actuation_result": actuation_res,
            "supervision_state": sup_state,
            "actuated_recovery_start": True,
        })
        return res


def get_supervision_status(root: Optional[Path] = None) -> Dict[str, Any]:
    """Read-only evaluation of supervision status without mutating state."""
    sup_root = root or get_default_supervision_root()
    desired, gen, raw_intent, err = read_intent(sup_root)
    obs = observe_physical_runner(supervision_root=sup_root)
    rec_state, rec_err = read_recovery_state(sup_root)
    now_epoch = time.time()
    in_cooldown, remaining_s = compute_cooldown(rec_state or {}, now_epoch)

    if err is not None:
        sup_state = "UNCONFIGURED" if err == "MISSING_INTENT" else "INTENT_INVALID"
        auth = False
    elif rec_err is not None:
        sup_state = "ATTENTION_REQUIRED"
        auth = False
    elif desired == "STOPPED":
        sup_state = "STOPPED" if obs["status"] == "STOPPED" else "STOPPED_BUT_RUNNER_RUNNING"
        auth = False
    elif obs["healthy"]:
        sup_state = "RUNNING_HEALTHY"
        auth = True
    elif obs["status"] == "RUNNING":
        sup_state = "ATTENTION_REQUIRED"
        auth = True
    elif in_cooldown:
        sup_state = "RECOVERY_COOLDOWN"
        auth = True
    else:
        sup_state = "RECOVERY_REQUIRED"
        auth = True

    return {
        "protocol": SUPERVISION_PROTOCOL,
        "supervision_state": sup_state,
        "intent_valid": err is None,
        "recovery_authorized": auth,
        "desired_physical_state": desired,
        "generation": gen,
        "intent_error": err or (f"RECOVERY_STATE_CORRUPT: {rec_err}" if rec_err else None),
        "observed_physical_runner": obs,
        "recovery_metrics": {
            "consecutive_recovery_failures": (rec_state or {}).get("consecutive_recovery_failures", 0),
            "last_recovery_attempt_utc": (rec_state or {}).get("last_recovery_attempt_utc"),
            "cooldown_remaining_seconds": round(remaining_s, 1),
        },
    }
