#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Run the long-context, multi-turn, concurrent and cancel stress probe."""
import argparse
import json
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--model', default='qwen15')
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]

    def run(*arguments):
        return subprocess.run(adb + list(arguments), capture_output=True, text=True, check=True).stdout

    phases = ['long_context', 'boundary', 'multi_turn', 'concurrent', 'cancel']
    report = {}
    for phase in phases:
        run('shell', 'am', 'force-stop', args.package)
        run('logcat', '-c')
        run('shell', 'am', 'start', '-W', '-a', 'STRESS3', '--es', 'model', args.model, '--es', 'phase', phase,
            '-n', args.package + '/com.inceptless.traceweave.LocalModelQualityProbe')
        deadline = time.time() + args.timeout
        finished = False
        while time.time() < deadline:
            if 'D3_DONE ' + phase in run('logcat', '-d', '-s', 'AgentOsModelQuality:I', '*:S'):
                finished = True
                break
            time.sleep(5)
        if not finished:
            report[phase] = {'phase': phase, 'error': 'timeout'}
            continue
        raw = subprocess.run(adb + ['exec-out', 'run-as', args.package, 'cat', 'files/d3-' + phase + '.json'],
                             capture_output=True, check=True).stdout
        report[phase] = json.loads(raw.decode('utf-8'))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    for phase in phases:
        value = report.get(phase, {})
        print(phase, {k: v for k, v in value.items() if k not in ('phase', 'output')})


if __name__ == '__main__':
    main()
