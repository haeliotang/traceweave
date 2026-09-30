from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
import subprocess
import threading
import time
import json
import math
import os
import signal
import os
import queue
from pathlib import Path
from collections import deque


HOST = "127.0.0.1"
PORT = 8765

CORS_ORIGIN = "https://appassets.androidplatform.net"

ACCEL = "LSM6DSR Accelerometer"
LIGHT = "TMD3719 Ambient Light"
PRESSURE = "ICP10101 Pressure Sensor"

# Sampling intervals
FAST_INTERVAL = 3.0       # legacy sensor interval
SENSOR_STREAM_DELAY_MS = 500
SENSOR_STREAM_STALE = 10.0
SENSOR_STREAM_RESTART_DELAY = 1.0
SENSOR_STREAM_WATCHDOG_INTERVAL = 2.0
SENSOR_STREAM_WATCHDOG_TIMEOUT = 10.0
WIFI_INTERVAL = 10.0
LOCATION_INTERVAL = 20.0
BATTERY_INTERVAL = 30.0

# Freshness thresholds
FAST_STALE = 10.0
WIFI_STALE = 30.0
LOCATION_STALE = 60.0
BATTERY_STALE = 90.0

HISTORY_SIZE = 120
EVENT_SIZE = 100

# Persistent append-only event memory.
EVENT_STORE_DIR = (
    Path.home() / ".pixel_sensorium"
)
EVENT_STORE_PATH = (
    EVENT_STORE_DIR / "events.jsonl"
)

# Persistent lightweight record of physical sensor
# acquisition attempts. This is separate from world-state
# history: one row means one real sensor acquisition attempt.
ACQUISITION_STORE_PATH = (
    EVENT_STORE_DIR / "sensor_acquisitions.jsonl"
)

ACQUISITION_SIZE = 1000
sensor_acquisitions = deque(
    maxlen=ACQUISITION_SIZE
)


state_lock = threading.RLock()

raw_cache = {}
latest_state = None

history = deque(maxlen=HISTORY_SIZE)
events = deque(maxlen=EVENT_SIZE)

last_success = {}

sensor_acquisition = {
    "last_attempt_at": None,
    "last_success_at": None,
    "consecutive_failures": 0,
    "total_failures": 0,
    "last_error": None,
    "last_latency_ms": None,
}

sensor_stream_state = {
    "generation": 0,
    "started_at": None,
    "last_sample_at": None,
    "restart_reason": None,
}

def now():
    return time.time()


def run_termux(args, timeout=8):
    result = subprocess.run(
        args,
        capture_output=True,
        text=True,
        timeout=timeout,
    )

    if result.returncode != 0:
        raise RuntimeError(
            result.stderr.strip()
            or f"Command failed: {' '.join(args)}"
        )

    text = result.stdout.strip()

    try:
        return json.loads(text)
    except Exception:
        return {"result": text}


def collect(command, timeout=8):
    started = time.monotonic()

    try:
        data = run_termux(
            command,
            timeout=timeout,
        )

        return {
            "ok": True,
            "observed_at": now(),
            "latency_ms": round(
                (time.monotonic() - started) * 1000
            ),
            "data": data,
        }

    except subprocess.TimeoutExpired:
        return {
            "ok": False,
            "attempted_at": now(),
            "latency_ms": round(
                (time.monotonic() - started) * 1000
            ),
            "error": "timeout",
        }

    except Exception as e:
        return {
            "ok": False,
            "attempted_at": now(),
            "latency_ms": round(
                (time.monotonic() - started) * 1000
            ),
            "error": str(e),
        }


def update_cache(name, result):
    global raw_cache

    with state_lock:
        # A failed acquisition does NOT destroy the
        # last successful observation.
        if result.get("ok"):
            raw_cache[name] = result
            last_success[name] = result["observed_at"]

        else:
            previous = raw_cache.get(name)

            if previous:
                previous = dict(previous)
                previous["last_error"] = {
                    "at": result.get("attempted_at"),
                    "error": result.get("error"),
                }
                raw_cache[name] = previous

            else:
                raw_cache[name] = result


def freshness(name, stale_after):
    timestamp = last_success.get(name)

    if timestamp is None:
        return {
            "observed_at": None,
            "age_seconds": None,
            "stale": True,
        }

    age = max(0.0, now() - timestamp)

    return {
        "observed_at": timestamp,
        "age_seconds": round(age, 1),
        "stale": age > stale_after,
    }


