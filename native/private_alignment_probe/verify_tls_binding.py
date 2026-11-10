#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Check synthetic TLS channel session/circuit binding before MPC setup."""
import argparse
import hashlib
import json
from pathlib import Path
import ssl
import socket
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('binary', 'identities', 'circuit', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    receipts = []
    with tempfile.TemporaryDirectory(prefix='traceform-tls-binding-') as directory:
        changed = Path(directory) / 'different-circuit.txt'
        source = args.circuit.read_text()
        if ' AND' not in source:
            raise ValueError('synthetic AND circuit required')
        changed.write_text(source.replace(' AND', ' XOR', 1))
        for case in ('matching', 'session_mismatch', 'circuit_mismatch'):
            children = []
            try:
                for role in (1, 2):
                    peer = 3 - role
                    root = args.identities
                    pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((root / f'{peer}.pem').read_text())).hexdigest()
                    session = '22' * 32 if role == 2 and case == 'session_mismatch' else '11' * 32
                    circuit = changed if role == 2 and case == 'circuit_mismatch' else args.circuit
                    child = subprocess.Popen([str(args.binary), str(role), '23793', str(circuit),
                        str(root / f'{role}.pem'), str(root / f'{role}.key'), str(root / f'{peer}.pem'), pin, session],
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    children.append(child)
                    child.stdin.write(b'0000\n')
                    child.stdin.close()
                    child.stdin = None
                    if role == 1: time.sleep(.1)
                parties = []
                for child in children:
                    stdout, stderr = child.communicate(timeout=15)
                    passed = (child.returncode == 0 and b'PUBLIC_OUTPUT=000010' in stdout if case == 'matching'
                              else child.returncode != 0 and b'PUBLIC_OUTPUT=' not in stdout
                              and b'protocol channel binding rejected' in stderr)
                    parties.append({'passed': passed, 'exit_code': child.returncode, 'stderr': stderr.decode()})
                assert all(p['passed'] for p in parties), (case, parties)
                receipts.append({'case': case, 'passed': True, 'parties': parties})
            finally:
                for child in children:
                    if child.poll() is None:
                        child.kill()
                        child.communicate()
        # A correctly authenticated synthetic peer deliberately sends wrong
        # protocol metadata. TLS identity alone must not accept these frames.
        for case, offset, value in [('wrong_role', 8, 1), ('wrong_channel', 12, 1),
                                    ('legacy_version', 7, 1), ('unknown_version', 7, 3)]:
            root = args.identities
            pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((root / '2.pem').read_text())).hexdigest()
            child = subprocess.Popen([str(args.binary), '1', '23793', str(args.circuit),
                str(root / '1.pem'), str(root / '1.key'), str(root / '2.pem'), pin, '11' * 32],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                child.stdin.write(b'0000\n'); child.stdin.close(); child.stdin = None
                context = ssl.create_default_context(cafile=str(root / '1.pem'))
                context.check_hostname = False
                context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_3
                context.load_cert_chain(str(root / '2.pem'), str(root / '2.key'))
                deadline = time.monotonic() + 3
                while True:
                    try:
                        raw = socket.create_connection(('127.0.0.1', 23793), timeout=3)
                        break
                    except ConnectionRefusedError:
                        if time.monotonic() >= deadline: raise
                        time.sleep(.01)
                with context.wrap_socket(raw, server_hostname=None) as peer:
                    assert peer.recv(1) == b'\1'
                    peer.sendall(b'\1')
                    frame = bytearray()
                    while len(frame) < 77:
                        part = peer.recv(77 - len(frame))
                        if not part: raise ValueError('missing protocol frame')
                        frame.extend(part)
                    frame[8] = 2
                    frame[offset] = value
                    peer.sendall(frame)
                stdout, stderr = child.communicate(timeout=15)
                assert child.returncode != 0 and b'PUBLIC_OUTPUT=' not in stdout
                assert b'protocol channel binding rejected' in stderr
                receipts.append({'case': case, 'passed': True, 'authenticated_peer_metadata_rejected': True})
            finally:
                if child.poll() is None:
                    child.kill(); child.communicate()
    args.output.write_text(json.dumps({'passed': True, 'cases': receipts,
        'binary_sha256': hashlib.sha256(args.binary.read_bytes()).hexdigest(),
        'scope': 'synthetic_loopback_tls_session_and_circuit_binding_not_replay_policy'}, indent=2) + '\n')
    print(json.dumps({'passed': True, 'cases': len(receipts)}))


if __name__ == '__main__':
    main()
