#!/usr/bin/env python3
"""Measure a controlled idle baseline and a synthetic workload on one device.

Requires the device to be unplugged, awake and unlocked. Refuses to run while
charging, because charging masks battery drain. Synthetic workload only.
"""
import argparse
import json
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--baseline-minutes', type=float, default=5.0)
    parser.add_argument('--workload-minutes', type=float, default=10.0)
    parser.add_argument('--package', action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]

    def shell(*arguments):
        return subprocess.run(adb + ['shell'] + list(arguments), capture_output=True, text=True, check=True).stdout

    battery = shell('dumpsys', 'battery')
    if 'AC powered: true' in battery or 'USB powered: true' in battery:
        raise SystemExit('Unplug the device before measuring battery drain')
    if 'level:' not in battery:
        raise SystemExit('Battery level is unavailable')

    def level():
        match = re.search(r'level: (\d+)', shell('dumpsys', 'battery'))
        return int(match.group(1)) if match else -1

    def temperature():
        match = re.search(r'temperature: (\d+)', shell('dumpsys', 'battery'))
        return int(match.group(1)) if match else -1

    def thermal():
        output = shell('dumpsys', 'thermalservice')
        match = re.search(r'Thermal Status: (\w+)', output)
        return match.group(1) if match else 'unknown'

    def sample():
        return {'battery_level': level(), 'battery_temperature_tenths_c': temperature(),
                'thermal_status': thermal(), 'recorded_at': datetime.now(timezone.utc).isoformat()}

    shell('dumpsys', 'batterystats', '--reset')
    start = sample()
    time.sleep(args.baseline_minutes * 60)
    after_baseline = sample()
    for package in args.package:
        shell('am', 'start', '-W', '-n', package + '/org.traceform.agentos.LocalModelQualityProbe',
              '-a', 'STRESS3', '--es', 'phase', 'multi_turn', '--es', 'model', 'qwen15')
    time.sleep(args.workload_minutes * 60)
    end = sample()
    stats = shell('dumpsys', 'batterystats', '--charged')
    uids = [line.strip() for line in stats.splitlines() if 'org.traceform' in line or 'Estimated power use' in line]
    receipt = {
        'device_serial': args.serial,
        'synthetic_workload_only': True,
        'baseline_minutes': args.baseline_minutes,
        'workload_minutes': args.workload_minutes,
        'samples': {'start': start, 'after_baseline': after_baseline, 'end': end},
        'baseline_drain_percent_per_hour': round((start['battery_level'] - after_baseline['battery_level'])
                                                 / max(args.baseline_minutes, 1) * 60, 2),
        'workload_drain_percent_per_hour': round((after_baseline['battery_level'] - end['battery_level'])
                                                 / max(args.workload_minutes, 1) * 60, 2),
        'temperature_delta_tenths_c': end['battery_temperature_tenths_c'] - start['battery_temperature_tenths_c'],
        'batterystats_excerpt': uids[:40],
        'limitations': [
            'System battery statistics only; not an external power meter',
            'No controlled cooling, fixed ambient temperature or flight mode',
            'One run per configuration; background activity is not fully controlled',
            'Drain percentages are coarse because the battery level is reported in whole percent',
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, indent=2) + '\n')
    print('baseline %/h:', receipt['baseline_drain_percent_per_hour'],
          'workload %/h:', receipt['workload_drain_percent_per_hour'],
          'temp delta:', receipt['temperature_delta_tenths_c'])


if __name__ == '__main__':
    main()