def normalize():
    with state_lock:
        cache = dict(raw_cache)

    # ---------------- Battery ----------------

    b_raw = cache.get("battery", {})

    if b_raw.get("ok"):
        b = b_raw.get("data", {})

        battery = {
            "available": True,
            "percent": b.get("percentage"),
            "charging": (
                b.get("status") == "CHARGING"
            ),
            "status": b.get("status"),
            "health": b.get("health"),
            "temperature_c": b.get("temperature"),
            **freshness(
                "battery",
                BATTERY_STALE,
            ),
        }

    else:
        battery = {
            "available": False,
            **freshness(
                "battery",
                BATTERY_STALE,
            ),
        }

    # ---------------- Wi-Fi ----------------

    w_raw = cache.get("wifi", {})

    if w_raw.get("ok"):
        w = w_raw.get("data", {})

        ssid = w.get("ssid")

        if ssid in (
            "",
            "<unknown ssid>",
        ):
            ssid = None

        wifi = {
            "available": True,
            "connected": (
                w.get("supplicant_state")
                == "COMPLETED"
            ),
            "ssid": ssid,
            "rssi_dbm": w.get("rssi"),
            "frequency_mhz":
                w.get("frequency_mhz"),
            "link_speed_mbps":
                w.get("link_speed_mbps"),
            **freshness(
                "wifi",
                WIFI_STALE,
            ),
        }

    else:
        wifi = {
            "available": False,
            "connected": None,
            **freshness(
                "wifi",
                WIFI_STALE,
            ),
        }

    # ---------------- Location ----------------

    l_raw = cache.get("location", {})

    if l_raw.get("ok"):
        l = l_raw.get("data", {})

        location = {
            "available": True,
            "latitude": l.get("latitude"),
            "longitude": l.get("longitude"),
            "altitude_m": l.get("altitude"),
            "accuracy_m": l.get("accuracy"),
            "speed_m_s": l.get("speed"),
            "provider": l.get("provider"),
            **freshness(
                "location",
                LOCATION_STALE,
            ),
        }

    else:
        location = {
            "available": False,
            **freshness(
                "location",
                LOCATION_STALE,
            ),
        }

    # ---------------- Sensors ----------------

    s_raw = cache.get("sensors", {})

    sensor_data = (
        s_raw.get("data", {})
        if s_raw.get("ok")
        else {}
    )

    sensor_fresh = freshness(
        "sensors",
        FAST_STALE,
    )

    accel_values = (
        sensor_data
        .get(ACCEL, {})
        .get("values")
    )

    if isinstance(accel_values, list):
        accel_values = [
            round(float(v), 3)
            for v in accel_values
        ]

        motion = get_motion_estimate()

        acceleration = {
            "available": True,
            "xyz_m_s2": accel_values,
            "motion_state": motion.get("state", "unknown"),
            "motion_confidence": motion.get("confidence"),
            "motion_score": motion.get("score"),
            "window_samples": motion.get("window_samples"),
            **sensor_fresh,
        }

    else:
        acceleration = {
            "available": False,
            "motion_state": "unknown",
            **sensor_fresh,
        }

    light_values = (
        sensor_data
        .get(LIGHT, {})
        .get("values", [])
    )

    if light_values:
        light = {
            "available": True,
            "lux": round(
                float(light_values[0]),
                2,
            ),
            **sensor_fresh,
        }

    else:
        light = {
            "available": False,
            **sensor_fresh,
        }

    pressure_values = (
        sensor_data
        .get(PRESSURE, {})
        .get("values", [])
    )

    if pressure_values:
        pressure = {
            "available": True,
            "hpa": round(
                float(pressure_values[0]),
                2,
            ),
            **sensor_fresh,
        }

    else:
        pressure = {
            "available": False,
            **sensor_fresh,
        }

    return {
        "schema":
            "pixel-sensorium.world-state.v2",

        "generated_at": now(),

        "observation": {
            "battery": battery,
            "wifi": wifi,
            "location": location,

            "motion": {
                "acceleration": acceleration,
            },

            "environment": {
                "light": light,
                "pressure": pressure,
            },
        },

        "semantics": {
            "available_false_means":
                "no usable observation exists",

            "stale_true_means":
                "observation exists but may be outdated",

            "failed_refresh_means":
                "retain last successful observation",

            "motion_state_unknown_means":
                "single acceleration sample does not establish motion",
        },
    }



# ==================================================
# Motion Temporal State Estimator
# ==================================================

MOTION_WINDOW_SIZE = 6

# Require several consecutive candidate windows before
# accepting a state transition.
MOTION_CONFIRM_WINDOWS = 2

# Thresholds intentionally use hysteresis:
# entering MOVING is harder than remaining MOVING.
MOTION_ENTER_THRESHOLD = 0.22
MOTION_EXIT_THRESHOLD = 0.10

motion_samples = deque(maxlen=MOTION_WINDOW_SIZE)

motion_estimator = {
    "state": "unknown",
    "confidence": 0.0,
    "candidate": None,
    "candidate_count": 0,
    "window_samples": 0,
    "score": None,
    "updated_at": None,
}


def accel_magnitude(values):
    if not isinstance(values, list) or len(values) < 3:
        return None

    try:
        x, y, z = map(float, values[:3])
        return math.sqrt(
            x * x + y * y + z * z
        )
    except Exception:
        return None


def motion_window_score(samples):
    """
    Estimate short-window dynamic acceleration variability.

    Use changes in acceleration MAGNITUDE rather than changes
    in the raw XYZ vector.

    This makes the score substantially less sensitive to phone
    orientation changes, because gravity may rotate between axes
    while its magnitude remains approximately 9.81 m/s².

    Score = RMS change between consecutive acceleration magnitudes.

    This remains a motion heuristic, not a semantic activity
    classifier.
    """

    if len(samples) < 3:
        return None

    magnitudes = []

    for sample in samples:
        try:
            x, y, z = map(float, sample[:3])

            magnitudes.append(
                math.sqrt(
                    x * x
                    + y * y
                    + z * z
                )
            )
        except Exception:
            continue

    if len(magnitudes) < 3:
        return None

    changes = [
        b - a
        for a, b in zip(
            magnitudes[:-1],
            magnitudes[1:]
        )
    ]

    if not changes:
        return None

    return math.sqrt(
        sum(v * v for v in changes)
        / len(changes)
    )


