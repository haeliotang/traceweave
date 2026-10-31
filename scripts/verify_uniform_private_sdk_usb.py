#!/usr/bin/env python3
"""Run synthetic separate-party uniform16 cases through the isolated Android SDK.

Requires a preinstalled synthetic probe APK, fake TLS identities, a pinned
circuit and dedicated USB forwarding. No production data or real credentials.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'native/private_alignment_probe'))
from verify_uniform_permutation import fixtures, encode


PACKAGE = 'com.inceptless.traceweave.edge.synthetic'
ROOT = 'files/private_uniform16'


def statement_bytes(output, valid):
    statement = bytearray(224)
    for bit in range(80):
        if output[1 + bit] == '1':
            statement[bit // 8] |= 1 << (bit % 8)
    for binding in range(3):
        for value in range(32):
            offset = 32 + binding * 64 + (value if value < 31 else 32)
            for bit in range(8):
                if output[81 + binding * 256 + value * 8 + bit] == '1':
                    statement[offset] |= 1 << bit
    if not valid:
        # A known-rejected witness still needs the authenticated transport session
        # in the configured statement. It must not be treated as a valid result.
        statement[32:63] = bytes(range(31))
        statement[64] = 31
    return bytes(statement)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--circuit', type=Path, required=True)
    parser.add_argument('--identities', type=Path, required=True, help='Synthetic test identity directory only')
    parser.add_argument('--peer-launcher', type=Path, required=True)
    parser.add_argument('--usb-client', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    adb = [str(args.adb), '-s', args.serial]

    def shell(command, data=None, timeout=30):
        return subprocess.run(adb + ['shell', command], input=data, capture_output=True, check=True, timeout=timeout).stdout

    def upload(name, data):
        shell(shlex.join(['run-as', PACKAGE, 'sh', '-c', 'cat > ' + ROOT + '/' + name]), data, timeout=120)

    shell(shlex.join(['run-as', PACKAGE, 'mkdir', '-p', ROOT]))
    upload('circuit.txt', args.circuit.read_bytes())
    for name in ('1.pem', '1.key', '2.pem'):
        upload(name, (args.identities / name).read_bytes())
    import ssl
    upload('2.pin', hashlib.sha256(ssl.PEM_cert_to_DER_cert((args.identities / '2.pem').read_text())).hexdigest().encode())
    expected_hash = hashlib.sha256(args.circuit.read_bytes()).hexdigest()
    device_hash = shell(shlex.join(['run-as', PACKAGE, 'sha256sum', ROOT + '/circuit.txt'])).decode().split()[0]
    if device_hash != expected_hash:
        raise ValueError('device circuit digest mismatch')
    receipts = []
    report = {'passed': False, 'circuit_sha256': expected_hash, 'cases': receipts,
              'scope': 'synthetic_one_app_SDK_participant_separate_Mac_peer_not_owner_enrollment_or_proof_approval'}
    def save():
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    save()
    for label, witness in fixtures(16, quantized=True, search_distance_shift=14):
        bits, expected, valid, objective = encode(witness, label, quantized=True, packed=True, search_distance_shift=14)
        upload('own.bits', bits[6224:].encode())
        upload('public_inputs.bin', statement_bytes(expected, valid))
        upload('fixture.json', json.dumps({'case': label, 'expected_valid': valid}).encode())
        shell(shlex.join(['am', 'broadcast', '-n', PACKAGE + '/com.inceptless.traceweave.edge.SyntheticPrivateAlignmentReceiver']))
        dispatch = json.loads(shell(shlex.join(['run-as', PACKAGE, 'cat', 'files/private_dispatch.json'])))
        print(json.dumps({'case': label, 'nonce': dispatch['nonce'], 'state': 'dispatched'}), flush=True)
        environment = os.environ.copy()
        environment['TRACEFORM_TEST_SESSION'] = bytes(range(32)).hex()
        started = time.monotonic()
        child = subprocess.Popen([sys.executable, str(args.usb_client), str(args.peer_launcher), '2', '24885', str(args.circuit)],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment)
        receipt = {'case': label, 'expected_valid': valid, 'objective_numerator': str(objective), 'nonce': dispatch['nonce']}
        try:
            stdout, stderr = child.communicate((bits[:6224] + '\n').encode(), timeout=125)
            receipt['peer'] = {'exit_code': child.returncode, 'stdout': stdout.decode(), 'stderr': stderr.decode()}
            deadline = time.monotonic() + 10
            device = None
            while time.monotonic() < deadline:
                result = subprocess.run(adb + ['shell', shlex.join(['run-as', PACKAGE, 'cat', 'files/private_uniform16.json'])], capture_output=True, timeout=10)
                if result.returncode == 0:
                    candidate = json.loads(result.stdout)
                    if candidate.get('nonce') == dispatch['nonce']:
                        device = candidate
                        break
                time.sleep(.2)
            receipt['device'] = device
            outputs = [line for line in stdout.decode().splitlines() if line.startswith('PUBLIC_OUTPUT=')]
            receipt['passed'] = (child.returncode == 0 and outputs == ['PUBLIC_OUTPUT=' + expected]
                and device is not None and device.get('passed') is True and device.get('case') == label
                and (device.get('output') == expected if valid else device.get('witness_rejected') is True))
            terminal_deadline = time.monotonic() + 10
            while True:
                state = subprocess.run(adb + ['shell', 'cmd jobscheduler get-job-state ' + PACKAGE + ' 34716'],
                                       capture_output=True, timeout=10)
                state_text = (state.stdout + state.stderr).decode()
                if 'unknown(' in state_text or 'Could not find job' in state_text:
                    receipt['job_terminal'] = True
                    break
                if time.monotonic() >= terminal_deadline:
                    raise RuntimeError('completed receipt without terminal job state')
                time.sleep(.2)
            receipt['private_cache_listing'] = shell(shlex.join(['run-as', PACKAGE, 'ls', 'cache'])).decode()
            receipt['passed'] = receipt['passed'] and 'private-circuit-' not in receipt['private_cache_listing']
        except Exception as error:
            receipt.update({'passed': False, 'error': str(error)})
        finally:
            if child.poll() is None:
                child.kill()
                child.communicate()
        receipt['elapsed_seconds'] = time.monotonic() - started
        receipts.append(receipt)
        save()
        print(json.dumps({'case': label, 'passed': receipt['passed'], 'elapsed_seconds': receipt['elapsed_seconds']}), flush=True)
        if not receipt['passed']:
            return 1
    report['passed'] = True
    save()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
