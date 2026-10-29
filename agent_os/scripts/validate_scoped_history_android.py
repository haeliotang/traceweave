#!/usr/bin/env python3
"""Validate non-empty synthetic Edge history in isolated debug applications only."""
import argparse
import json
from pathlib import Path
import shlex
import sqlite3
import subprocess
import sys
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'agent_os/scripts'))
from verify_release_android import apk_identity

EDGE = 'org.traceform.edge.validation'
PROBE = 'org.traceform.agentos.actionsvalidation'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('adb', 'serial', 'edge-apk', 'probe-apk', 'build-tools'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    for path, package in ((args.edge_apk, EDGE), (args.probe_apk, PROBE)):
        identity = apk_identity(Path(path), Path(args.build_tools))
        if identity['package'] != package or not identity['debuggable']:
            raise RuntimeError('Only the expected isolated debug packages are supported')
    adb = [args.adb, '-s', args.serial]

    def run(*arguments, **kwargs):
        return subprocess.run(adb + list(arguments), check=True, **kwargs)

    packages = run('shell', 'pm', 'list', 'packages', capture_output=True, text=True).stdout.splitlines()
    if any('package:' + package in packages for package in (EDGE, PROBE)):
        raise RuntimeError('Existing validation applications must be preserved')
    installed = []
    try:
        for package, apk in ((EDGE, args.edge_apk), (PROBE, args.probe_apk)):
            run('install', apk, stdout=subprocess.DEVNULL)
            installed.append(package)
        now = int(run('shell', 'date', '+%s', capture_output=True, text=True).stdout.strip()) * 1000
        with zipfile.ZipFile(args.edge_apk) as archive:
            binding = json.loads(archive.read('assets/model_binding.json'))
            nodes = json.loads(archive.read('assets/manifold.json'))['nodes']
            tokens = [node['state_token'] for node in nodes]
        if not tokens:
            raise RuntimeError('Fixture model has no tokens')
        with tempfile.TemporaryDirectory(prefix='traceform-edge-synthetic-') as directory:
            path = Path(directory) / 'synthetic.sqlite'
            with sqlite3.connect(path) as db:
                db.execute('CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)')
                for index in range(6):
                    instant = (now // 30000 - 8 + index) * 30000
                    frame = {'timestamp_epoch_ms': instant, 'state_token': tokens[index % len(tokens)], **binding,
                             'source_mode': 'synthetic_fixture', 'session_id': 'synthetic_session',
                             'continuous_from_previous': index > 0}
                    db.execute('INSERT INTO warm_trajectory VALUES (?,?,?,1)',
                               (instant // 30000, instant, json.dumps(frame)))
            command = shlex.join(['run-as', EDGE, 'sh', '-c', 'mkdir -p databases && cat > databases/edge.sqlite'])
            with path.open('rb') as payload:
                run('shell', command, stdin=payload)
        run('shell', 'am', 'start', '-W', '-S', '-a', 'AVAILABLE_HISTORY', '-n',
            PROBE + '/org.traceform.agentos.ScopedTraceformProbe', stdout=subprocess.DEVNULL)
        pid = None
        for _ in range(20):
            result = subprocess.run(adb + ['shell', 'pidof', PROBE], capture_output=True, text=True)
            if result.returncode == 0:
                pid = result.stdout.strip().split()[0]
                break
            time.sleep(.25)
        if not pid:
            raise RuntimeError('Validation process never started')
        for _ in range(60):
            logs = run('logcat', '-d', '--pid=' + pid, '-s', 'AgentOsScopedProbe:I', '*:S',
                       capture_output=True, text=True).stdout
            lines = [line for line in logs.splitlines() if 'PASS' in line or 'FAIL' in line]
            if lines:
                if 'PASS authorized_synthetic_history' not in lines[-1]:
                    raise RuntimeError('Isolated service probe failed: ' + lines[-1])
                print('PASS non-empty synthetic history through scoped Binder and Agent OS summary validation')
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Timed out waiting for summary probe')
    finally:
        for package in reversed(installed):
            run('uninstall', package, stdout=subprocess.DEVNULL)
        print('Isolated applications and synthetic history removed')


if __name__ == '__main__':
    main()
