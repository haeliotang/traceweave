#!/usr/bin/env python3
"""Check that the TLS session is the session released by the bound GW circuit."""
import argparse
import hashlib
import json
from pathlib import Path
import ssl
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('binary', 'identities', 'circuit', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    session = bytes(range(32))
    bits = ''.join(format(v, '021b')[::-1] for v in [0, 0, 0, 0, 500000, 500000])
    bits += ''.join(format(v, '08b')[::-1] for v in session + bytes([11] * 32))
    receipts = []
    for case in ('matching', 'mismatching', 'invalid_masked'):
        children = []
        try:
            transport_session = session if case == 'matching' else bytes([session[0] ^ 1]) + session[1:]
            for role in (1, 2):
                root, peer = args.identities, 3 - role
                pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((root / f'{peer}.pem').read_text())).hexdigest()
                child = subprocess.Popen([str(args.binary), str(role), '23813', str(args.circuit),
                    str(root / f'{role}.pem'), str(root / f'{role}.key'), str(root / f'{peer}.pem'),
                    pin, transport_session.hex()], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                children.append(child)
                private_bits = '0' * 126 + bits[126:] if case == 'invalid_masked' else bits
                child.stdin.write((private_bits + '\n').encode()); child.stdin.close(); child.stdin = None
                if role == 1: time.sleep(.1)
            parties = []
            for child in children:
                stdout, stderr = child.communicate(timeout=60)
                outputs = [line for line in stdout.decode().splitlines() if line.startswith('PUBLIC_OUTPUT=')]
                if case == 'matching':
                    expected_session = ''.join(format(v, '08b')[::-1] for v in session)
                    # PUBLIC_OUTPUT= is 14 bytes; session starts after validity + objective.
                    passed = child.returncode == 0 and len(outputs) == 1 and outputs[0][14 + 81:14 + 337] == expected_session
                elif case == 'invalid_masked':
                    passed = child.returncode == 0 and outputs == ['PUBLIC_OUTPUT=' + '0' * 849]
                else:
                    passed = child.returncode != 0 and not outputs and b'transport statement session rejected' in stderr
                parties.append({'passed': passed, 'exit_code': child.returncode, 'stderr': stderr.decode()})
            receipts.append({'case': case, 'parties': parties})
            assert all(p['passed'] for p in parties), (case, parties)
        finally:
            for child in children:
                if child.poll() is None: child.kill(); child.communicate()
    args.output.write_text(json.dumps({'passed': True, 'cases': receipts,
        'binary_sha256': hashlib.sha256(args.binary.read_bytes()).hexdigest(),
        'scope': 'synthetic_tls_to_public_statement_session_binding'}, indent=2) + '\n')


if __name__ == '__main__':
    main()
