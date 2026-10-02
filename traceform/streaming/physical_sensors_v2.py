"""V2 hardware adapter with field-source evidence; frozen v1 remains unchanged."""

import json
import math
from pathlib import Path
import subprocess
import time
from typing import Any, Dict, List, Optional, Tuple

from pixel_physical import config

PRIMARY_SENSORS_ARG = (
    "LSM6DSR Accelerometer,"
    "LSM6DSR Gyroscope,"
    "Orientation Sensor,"
    "Step Counter,"
    "TMD3719 Ambient Light,"
    "ICP10101 Pressure Sensor,"
    "TMD3719 Proximity (wake-up),"
    "LSM6DSR Temperature"
)


def cleanup_sensors() -> None:
    """Releases active sensor listeners to save battery."""
    if Path(config.SENSOR_BIN).exists():
        try:
            subprocess.run([config.SENSOR_BIN, "-c"], timeout=2.0, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass


def sample_hardware_sensors(timeout: float = config.SENSOR_TIMEOUT_SECONDS) -> Tuple[Dict[str, Any], Dict[str, Any], str, str]:
    """
    Acquires a single combined reading from IMU and Environmental hardware sensors.
    Returns:
      (motion_dict, environment_dict, motion_availability, env_availability)
    """
    if not Path(config.SENSOR_BIN).exists():
        return (
            {"error": "SENSOR_BIN_NOT_FOUND"},
            {"error": "SENSOR_BIN_NOT_FOUND"},
            "UNAVAILABLE",
            "UNAVAILABLE",
        )

    cmd = [config.SENSOR_BIN, "-n", "1", "-s", PRIMARY_SENSORS_ARG]
    raw_data: Dict[str, Any] = {}
    try:
        res = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
        )
        if res.returncode == 0 and res.stdout.strip():
            parsed = json.loads(res.stdout.strip())
            if isinstance(parsed, dict):
                raw_data = parsed
    except subprocess.TimeoutExpired:
        cleanup_sensors()
        return (
            {"error": "SENSOR_TIMEOUT"},
            {"error": "SENSOR_TIMEOUT"},
            "TEMPORARILY_UNAVAILABLE",
            "TEMPORARILY_UNAVAILABLE",
        )
    except Exception as exc:
        cleanup_sensors()
        return (
            {"error": str(exc)},
            {"error": str(exc)},
            "ERROR",
            "ERROR",
        )
    finally:
        cleanup_sensors()

    # Process Motion Modality
    accel_data = raw_data.get("LSM6DSR Accelerometer", {}).get("values", [])
    gyro_data = raw_data.get("LSM6DSR Gyroscope", {}).get("values", [])
    orient_data = raw_data.get("Orientation Sensor", {}).get("values", [])
    step_data = raw_data.get("Step Counter", {}).get("values", [])

    motion_obj: Dict[str, Any] = {}
    motion_avail = "UNAVAILABLE"

    if accel_data and len(accel_data) >= 3:
        motion_avail = "AVAILABLE"
        ax, ay, az = float(accel_data[0]), float(accel_data[1]), float(accel_data[2])
        accel_mag = math.sqrt(ax * ax + ay * ay + az * az)
        accel_rms = math.sqrt((ax * ax + ay * ay + az * az) / 3.0)

        gx, gy, gz = 0.0, 0.0, 0.0
        if gyro_data and len(gyro_data) >= 3:
            gx, gy, gz = float(gyro_data[0]), float(gyro_data[1]), float(gyro_data[2])
        gyro_energy = gx * gx + gy * gy + gz * gz

        # Deviation from 1G (9.80665 m/s^2)
        dev_from_gravity = abs(accel_mag - 9.80665)
        motion_energy = round(dev_from_gravity + math.sqrt(gyro_energy), 4)

        # Stationary likelihood: 1.0 when near gravity and gyro is zero
        stationary_score = max(0.0, min(1.0, 1.0 - (motion_energy / 2.0)))

        orientation_deg = [round(float(v), 2) for v in orient_data[:3]] if orient_data else None
        step_count = int(step_data[0]) if step_data else None

        motion_obj = {
            "accel_magnitude": round(accel_mag, 4),
            "accel_rms": round(accel_rms, 4),
            "accel_raw": [round(ax, 4), round(ay, 4), round(az, 4)],
            "gyro_energy": round(gyro_energy, 6),
            "gyro_raw": [round(gx, 4), round(gy, 4), round(gz, 4)],
            "motion_energy": motion_energy,
            "stationary_likelihood": round(stationary_score, 3),
            "orientation_degrees": orientation_deg,
            "step_count": step_count,
        }
    else:
        motion_obj = {"error": "NO_ACCELEROMETER_SAMPLE"}

    # Process Environment Modality
    light_data = raw_data.get("TMD3719 Ambient Light", {}).get("values", [])
    press_data = raw_data.get("ICP10101 Pressure Sensor", {}).get("values", [])
    prox_data = raw_data.get("TMD3719 Proximity (wake-up)", {}).get("values", [])
    temp_data = raw_data.get("LSM6DSR Temperature", {}).get("values", [])

    env_obj: Dict[str, Any] = {}
    env_avail = "UNAVAILABLE"

    if light_data or press_data or prox_data:
        env_avail = "AVAILABLE"
        lux = round(float(light_data[0]), 2) if light_data else None
        press = round(float(press_data[0]), 2) if press_data else None
        prox = round(float(prox_data[0]), 1) if prox_data else None
        temp = round(float(temp_data[0]), 2) if temp_data else None

        env_obj = {
            "ambient_light_lux": lux,
            "pressure_hpa": press,
            "proximity_cm": prox,
            "device_temp_c": temp,
        }
    else:
        env_obj = {"error": "NO_ENVIRONMENTAL_SAMPLES"}

    # Preserve v1 numbers while recording whether their inputs actually arrived.
    if motion_avail == "AVAILABLE":
        gyro_status = "observed" if len(gyro_data) >= 3 else "imputed_zero"
        motion_obj["_field_provenance"] = {
            "accel_raw": "observed_rounded",
            "accel_magnitude": "derived",
            "accel_rms": "derived",
            "gyro_raw": gyro_status,
            "gyro_energy": "derived" if gyro_status == "observed" else "derived_with_imputed_input",
            "motion_energy": "derived" if gyro_status == "observed" else "derived_with_imputed_input",
            "stationary_likelihood": "derived" if gyro_status == "observed" else "derived_with_imputed_input",
            "orientation_degrees": "observed_rounded" if orient_data else "unavailable",
            "step_count": "observed" if step_data else "unavailable",
        }
    if env_avail == "AVAILABLE":
        env_obj["_field_provenance"] = {
            name: "observed_rounded" if values else "unavailable"
            for name, values in (("ambient_light_lux", light_data), ("pressure_hpa", press_data),
                                 ("proximity_cm", prox_data), ("device_temp_c", temp_data))
        }

    return motion_obj, env_obj, motion_avail, env_avail
