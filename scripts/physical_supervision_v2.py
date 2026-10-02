#!/usr/bin/env python3
"""Control the separately versioned rolling acquisition supervisor."""

import argparse
import json
import math
from pathlib import Path
import subprocess
import sys

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))
from traceform.streaming.physical_supervision_v2 import PhysicalSupervisor
from pixel_physical.sensors import PRIMARY_SENSORS_ARG


def api_preflight():
    try:
        result = subprocess.run(['/data/data/com.termux/files/usr/bin/termux-battery-status'],
                                capture_output=True, text=True, timeout=5)
        payload = json.loads(result.stdout)
        ready = result.returncode == 0 and isinstance(payload, dict) and isinstance(payload.get('present'), bool)
        if not ready:
            return {'ready': False, 'reason': 'api_reply_invalid', 'scope': 'battery_ipc'}
        try:
            result = subprocess.run(['/data/data/com.termux/files/usr/bin/termux-sensor',
                                     '-n', '1', '-s', PRIMARY_SENSORS_ARG],
                                    capture_output=True, text=True, timeout=5)
            sensors = json.loads(result.stdout)
            def has_values(name, count):
                item = sensors.get(name) if isinstance(sensors, dict) else None
                values = item.get('values') if isinstance(item, dict) else None
                return (isinstance(values, list) and len(values) >= count
                        and all(isinstance(value, (int, float)) and not isinstance(value, bool)
                                and math.isfinite(value) for value in values[:count]))
            ready = (result.returncode == 0 and has_values('LSM6DSR Accelerometer', 3)
                     and any(has_values(name, 1) for name in
                             ['TMD3719 Ambient Light', 'ICP10101 Pressure Sensor', 'TMD3719 Proximity (wake-up)']))
            return {'ready': ready, 'reason': 'api_reply_valid' if ready else 'sensor_reply_invalid',
                    'scope': 'battery_and_motion_environment_startup_not_persistent_health'}
        finally:
            try:
                subprocess.run(['/data/data/com.termux/files/usr/bin/termux-sensor', '-c'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2)
            except (OSError, subprocess.TimeoutExpired):
                pass
    except (OSError, subprocess.TimeoutExpired, ValueError) as error:
        return {'ready': False, 'reason': type(error).__name__}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'stop', 'tick'])
    args = parser.parse_args()
    termux_home = Path('/data/data/com.termux/files/home')
    relative = PROJECT_ROOT.relative_to(termux_home)
    runner = str(Path('/root') / relative / 'scripts/physical_acquisition_v2.py')
    command = ['/data/data/com.termux/files/usr/bin/proot-distro', 'login', '--bind',
               str(termux_home) + ':/root', 'debian', '--', '/usr/bin/python3', '-u', runner]
    supervisor = PhysicalSupervisor(PROJECT_ROOT / 'data', termux_home / '.pixel_sensorium', command, runner,
                                    preflight=api_preflight)
    print(json.dumps(supervisor.transact(args.action)))


if __name__ == '__main__':
    main()
