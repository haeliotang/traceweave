#!/usr/bin/env python3
"""Run the two-install sync scenario on one device.

Node A (actionsvalidation) serves one session on loopback; node B
(documentsvalidation) dials. Exercises convergence, concurrent edits,
user resolution and deletion propagation. Synthetic data only.
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NODE_A = 'org.traceform.agentos.actionsvalidation'
NODE_B = 'org.traceform.agentos.documentsvalidation'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--node-a-apk', type=Path, required=True)
    parser.add_argument('--node-b-apk', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    out = args.output_dir
    out.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, '-s', args.serial]

    def run(*arguments, **kwargs):
        return subprocess.run(adb + list(arguments), capture_output=True, text=True, **kwargs)

    def case(package, name, tag):
        target = out / f'{tag}.json'
        result = subprocess.run([sys.executable, str(ROOT / 'agent_os/scripts/validate_actions_android.py'),
                                 '--adb', args.adb, '--serial', args.serial, '--package', package,
                                 '--case', name, '--output', str(target)], cwd=ROOT,
                                capture_output=True, text=True)
        if result.returncode != 0:
            raise RuntimeError(f'{name} failed on {package}:\n{result.stdout}\n{result.stderr}')
        return target

    def serve_and_exchange(tag):
        server = subprocess.Popen([sys.executable, str(ROOT / 'agent_os/scripts/validate_actions_android.py'),
                                   '--adb', args.adb, '--serial', args.serial, '--package', NODE_A,
                                   '--case', 'SYNC_NODE_SERVE', '--output', str(out / f'{tag}-server.json')],
                                  cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        time.sleep(4)
        case(NODE_B, 'SYNC_NODE_EXCHANGE', f'{tag}-client')
        server.wait(timeout=90)
        if server.returncode != 0:
            raise RuntimeError(f'{tag} server failed: {server.communicate()}')

    for apk, package in ((args.node_a_apk, NODE_A), (args.node_b_apk, NODE_B)):
        run('install', '-r', str(apk), check=True)
        run('shell', 'pm', 'clear', package, check=True)
    case(NODE_A, 'SYNC_RECORD_ROUNDTRIP', 'recovery')
    case(NODE_B, 'SYNC_DIAL_UNREACHABLE', 'retry-fail')
    serve_and_exchange('first')
    case(NODE_A, 'SYNC_EDIT_A', 'edit-a')
    case(NODE_B, 'SYNC_EDIT_B', 'edit-b')
    serve_and_exchange('second')
    case(NODE_A, 'SYNC_VERIFY_CONFLICT', 'conflict-a')
    case(NODE_B, 'SYNC_VERIFY_CONFLICT', 'conflict-b')
    case(NODE_A, 'SYNC_RESOLVE_B_EDIT', 'resolve')
    serve_and_exchange('third')
    case(NODE_A, 'SYNC_VERIFY_RESOLVED', 'resolved-a')
    case(NODE_B, 'SYNC_VERIFY_RESOLVED', 'resolved-b')
    case(NODE_A, 'SYNC_DELETE_OWN', 'delete-own')
    serve_and_exchange('fourth')
    case(NODE_A, 'SYNC_VERIFY_AFTER_DELETE', 'deleted-a')
    case(NODE_B, 'SYNC_VERIFY_AFTER_DELETE', 'deleted-b')
    cases = []
    for path in sorted(out.glob('*.json')):
        cases.extend(json.loads(path.read_text()).get('cases', []))
    receipt = {
        'device_serial': args.serial,
        'synthetic_only': True,
        'cases': cases,
        'all_passed': all(item['passed'] for item in cases),
        'scenarios': ['record_roundtrip', 'retry_after_failure', 'convergence', 'concurrent_conflict',
                      'user_resolution', 'deletion_propagation'],
    }
    (out / 'e2e.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('sync e2e:', 'PASS' if receipt['all_passed'] else 'FAIL', len(cases), 'cases')
    if not receipt['all_passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