def update_motion_estimator(values):
    global motion_estimator

    if not isinstance(values, list) or len(values) < 3:
        return

    try:
        sample = tuple(
            float(v)
            for v in values[:3]
        )
    except Exception:
        return

    with state_lock:
        motion_samples.append(sample)

        samples = list(motion_samples)

    score = motion_window_score(samples)

    if score is None:
        with state_lock:
            motion_estimator["window_samples"] = len(samples)
            motion_estimator["updated_at"] = now()
        return

    with state_lock:
        current = motion_estimator["state"]

    # Hysteresis:
    # unknown/stationary requires stronger evidence to enter moving.
    # moving requires sustained low variability to become stationary.
    if current == "moving":
        candidate = (
            "stationary"
            if score < MOTION_EXIT_THRESHOLD
            else "moving"
        )
    else:
        candidate = (
            "moving"
            if score > MOTION_ENTER_THRESHOLD
            else "stationary"
        )

    with state_lock:
        previous_candidate = motion_estimator["candidate"]

        if candidate == previous_candidate:
            motion_estimator["candidate_count"] += 1
        else:
            motion_estimator["candidate"] = candidate
            motion_estimator["candidate_count"] = 1

        motion_estimator["score"] = round(score, 4)
        motion_estimator["window_samples"] = len(samples)
        motion_estimator["updated_at"] = now()

        count = motion_estimator["candidate_count"]
        old_state = motion_estimator["state"]

        # Rough confidence is about persistence, not a calibrated
        # statistical probability.
        motion_estimator["confidence"] = round(
            min(
                1.0,
                count / MOTION_CONFIRM_WINDOWS
            ),
            2,
        )

        if (
            count >= MOTION_CONFIRM_WINDOWS
            and candidate != old_state
        ):
            motion_estimator["state"] = candidate
            motion_estimator["confidence"] = 1.0
            motion_estimator["candidate_count"] = 0
            motion_estimator["candidate"] = None

            new_state = candidate

        else:
            new_state = None

    if new_state is not None:
        # Do not create a transition event for initial
        # unknown -> stationary initialization.
        if old_state == "stationary" and new_state == "moving":
            add_event(
                "motion_started",
                from_state="stationary",
                to_state="moving",
                score=round(score, 4),
            )

        elif old_state == "moving" and new_state == "stationary":
            add_event(
                "motion_stopped",
                from_state="moving",
                to_state="stationary",
                score=round(score, 4),
            )


def get_motion_estimate():
    with state_lock:
        return dict(motion_estimator)


def persist_sensor_acquisition(record):
    """
    Append one physical sensor acquisition attempt.

    This ledger records observation coverage, not semantic
    events. Failure to persist must not stop Sensorium.
    """

    try:
        EVENT_STORE_DIR.mkdir(
            parents=True,
            exist_ok=True,
        )

        line = json.dumps(
            record,
            ensure_ascii=False,
            separators=(",", ":"),
        )

        with ACQUISITION_STORE_PATH.open(
            "a",
            encoding="utf-8",
        ) as f:
            f.write(line + "\n")

    except Exception as e:
        print(
            "Acquisition persistence warning:",
            type(e).__name__,
            str(e),
            flush=True,
        )


def record_sensor_acquisition(result):
    """
    Record exactly one real sensor acquisition attempt.
    """

    if result.get("ok"):
        record = {
            "timestamp":
                result.get("observed_at"),
            "ok": True,
            "latency_ms":
                result.get("latency_ms"),
        }
    else:
        record = {
            "timestamp":
                result.get(
                    "attempted_at",
                    now(),
                ),
            "ok": False,
            "latency_ms":
                result.get("latency_ms"),
            "error":
                result.get("error"),
        }

    try:
        record["timestamp"] = float(
            record["timestamp"]
        )
    except Exception:
        record["timestamp"] = now()

    with state_lock:
        sensor_acquisitions.append(record)

    persist_sensor_acquisition(record)


def load_sensor_acquisitions():
    """
    Restore the newest valid acquisition records.
    """

    if not ACQUISITION_STORE_PATH.exists():
        return 0

    restored = []

    try:
        with ACQUISITION_STORE_PATH.open(
            "r",
            encoding="utf-8",
        ) as f:
            for line in f:
                line = line.strip()

                if not line:
                    continue

                try:
                    record = json.loads(line)
                except Exception:
                    continue

                if not isinstance(record, dict):
                    continue

                try:
                    timestamp = float(
                        record.get("timestamp")
                    )
                except Exception:
                    continue

                if timestamp > now() + 300:
                    continue

                record["timestamp"] = timestamp
                record["ok"] = bool(
                    record.get("ok")
                )

                restored.append(record)

    except Exception as e:
        print(
            "Acquisition restore warning:",
            type(e).__name__,
            str(e),
            flush=True,
        )
        return 0

    restored.sort(
        key=lambda r: r["timestamp"]
    )

    restored = restored[
        -ACQUISITION_SIZE:
    ]

    with state_lock:
        sensor_acquisitions.clear()
        sensor_acquisitions.extend(
            restored
        )

    return len(restored)


