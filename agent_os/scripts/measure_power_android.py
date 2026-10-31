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
    counter = fields.get("charge counter")
    return {
        "powered": powered,
        "level": int(level) if level and level.isdigit() else None,
        "temperature_c": (int(temperature) / 10.0) if temperature and temperature.lstrip("-").isdigit() else None,
        "charge_uah": int(counter) if counter and counter.lstrip("-").isdigit() else None,
    }


def estimate_from_charge(samples):
    """Consumption from (seconds, microamp-hours) samples; far finer than whole-percent levels."""
    if len(samples) < 2:
        raise ValueError("at least two samples are required")
    (first_seconds, first_charge), (last_seconds, last_charge) = samples[0], samples[-1]
    hours = (last_seconds - first_seconds) / 3600.0
    if hours <= 0:
        raise ValueError("samples must span a positive interval")
    used_uah = first_charge - last_charge
    return {
        "hours": round(hours, 3),
        "used_mah": round(used_uah / 1000.0, 1),
        "mah_per_hour": round((used_uah / 1000.0) / hours, 1),
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


def parse_readings(values):
    """Parse 'HH:MM level' strings into (seconds, level) samples sorted by time."""
    from datetime import datetime

    samples = []
    for value in values:
        clock, level = value.split()
        moment = datetime.strptime(clock, "%H:%M")
        samples.append((moment.hour * 3600 + moment.minute * 60, int(level)))
    samples.sort()
    return samples


def sample(adb, serial):
    output = subprocess.run([adb, "-s", serial, "shell", "dumpsys", "battery"],
                            capture_output=True, text=True, check=True).stdout
    return parse_battery(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", help="Required unless --reading is used")
    parser.add_argument("--minutes", type=float, default=10)
    parser.add_argument("--interval-seconds", type=float, default=60)
    parser.add_argument("--capacity-mah", type=float, default=4410)
    parser.add_argument("--output", required=True)
    parser.add_argument("--label", default="idle")
    parser.add_argument("--wait-for-unplug", action="store_true",
                        help="Poll until the device stops charging, then sample")
    parser.add_argument("--wait-timeout-minutes", type=float, default=60)
    parser.add_argument("--reading", action="append", dest="readings",
                        help="Manual 'HH:MM level' reading, repeatable; skips the device")
    args = parser.parse_args()

    if args.readings:
        samples = parse_readings(args.readings)
        result = {
            "label": args.label,
            "device": "manual",
            "capacity_mah": args.capacity_mah,
            "samples": samples,
            "estimate": estimate_value,
        "charge_samples": charge_samples,
            "temperature_c": {"start": None, "end": None, "max": None},
            "notes": ["computed from manually reported readings", "no temperature data"],
        }
        with open(args.output, "w", encoding="utf-8") as handle:
            json.dump(result, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        print(json.dumps(result["estimate"]))
        return

    if not args.serial:
        parser.error("--serial is required when sampling the device")
    first = sample(args.adb, args.serial)
    if first["powered"] and args.wait_for_unplug:
        deadline = time.time() + args.wait_timeout_minutes * 60
        while first["powered"] and time.time() < deadline:
            time.sleep(5)
            first = sample(args.adb, args.serial)
    if first["powered"]:
        print("device is charging; unplug it before measuring", file=sys.stderr)
        sys.exit(2)

    started = time.time()
    samples = [(0.0, first["level"])]
    charge_samples = [(0.0, first["charge_uah"])] if first.get("charge_uah") else []
    temperatures = [first["temperature_c"]]
    while time.time() - started < args.minutes * 60:
        time.sleep(args.interval_seconds)
        current = sample(args.adb, args.serial)
        samples.append((time.time() - started, current["level"]))
        if current.get("charge_uah"):
            charge_samples.append((time.time() - started, current["charge_uah"]))
        if current["temperature_c"] is not None:
            temperatures.append(current["temperature_c"])

    estimate_value = estimate(samples, args.capacity_mah)
    if len(charge_samples) >= 2:
        estimate_value = estimate_from_charge(charge_samples)
    result = {
        "label": args.label,
        "device": args.serial,
        "capacity_mah": args.capacity_mah,
        "samples": samples,
        "estimate": estimate_value,
        "charge_samples": charge_samples,
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
