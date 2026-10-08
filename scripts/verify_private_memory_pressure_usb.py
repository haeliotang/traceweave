#!/usr/bin/env python3
"""Run the installed isolated bounded pressure fixture; never starts a collector."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--probe-build', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    adb = [str(args.adb), '-s', args.serial]
    package = 'org.traceform.edge.synthetic'

    def shell(command):
        return subprocess.run(adb + ['shell', shlex.join(command)], capture_output=True,
                              check=True, timeout=15).stdout

    # Use the device clock, so stale receipts cannot pass if host clocks differ.
    started = int(shell(['date', '+%s'])) * 1000
    shell(['am', 'set-standby-bucket', package, 'active'])
    shell(['am', 'broadcast', '-n', package + '/org.traceform.edge.SyntheticMemoryPressureReceiver'])
    shell(['cmd', 'jobscheduler', 'run', '-f', package, '34718'])
    deadline = time.monotonic() + 165
    while time.monotonic() < deadline:
        result = subprocess.run(adb + ['shell', shlex.join(['run-as', package, 'cat', 'files/memory_pressure.json'])],
                                capture_output=True, timeout=15)
        if result.returncode == 0:
            report = json.loads(result.stdout)
            if report.get('completed_epoch_ms', 0) >= started:
                build = json.loads((args.probe_build / 'build_report.json').read_text())
                root = Path(__file__).resolve().parents[1]
                report['build'] = build
                report['pressure_source_sha256'] = hashlib.sha256(
                    (root / 'native/private_alignment_probe/memory_pressure_probe.c').read_bytes()).hexdigest()
                report['pressure_limits'] = {'chunk_mib': 32, 'maximum_mib': 768,
                                             'available_reserve_mib': 256, 'child_timeout_seconds': 120,
                                             'child_oom_score_adj': 1000}
                args.output.write_text(json.dumps(report, indent=2) + '\n')
                print(json.dumps({key: report.get(key) for key in ('passed',
                    'real_pressure_admission_rejected', 'readmitted_after_release',
                    'running_low_memory_withdrawal_verified')}))
                if not report.get('passed'):
                    raise SystemExit(1)
                return
        time.sleep(1)
    raise TimeoutError('bounded pressure fixture did not publish a fresh terminal receipt')


if __name__ == '__main__':
    main()