def persist_event(event):
    """
    Append one observed event to the persistent event log.

    Persistence failure must not prevent the in-memory
    Sensorium from continuing to operate.
    """

    try:
        EVENT_STORE_DIR.mkdir(
            parents=True,
            exist_ok=True,
        )

        line = json.dumps(
            event,
            ensure_ascii=False,
            separators=(",", ":"),
        )

        with EVENT_STORE_PATH.open(
            "a",
            encoding="utf-8",
        ) as f:
            f.write(line + "\n")

    except Exception as e:
        print(
            "Event persistence warning:",
            type(e).__name__,
            str(e),
            flush=True,
        )


def load_persisted_events():
    """
    Restore valid persisted events into the bounded RAM deque.

    Corrupt lines are ignored. Restored events retain their
    original timestamps and are NOT written back to disk.
    """

    if not EVENT_STORE_PATH.exists():
        return 0

    restored = []

    try:
        with EVENT_STORE_PATH.open(
            "r",
            encoding="utf-8",
        ) as f:
            for line in f:
                line = line.strip()

                if not line:
                    continue

                try:
                    event = json.loads(line)
                except Exception:
                    continue

                if not isinstance(event, dict):
                    continue

                event_type = event.get("type")
                timestamp = event.get("timestamp")

                if not isinstance(
                    event_type,
                    str,
                ):
                    continue

                try:
                    timestamp = float(timestamp)
                except Exception:
                    continue

                # Reject impossible future timestamps.
                if timestamp > now() + 300:
                    continue

                event["timestamp"] = timestamp
                restored.append(event)

    except Exception as e:
        print(
            "Event restore warning:",
            type(e).__name__,
            str(e),
            flush=True,
        )
        return 0

    # Establish deterministic temporal order before retaining
    # the newest EVENT_SIZE observations.
    restored.sort(
        key=lambda e: e["timestamp"]
    )

    restored = restored[-EVENT_SIZE:]

    with state_lock:
        events.clear()
        events.extend(restored)

    return len(restored)


def add_event(event_type, **data):
    event = {
        "type": event_type,
        "timestamp": now(),
        **data,
    }

    with state_lock:
        events.append(event)

    persist_event(event)


def detect_events(previous, current):
    if not previous:
        return

    try:
        p = previous["observation"]
        c = current["observation"]

        # ---------- Charging ----------
        pb = p["battery"]
        cb = c["battery"]

        if (
            pb.get("available")
            and cb.get("available")
        ):
            if (
                not pb.get("charging")
                and cb.get("charging")
            ):
                add_event(
                    "charging_started"
                )

            elif (
                pb.get("charging")
                and not cb.get("charging")
            ):
                add_event(
                    "charging_stopped"
                )

        # ---------- Wi-Fi ----------
        pw = p["wifi"]
        cw = c["wifi"]

        if (
            pw.get("available")
            and cw.get("available")
        ):
            old_ssid = pw.get("ssid")
            new_ssid = cw.get("ssid")

            # Unknown SSID is epistemic uncertainty,
            # not evidence that the network changed.
            #
            # Only emit wifi_changed when BOTH observations
            # contain known SSIDs and they genuinely differ.
            if (
                old_ssid is not None
                and new_ssid is not None
                and old_ssid != new_ssid
            ):
                add_event(
                    "wifi_changed",
                    from_ssid=old_ssid,
                    to_ssid=new_ssid,
                )

        # ---------- Light ----------
        pl = (
            p["environment"]
            ["light"]
        )

        cl = (
            c["environment"]
            ["light"]
        )

        if (
            pl.get("available")
            and cl.get("available")
        ):
            old_lux = pl.get("lux")
            new_lux = cl.get("lux")

            if (
                old_lux is not None
                and new_lux is not None
            ):
                # Conservative ratio-based detector.
                if (
                    old_lux >= 10
                    and new_lux
                    < old_lux * 0.25
                ):
                    add_event(
                        "environment_became_darker",
                        from_lux=old_lux,
                        to_lux=new_lux,
                    )

                elif (
                    old_lux <= 10
                    and new_lux
                    > max(
                        25,
                        old_lux * 4,
                    )
                ):
                    add_event(
                        "environment_became_brighter",
                        from_lux=old_lux,
                        to_lux=new_lux,
                    )

    except Exception:
        # Event detection must never break
        # perception.
        pass


def publish_state():
    global latest_state

    current = normalize()

    with state_lock:
        previous = latest_state
        latest_state = current
        history.append(current)

    detect_events(
        previous,
        current,
    )



