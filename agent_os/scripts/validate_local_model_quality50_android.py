#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Run the 50-case synthetic quality probe on an installed isolated package."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--model', default='qwen15')
    parser.add_argument('--timeout', type=int, default=1800)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]

    def run(*arguments):
        return subprocess.run(adb + list(arguments), capture_output=True, text=True, check=True).stdout

    run('shell', 'am', 'force-stop', args.package)
    run('logcat', '-c')
    run('shell', 'am', 'start', '-W', '-a', 'QUALITY50', '--es', 'model', args.model, '-n',
        args.package + '/com.inceptless.traceweave.LocalModelQualityProbe')
    deadline = time.time() + args.timeout
    done = False
    while time.time() < deadline:
        logs = run('logcat', '-d', '-s', 'AgentOsModelQuality:I', '*:S')
        if 'QUALITY50_DONE' in logs:
            done = True
            break
        time.sleep(5)
    if not done:
        raise SystemExit('quality probe did not finish before the timeout')
    raw = subprocess.run(adb + ['exec-out', 'run-as', args.package, 'cat',
                                'files/quality50.json'], capture_output=True, check=True).stdout
    payload = json.loads(raw.decode('utf-8'))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2) + '\n')
    print('quality50:', payload['passed'], '/', payload['total'], payload['by_category'])


if __name__ == '__main__':
    main()
