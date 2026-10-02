#!/usr/bin/env python3
"""Check public outputs of three synthetic private-input circuit executions."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--circuit', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--port', type=int, default=23683)
    args = parser.parse_args()
    circuit_digest = hashlib.sha256(args.circuit.read_bytes()).hexdigest()
    if circuit_digest != '507a539f19c7e774da17b0dac687ae6b68ef9994c98fd041972a026a09432aef':
        raise ValueError('unexpected synthetic circuit')
    receipts = []
    for first, second, expected in [('0000', '0000', '000010'),
                                    ('1111', '1111', '111100'),
                                    ('1010', '1100', '100000')]:
        children = []
        try:
            for role, bits in [('1', first), ('2', second)]:
                children.append(subprocess.Popen([
                    str(args.binary), role, str(args.port), str(args.circuit),
                ], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE))
                children[-1].stdin.write((bits + '\n').encode())
                children[-1].stdin.close()
                children[-1].stdin = None
                if role == '1':
                    time.sleep(.1)
            parties = []
            for child in children:
                output, errors = child.communicate(timeout=30)
                lines = output.decode().splitlines()
                parties.append({'exit_code': child.returncode,
                                'output_matches': f'PUBLIC_OUTPUT={expected}' in lines,
                                'stderr': errors.decode()})
            receipts.append({'expected_public_output': expected, 'parties': parties})
        finally:
            for child in children:
                if child.poll() is None:
                    child.kill()
                    child.communicate()
    passed = all(party['exit_code'] == 0 and party['output_matches']
                 for receipt in receipts for party in receipt['parties'])
    report = {'passed': passed, 'synthetic_cases': receipts,
              'binary_sha256': hashlib.sha256(args.binary.read_bytes()).hexdigest(),
              'circuit_sha256': circuit_digest,
              'scope': 'fixed_backend_arm_host_compatibility_not_private_gw_or_security_audit'}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': passed, 'cases': len(receipts), 'report': str(args.output)}))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