def iter_json_stream(stream):
    """
    Incrementally decode consecutive JSON documents from a
    binary termux-sensor stdout pipe.

    Uses os.read() rather than TextIOWrapper.read()/readline()
    so persistent sensor output does not depend on Python text
    buffering or EOF.
    """

    decoder = json.JSONDecoder()
    text_buffer = ""
    byte_buffer = b""

    fd = stream.fileno()

    while True:
        chunk = os.read(fd, 4096)

        if chunk == b"":
            # Real EOF.
            if byte_buffer:
                try:
                    text_buffer += byte_buffer.decode(
                        "utf-8"
                    )
                except UnicodeDecodeError:
                    text_buffer += byte_buffer.decode(
                        "utf-8",
                        errors="replace",
                    )

            stripped = text_buffer.lstrip()

            if stripped:
                try:
                    value, _ = decoder.raw_decode(
                        stripped
                    )
                    yield value
                except Exception:
                    pass

            return

        byte_buffer += chunk

        # termux-sensor emits ordinary UTF-8 JSON. Keep an
        # incomplete trailing UTF-8 sequence for the next read.
        while byte_buffer:
            try:
                decoded = byte_buffer.decode(
                    "utf-8"
                )
                byte_buffer = b""
                break

            except UnicodeDecodeError as e:
                if e.reason == "unexpected end of data":
                    prefix = byte_buffer[:e.start]

                    if prefix:
                        text_buffer += prefix.decode(
                            "utf-8"
                        )

                    byte_buffer = byte_buffer[e.start:]
                    decoded = ""
                    break

                decoded = byte_buffer.decode(
                    "utf-8",
                    errors="replace",
                )
                byte_buffer = b""
                break

        text_buffer += decoded

        while True:
            stripped = text_buffer.lstrip()

            if not stripped:
                text_buffer = ""
                break

            try:
                value, end = decoder.raw_decode(
                    stripped
                )

            except json.JSONDecodeError:
                # Current JSON document is incomplete.
                break

            yield value

            text_buffer = stripped[end:]

def _sensor_stream_reader(stream, output_queue):
    """
    Decode the blocking termux-sensor stdout stream in a
    dedicated reader thread.

    sensor_loop remains the sole subprocess lifecycle owner.
    """

    try:
        for value in iter_json_stream(stream):
            output_queue.put(
                ("sample", value)
            )

        output_queue.put(
            ("eof", None)
        )

    except Exception as e:
        output_queue.put(
            (
                "error",
                type(e).__name__
                + ":"
                + str(e),
            )
        )


