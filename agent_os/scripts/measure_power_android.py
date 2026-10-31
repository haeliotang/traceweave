#!/usr/bin/env python3
"""Sample battery level and temperature on an unplugged device and estimate drain.

Refuses to run while the device is charging, because charging samples cannot be
used to estimate consumption.
"""
import argparse
import json
import re
import subprocess
import sys
import time

FIELD = re.compile(r"^[ \t]*([A-Za-z ]+):[ \t]*(.+)$", re.MULTILINE)


def parse_battery(text):
    fields = {key.lower(): value.strip() for key, value in FIELD.findall(text)}
    powered = any(fields.get(name) == "true" for name in ("ac powered", "usb powered", "wireless powered"))
    level = fields.get("level")
    temperature = fields.get("temperature")
    return {
        "powered": powered,
        "level": int(level) if level and level.isdigit() else None,
        "temperature_c": (int(temperature) / 10.0) if temperature and temperature.lstrip("-").isdigit() else None,
    }


def estimate(samples, capacity_mah):
    """Consumption estimate from (seconds, level) samples."""
    if len(samples) < 2:
        raise ValueError("at least two samples are required")
    (first_seconds, first_level), (last_seconds, last_level) = samples[0], samples[-1]
    hours = (last_seconds - first_seconds) / 3600.0
    if hours <= 0:
        raise ValueError("samples must span a positive interval")
    lost_percent = first_level - last_level
    percent_per_hour = lost_percent / hours
    return {
        "hours": round(hours, 3),
        "lost_percent": lost_percent,
        "percent_per_hour": round(percent_per_hour, 2),
        "mah_per_hour": round(percent_per_hour / 100.0 * capacity_mah, 1),
    }


def sample(adb, serial):
    output = subprocess.run([adb, "-s", serial, "shell", "dumpsys", "battery"],
                            capture_output=True, text=True, check=True).stdout
    return parse_battery(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--minutes", type=float, default=10)
    parser.add_argument("--interval-seconds", type=float, default=60)
    parser.add_argument("--capacity-mah", type=float, default=4410)
    parser.add_argument("--output", required=True)
    parser.add_argument("--label", default="idle")
    args = parser.parse_args()

    first = sample(args.adb, args.serial)
    if first["powered"]:
        print("device is charging; unplug it before measuring", file=sys.stderr)
        sys.exit(2)

    started = time.time()
    samples = [(0.0, first["level"])]
    temperatures = [first["temperature_c"]]
    while time.time() - started < args.minutes * 60:
        time.sleep(args.interval_seconds)
        current = sample(args.adb, args.serial)
        samples.append((time.time() - started, current["level"]))
        if current["temperature_c"] is not None:
            temperatures.append(current["temperature_c"])

    result = {
        "label": args.label,
        "device": args.serial,
        "capacity_mah": args.capacity_mah,
        "samples": samples,
        "estimate": estimate(samples, args.capacity_mah),
        "temperature_c": {
            "start": temperatures[0],
            "end": temperatures[-1],
            "max": max(value for value in temperatures if value is not None),
        },
        "notes": [
            "single run; no thermal chamber or power meter",
            "screen state and background load were not controlled beyond the label",
        ],
    }
    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    print(json.dumps(result["estimate"]))


if __name__ == "__main__":
    main()