def sensor_loop():
    """
    Persistent physical sensor stream with owner-side timeout.

    The reader thread may block on stdout, but sensor_loop
    itself never blocks indefinitely. sensor_loop exclusively
    owns terminate/kill/restart of the termux-sensor process.

    Missing observations are epistemic uncertainty and must
    never be interpreted as physical state transitions.
    """

    global sensor_acquisition

    while True:
        process = None
        failure = None
        failure_at = None
        sample_queue = queue.Queue()

        stream_started_at = now()

        try:
            with state_lock:
                sensor_stream_state[
                    "generation"
                ] += 1

                sensor_stream_state[
                    "started_at"
                ] = stream_started_at

                sensor_stream_state[
                    "last_sample_at"
                ] = None

                sensor_stream_state[
                    "restart_reason"
                ] = None

            process = subprocess.Popen(
                [
                    "termux-sensor",
                    "-s",
                    f"{ACCEL},{LIGHT},{PRESSURE}",
                    "-d",
                    str(SENSOR_STREAM_DELAY_MS),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=False,
                bufsize=0,
                start_new_session=True,
            )

            with state_lock:
                sensor_acquisition[
                    "last_attempt_at"
                ] = stream_started_at

                sensor_acquisition[
                    "last_error"
                ] = None

            reader = threading.Thread(
                target=_sensor_stream_reader,
                args=(
                    process.stdout,
                    sample_queue,
                ),
                daemon=True,
                name="sensor_stream_reader",
            )

            reader.start()

            while True:
                try:
                    kind, payload = sample_queue.get(
                        timeout=
                            SENSOR_STREAM_WATCHDOG_TIMEOUT
                    )

                except queue.Empty:
                    failure_at = now()

                    with state_lock:
                        sensor_stream_state[
                            "restart_reason"
                        ] = "sensor_stream_stalled"

                    failure = {
                        "ok": False,
                        "attempted_at": failure_at,
                        "latency_ms": None,
                        "error":
                            "sensor_stream_stalled",
                    }

                    break

                if kind == "sample":
                    sensor_data = payload

                    if not isinstance(
                        sensor_data,
                        dict,
                    ):
                        continue

                    observed_at = now()

                    with state_lock:
                        sensor_stream_state[
                            "last_sample_at"
                        ] = observed_at

                    result = {
                        "ok": True,
                        "observed_at":
                            observed_at,
                        "latency_ms": None,
                        "data": sensor_data,
                    }

                    with state_lock:
                        sensor_acquisition[
                            "last_attempt_at"
                        ] = observed_at

                        sensor_acquisition[
                            "last_success_at"
                        ] = observed_at

                        sensor_acquisition[
                            "consecutive_failures"
                        ] = 0

                        sensor_acquisition[
                            "last_error"
                        ] = None

                        sensor_acquisition[
                            "last_latency_ms"
                        ] = None

                    record_sensor_acquisition(
                        result
                    )

                    update_cache(
                        "sensors",
                        result,
                    )

                    accel_values = (
                        sensor_data
                        .get(ACCEL, {})
                        .get("values")
                    )

                    update_motion_estimator(
                        accel_values
                    )

                    publish_state()

                    continue

                if kind == "eof":
                    failure_at = now()

                    return_code = process.poll()

                    if return_code is None:
                        try:
                            return_code = (
                                process.wait(
                                    timeout=0.5
                                )
                            )
                        except Exception:
                            return_code = (
                                "unknown"
                            )

                    failure = {
                        "ok": False,
                        "attempted_at":
                            failure_at,
                        "latency_ms": None,
                        "error":
                            "sensor_stream_exited:"
                            + str(return_code),
                    }

                    break

                if kind == "error":
                    failure_at = now()

                    failure = {
                        "ok": False,
                        "attempted_at":
                            failure_at,
                        "latency_ms": None,
                        "error":
                            "sensor_stream_reader:"
                            + str(payload),
                    }

                    break

        except Exception as e:
            failure_at = now()

            failure = {
                "ok": False,
                "attempted_at":
                    failure_at,
                "latency_ms": None,
                "error":
                    "sensor_stream_failure:"
                    + type(e).__name__
                    + ":"
                    + str(e),
            }

        finally:
            if process is not None:
                # One sensor generation is one process group.
                #
                # termux-sensor may spawn the real
                # termux-api Sensor helper. Killing only the
                # wrapper PID can therefore leave an orphaned
                # physical sensor subscription behind.
                #
                # Never start the next generation until this
                # generation has received group-level cleanup.

                try:
                    pgid = os.getpgid(process.pid)
                except Exception:
                    pgid = None

                if pgid is not None:
                    try:
                        os.killpg(
                            pgid,
                            signal.SIGTERM,
                        )
                    except ProcessLookupError:
                        pass
                    except Exception:
                        pass

                else:
                    try:
                        if process.poll() is None:
                            process.terminate()
                    except Exception:
                        pass

                try:
                    process.wait(timeout=2)

                except subprocess.TimeoutExpired:
                    if pgid is not None:
                        try:
                            os.killpg(
                                pgid,
                                signal.SIGKILL,
                            )
                        except ProcessLookupError:
                            pass
                        except Exception:
                            pass

                    else:
                        try:
                            process.kill()
                        except Exception:
                            pass

                    try:
                        process.wait(timeout=2)
                    except Exception:
                        pass

                except Exception:
                    pass

                # Closing the pipes releases any generation-local
                # reader thread still blocked in read().
                try:
                    if process.stdout is not None:
                        process.stdout.close()
                except Exception:
                    pass

                try:
                    if process.stderr is not None:
                        process.stderr.close()
                except Exception:
                    pass

        if failure is None:
            failure_at = now()

            failure = {
                "ok": False,
                "attempted_at":
                    failure_at,
                "latency_ms": None,
                "error":
                    "sensor_stream_unknown_failure",
            }

        with state_lock:
            sensor_acquisition[
                "last_attempt_at"
            ] = failure_at

            sensor_acquisition[
                "consecutive_failures"
            ] += 1

            sensor_acquisition[
                "total_failures"
            ] += 1

            sensor_acquisition[
                "last_error"
            ] = failure.get("error")

            sensor_acquisition[
                "last_latency_ms"
            ] = None

        record_sensor_acquisition(
            failure
        )

        # Preserve the last successful physical observation.
        # Failure only adds uncertainty/failure metadata.
        update_cache(
            "sensors",
            failure,
        )

        publish_state()

        time.sleep(
            SENSOR_STREAM_RESTART_DELAY
        )

def wifi_loop():
    while True:
        result = collect(
            [
                "termux-wifi-connectioninfo"
            ],
            timeout=8,
        )

        update_cache(
            "wifi",
            result,
        )

        publish_state()

        time.sleep(WIFI_INTERVAL)


def location_loop():
    while True:
        result = collect(
            [
                "termux-location",
                "-p", "network",
                "-r", "once",
            ],
            timeout=10,
        )

        update_cache(
            "location",
            result,
        )

        publish_state()

        time.sleep(LOCATION_INTERVAL)


def battery_loop():
    while True:
        result = collect(
            [
                "termux-battery-status"
            ],
            timeout=8,
        )

        update_cache(
            "battery",
            result,
        )

        publish_state()

        time.sleep(BATTERY_INTERVAL)


def start_sampler():
    loops = [
        sensor_loop,
        wifi_loop,
        location_loop,
        battery_loop,
    ]

    for fn in loops:
        thread = threading.Thread(
            target=fn,
            daemon=True,
            name=fn.__name__,
        )

        thread.start()


def current_world_state():
    with state_lock:
        state = latest_state

    if state is None:
        return {
            "schema":
                "pixel-sensorium.world-state.v2",

            "status": "warming_up",
            "observation": {},
        }

    # Re-normalize so age_seconds/stale
    # reflects query time rather than publication time.
    return normalize()


def raw_world_state():
    with state_lock:
        return {
            "timestamp": now(),
            "cache": dict(raw_cache),
        }


def recent_events(limit=20):
    with state_lock:
        selected = list(events)[-limit:]

    current_time = now()

    result = []

    for event in reversed(selected):
        e = dict(event)

        e["age_seconds"] = round(
            current_time
            - e["timestamp"],
            1,
        )

        result.append(e)

    return result


def recent_history(limit=10):
    with state_lock:
        return list(history)[-limit:]



def build_movement_episodes(
    since=600.0,
    limit=10,
):
    """
    Build deterministic movement episodes from the existing
    event stream.

    An episode begins at motion_started and closes at the next
    motion_stopped.

    Other detected events occurring between those boundaries
    are attached to the episode as 'during' events.

    No semantic activity such as walking, carrying, travelling,
    etc. is inferred here.
    """

    current = now()

    # recent_events() is already the canonical v5 event-memory
    # access layer. Request the retained event set and establish
    # our own deterministic ordering.
    source = recent_events(100)

    timeline = []

    for item in source:
        timestamp = item.get("timestamp")

        if timestamp is None:
            continue

        try:
            timestamp = float(timestamp)
        except Exception:
            continue

        age = current - timestamp

        if age < 0 or age > since:
            continue

        timeline.append(
            (
                timestamp,
                dict(item),
            )
        )

    timeline.sort(
        key=lambda pair: pair[0]
    )

    episodes = []
    active = None

    for timestamp, item in timeline:
        event_type = item.get("type")

        if event_type == "motion_started":

            # If another start arrives before a stop, retain the
            # newest boundary rather than inventing an episode.
            active = {
                "start_timestamp": timestamp,
                "during": [],
            }

            continue

        if event_type == "motion_stopped":
            if active is None:
                continue

            start_timestamp = active[
                "start_timestamp"
            ]

            duration = max(
                0.0,
                timestamp - start_timestamp,
            )

            episode = {
                "episode": "movement",
                "started_seconds_ago": int(
                    round(
                        current - start_timestamp
                    )
                ),
                "ended_seconds_ago": int(
                    round(
                        current - timestamp
                    )
                ),
                "duration_seconds": int(
                    round(duration)
                ),
                "during": active["during"],
                "complete": True,
            }

            episodes.append(episode)
            active = None

            continue

        # Any non-motion event occurring inside a movement
        # interval becomes a temporal relation, not a new
        # inferred physical activity.
        if active is not None:
            related = {
                "event": event_type,
                "seconds_after_start": int(
                    round(
                        timestamp
                        - active["start_timestamp"]
                    )
                ),
            }

            if "from_lux" in item:
                try:
                    related["from_lux"] = round(
                        float(item["from_lux"]),
                        1,
                    )
                except Exception:
                    pass

            if "to_lux" in item:
                try:
                    related["to_lux"] = round(
                        float(item["to_lux"]),
                        1,
                    )
                except Exception:
                    pass

            if "from_ssid" in item:
                related["from_ssid"] = (
                    item["from_ssid"]
                )

            if "to_ssid" in item:
                related["to_ssid"] = (
                    item["to_ssid"]
                )

            active["during"].append(related)

    # Expose an unfinished episode explicitly rather than
    # pretending that a stop boundary was observed.
    if active is not None:
        start_timestamp = active[
            "start_timestamp"
        ]

        episodes.append({
            "episode": "movement",
            "started_seconds_ago": int(
                round(
                    current - start_timestamp
                )
            ),
            "ended_seconds_ago": None,
            "duration_seconds": None,
            "during": active["during"],
            "complete": False,
        })

    return episodes[-limit:]

class Handler(BaseHTTPRequestHandler):

    def cors_headers(self):
        self.send_header(
            "Access-Control-Allow-Origin",
            CORS_ORIGIN,
        )

        self.send_header(
            "Access-Control-Allow-Methods",
            "GET, OPTIONS",
        )

        self.send_header(
            "Access-Control-Allow-Headers",
            "Content-Type",
        )

    def send_json(self, data, status=200):
        body = json.dumps(
            data,
            ensure_ascii=False,
        ).encode("utf-8")

        self.send_response(status)

        self.send_header(
            "Content-Type",
            "application/json; charset=utf-8",
        )

        self.send_header(
            "Content-Length",
            str(len(body)),
        )

        self.cors_headers()

        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.cors_headers()
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        try:
            if path == "/health":
                with state_lock:
                    cache_keys = list(
                        raw_cache.keys()
                    )

                data = {
                    "status": "ok",
                    "service":
                        "Pixel Sensorium",
                    "version": "6.5.3",
                    "mode":
                        "persistent",
                    "cache_sources":
                        cache_keys,
                }

            elif path == "/world-state":
                data = current_world_state()

            elif path == "/world-state/raw":
                data = raw_world_state()

            elif path == "/events":
                # LLM-facing temporal query layer.
                #
                # since=N:
                # only events from the previous N seconds.
                #
                # limit=N:
                # maximum number of events returned.
                #
                # Output is deliberately compressed for small
                # local language models.

                try:
                    limit = int(
                        query.get(
                            "limit",
                            ["10"],
                        )[0]
                    )
                except Exception:
                    limit = 10

                limit = max(
                    1,
                    min(limit, 100),
                )

                try:
                    since = float(
                        query.get(
                            "since",
                            ["120"],
                        )[0]
                    )
                except Exception:
                    since = 120.0

                since = max(
                    1.0,
                    min(since, 86400.0),
                )

                current = now()

                # Ask the existing, already-tested event layer
                # for all events currently retained in memory.
                source_events = recent_events(100)

                filtered = []

                for item in source_events:
                    timestamp = item.get("timestamp")

                    if timestamp is None:
                        continue

                    try:
                        age = current - float(timestamp)
                    except Exception:
                        continue

                    if age < 0 or age > since:
                        continue

                    event = {
                        "event": item.get("type"),
                        "seconds_ago": int(
                            round(age)
                        ),
                    }

                    # Preserve useful semantic transition fields.
                    if "from_state" in item:
                        event["from"] = item["from_state"]

                    if "to_state" in item:
                        event["to"] = item["to_state"]

                    if "from_ssid" in item:
                        event["from_ssid"] = item["from_ssid"]

                    if "to_ssid" in item:
                        event["to_ssid"] = item["to_ssid"]

                    if "from_lux" in item:
                        try:
                            event["from_lux"] = round(
                                float(item["from_lux"]),
                                1,
                            )
                        except Exception:
                            pass

                    if "to_lux" in item:
                        try:
                            event["to_lux"] = round(
                                float(item["to_lux"]),
                                1,
                            )
                        except Exception:
                            pass

                    filtered.append(
                        (
                            float(timestamp),
                            event,
                        )
                    )

                # recent_events() currently exposes newest-first.
                # Do not rely on that implementation detail:
                # explicitly establish chronological order.
                filtered.sort(
                    key=lambda pair: pair[0]
                )

                # Keep newest N, while retaining oldest -> newest
                # order inside the selected temporal window.
                filtered = filtered[-limit:]

                data = {
                    "schema":
                        "pixel-sensorium.events.v2",
                    "window_seconds":
                        int(since),
                    "order":
                        "oldest_to_newest",
                    "events": [
                        event
                        for _, event in filtered
                    ],
                }

            elif path == "/episodes":
                try:
                    since = float(
                        query.get(
                            "since",
                            ["600"],
                        )[0]
                    )
                except Exception:
                    since = 600.0

                since = max(
                    1.0,
                    min(since, 86400.0),
                )

                try:
                    limit = int(
                        query.get(
                            "limit",
                            ["10"],
                        )[0]
                    )
                except Exception:
                    limit = 10

                limit = max(
                    1,
                    min(limit, 50),
                )

                data = {
                    "schema":
                        "pixel-sensorium.episodes.v1",
                    "window_seconds":
                        int(since),
                    "order":
                        "oldest_to_newest",
                    "episodes":
                        build_movement_episodes(
                            since=since,
                            limit=limit,
                        ),
                    "semantics": {
                        "movement":
                            "A motion_started event followed "
                            "by a motion_stopped event.",
                        "complete_false":
                            "A start boundary was observed "
                            "but no stop boundary has yet "
                            "been observed.",
                        "during":
                            "Detected events temporally "
                            "inside the movement interval.",
                        "does_not_mean":
                            "Movement does not establish "
                            "walking, carrying, travelling, "
                            "or user activity."
                    },
                }

            elif path == "/history":
                try:
                    limit = int(
                        query.get(
                            "limit",
                            ["10"],
                        )[0]
                    )
                except Exception:
                    limit = 10

                limit = max(
                    1,
                    min(limit, 50),
                )

                data = {
                    "history":
                        recent_history(limit)
                }

            # Live endpoints remain available
            # for diagnostics.

            elif path == "/battery":
                data = run_termux(
                    [
                        "termux-battery-status"
                    ],
                    timeout=8,
                )

            elif path == "/wifi":
                data = run_termux(
                    [
                        "termux-wifi-connectioninfo"
                    ],
                    timeout=8,
                )

            elif path == "/location":
                provider = query.get(
                    "provider",
                    ["network"],
                )[0]

                if provider not in (
                    "network",
                    "gps",
                    "passive",
                ):
                    provider = "network"

                data = run_termux(
                    [
                        "termux-location",
                        "-p", provider,
                        "-r", "once",
                    ],
                    timeout=10,
                )

            elif path == "/sensors":
                data = run_termux(
                    [
                        "termux-sensor",
                        "-l",
                    ],
                    timeout=8,
                )

            elif path == "/sensor":
                sensor = query.get(
                    "name",
                    ["all"],
                )[0]

                data = run_termux(
                    [
                        "termux-sensor",
                        "-s", sensor,
                        "-n", "1",
                    ],
                    timeout=8,
                )

            else:
                self.send_json(
                    {
                        "error":
                            "not found"
                    },
                    404,
                )
                return

            self.send_json(data)

        except subprocess.TimeoutExpired:
            self.send_json(
                {"error": "timeout"},
                504,
            )

        except Exception as e:
            self.send_json(
                {
                    "error": str(e)
                },
                500,
            )

    def log_message(
        self,
        format,
        *args
    ):
        pass


if __name__ == "__main__":
    restored = load_persisted_events()
    restored_acquisitions = (
        load_sensor_acquisitions()
    )

    print(
        f"Restored {restored} persisted events.",
        flush=True,
    )

    print(
        "Restored "
        f"{restored_acquisitions} "
        "sensor acquisition records.",
        flush=True,
    )

    start_sampler()

    print(
        "Pixel Sensorium v6.5.3 "
        f"listening on http://{HOST}:{PORT}",
        flush=True,
    )

    ThreadingHTTPServer(
        (HOST, PORT),
        Handler,
    ).serve_forever()
