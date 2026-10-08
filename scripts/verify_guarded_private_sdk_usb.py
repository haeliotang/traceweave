#!/usr/bin/env python3
"""Isolated synthetic guarded MPC, CPU abort and discovered-proof USB validation.

Requires the rebuilt probe installed in org.traceform.edge.synthetic. All keys
and witnesses are fake and stay in the ignored runtime directory. Never uses
the primary collector, enrolled owners, or device models.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import sys
import threading
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from native.private_alignment_probe.verify_uniform_permutation import encode, fixtures, packed_uniform_message
from scripts.preflight_private_computation_fixture import APPROVED_CIRCUIT, approval_bits
from scripts.verify_uniform_private_sdk_usb import statement_bytes
from traceform.dynamics.integer_transport import optimize_quantized_uniform_permutation

PACKAGE = 'org.traceform.edge.synthetic'
ROOT = 'files/private_uniform16'


def prepare_case(label, index, run_tag=''):
    source_label = 'different_scale' if label == 'changed_valid_model' or label.startswith(('changed_approval_', 'cpu_')) else label
    witness = dict(fixtures(16, quantized=True, search_distance_shift=14))[source_label]
    session = hashlib.sha256(('traceform.synthetic.guarded.v1/' + run_tag + '/' + str(index)).encode()).digest()
    bits, expected, valid, objective = encode(witness, source_label, quantized=True, packed=True,
                                            search_distance_shift=14, session=session)
    bindings = bytearray(192)
    for block, value in enumerate([session, *[
            hashlib.sha256(packed_uniform_message(owner, witness[field],
                (bytes([session[0] ^ 1]) + session[1:] if source_label == 'session_mismatch' and owner == 1 else session),
                bytes([11 if owner == 0 else 13] * 32))).digest()
            for owner, field in ((0, 'first'), (1, 'second'))]]):
        bindings[block * 64:block * 64 + 31] = value[:31]
        bindings[block * 64 + 32] = value[31]
    own, peer = bits[6224:], bits[:6224]
    if label == 'changed_valid_model':
        changed = dict(witness, first=witness['second'])
        changed_bits, _, _, _ = encode(changed, source_label, quantized=True, packed=True,
                                      search_distance_shift=14, session=session)
        peer = changed_bits[:6224]
        valid = False
    if label.startswith('changed_approval_'):
        block = int(label.rsplit('_', 1)[1])
        bindings[block * 64] ^= 1
        valid = False
    if source_label == 'changed_nonce':
        valid = False  # Owner approves nonce 13; owner 1 actually supplies nonce 12.
    if not valid:
        expected, objective = '0' * 849, 0
    return {'case': label, 'witness': witness, 'session': session, 'own': own, 'peer': peer,
            'bindings': bytes(bindings), 'expected': expected, 'valid': valid, 'objective': objective}


def synthetic_witness(case, discovered):
    if len(discovered) != 224 or discovered != statement_bytes(case['expected'], True):
        raise ValueError('actual discovered statement differs from independent reference')
    result = optimize_quantized_uniform_permutation(**case['witness'], search_distance_shift=14)
    payload = bytearray(b'TFGW16V2')
    for matrix in (case['witness']['first'], case['witness']['second'], result['coupling']):
        for row in matrix:
            for value in row:
                payload.extend(struct.pack('<I', value))
    payload.extend(case['session'])
    payload.extend(bytes([11] * 32) + bytes([13] * 32))
    payload.extend(discovered)
    return bytes(payload)


def wait_terminal(adb, job):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        result = subprocess.run(adb + ['shell', shlex.join(['cmd', 'jobscheduler', 'get-job-state', PACKAGE, str(job)])],
                                capture_output=True, timeout=10)
        state = (result.stdout + result.stderr).decode()
        if 'unknown(' in state or 'Could not find job' in state:
            return
        if result.returncode != 0:
            raise RuntimeError('job state query failed: ' + state)
        time.sleep(.2)
    raise RuntimeError('job did not become terminal')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--openssl', type=Path, required=True)
    parser.add_argument('--prover', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    base = args.runtime.resolve()
    identities = base / 'fixture'
    circuit = base / 'guarded.txt'
    if hashlib.sha256(circuit.read_bytes()).hexdigest() != APPROVED_CIRCUIT:
        raise ValueError('guarded circuit pin mismatch')
    adb = [str(args.adb), '-s', args.serial]
    report = {'passed': False, 'circuit_sha256': APPROVED_CIRCUIT, 'cases': [], 'proof_cases': [],
              'run_tag': os.urandom(16).hex(),
              'scope': 'synthetic_sixteen_node_two_process_MPC_and_centralized_proof_not_distributed_private_proving'}

    def save():
        args.output.write_text(json.dumps(report, indent=2) + '\n')

    def shell(command, data=None, timeout=120):
        return subprocess.run(adb + ['shell', shlex.join(command)], input=data, capture_output=True,
                              check=True, timeout=timeout).stdout

    def upload(name, data, root=ROOT):
        shell(['run-as', PACKAGE, 'sh', '-c', 'cat > ' + root + '/' + name], data)

    def receipt(name, nonce):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            result = subprocess.run(adb + ['shell', shlex.join(['run-as', PACKAGE, 'cat', name])], capture_output=True, timeout=10)
            if result.returncode == 0:
                candidate = json.loads(result.stdout)
                if candidate.get('nonce') == nonce:
                    return candidate
            time.sleep(.2)
        raise RuntimeError('matching device receipt unavailable')

    def sign(data, owner):
        material = base / 'approval-message.bin'; material.write_bytes(data)
        return subprocess.run([str(args.openssl), 'dgst', '-sha256', '-sign', str(identities / f'owner{owner}.key'),
                               str(material)], capture_output=True, check=True).stdout

    def cpu_process():
        processes = shell(['ps', '-A', '-o', 'PID,ARGS']).decode()
        for line in processes.splitlines():
            if 'libtraceform_private_participant.so' in line and 'private-circuit-' in line:
                pid = int(line.split()[0])
                stat = shell(['run-as', PACKAGE, 'cat', f'/proc/{pid}/stat']).decode().rpartition(')')[2].split()
                return pid, int(stat[11]) + int(stat[12])
        return None

    def run_peer(case, lifecycle):
        command = [str(base / 'host_phase_participant'), '2', '24885', str(circuit),
                   str(identities / '2.pem'), str(identities / '2.key'), str(identities / '1.pem'),
                   (identities / '1.pin').read_text(), (case['bindings'][:31] + case['bindings'][32:33]).hex()]
        data = (case['peer'] + approval_bits(case['bindings']) + '\n').encode()
        deadline = time.monotonic() + 180
        boot_deadline = time.monotonic() + 60
        while True:
            phase = threading.Event(); stdout, stderr = [], []
            child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            def drain(stream, output):
                for line in iter(stream.readline, b''):
                    output.append(line)
                    if b'TRACEFORM_MPC_PHASE=independent' in line:
                        phase.set()
            readers = [threading.Thread(target=drain, args=(child.stdout, stdout)),
                       threading.Thread(target=drain, args=(child.stderr, stderr))]
            for reader in readers: reader.start()
            observation = None
            try:
                child.stdin.write(data); child.stdin.close()
                while child.poll() is None and time.monotonic() < deadline:
                    if lifecycle and phase.is_set():
                        baseline = cpu_process()
                        if baseline is None:
                            raise RuntimeError('MPC phase without Android child')
                        cpu_deadline = time.monotonic() + 10
                        while time.monotonic() < cpu_deadline:
                            current = cpu_process()
                            if current and current[0] == baseline[0] and current[1] - baseline[1] >= 50:
                                observation = {'authenticated_host_phase': 'independent', 'android_child_pid': current[0],
                                               'cpu_ticks_after_phase': current[1] - baseline[1]}
                                upload('withdraw.signal', b'withdraw\n')
                                break
                            time.sleep(.1)
                        if observation is None:
                            raise RuntimeError('Android CPU phase not observed before withdrawal')
                        child.wait(timeout=15)
                        break
                    time.sleep(.05)
                if child.poll() is None:
                    raise RuntimeError('peer exceeded bounded runtime')
            finally:
                if child.poll() is None: child.kill()
                child.wait(timeout=5)
                for reader in readers: reader.join(timeout=5)
            out, err = b''.join(stdout), b''.join(stderr)
            transient = child.returncode == 1 and out == b'connected\n' and b'unexpected eof while reading' in err and not phase.is_set()
            if transient and time.monotonic() < boot_deadline:
                continue
            return {'exit_code': child.returncode, 'stdout': out.decode(), 'stderr': err.decode()}, observation

    shell(['run-as', PACKAGE, 'mkdir', '-p', ROOT, 'files/proof_v2'])
    for name in ('1.pem', '1.key', '2.pem', '2.pin', 'owner0.der', 'owner1.der'):
        upload(name, (identities / name).read_bytes())
    upload('circuit.txt', circuit.read_bytes())
    if shell(['run-as', PACKAGE, 'sha256sum', ROOT + '/circuit.txt']).decode().split()[0] != APPROVED_CIRCUIT:
        raise ValueError('device guarded circuit differs')
    subprocess.run(adb + ['forward', 'tcp:24885', 'tcp:24885'], check=True)
    save()
    try:
        # Fresh sessions for every case; no deletion or resetting of reservations.
        labels = ['identical', 'transposition', 'reversal', 'nonzero_diagonal', 'asymmetric',
                  'nonuniform_same_total', 'negative_distance', 'out_of_range_distance', 'triangle_violation',
                  'wrong_total', 'session_mismatch', 'changed_nonce', 'changed_valid_model',
                  'changed_approval_0', 'changed_approval_1', 'changed_approval_2',
                  'cpu_cancel', 'cpu_withdraw', 'large_80bit_objective', 'different_scale']
        for index, label in enumerate(labels):
            case = prepare_case(label, index, report['run_tag'])
            lifecycle = label.removeprefix('cpu_') if label.startswith('cpu_') else ''
            upload('own.bits', case['own'].encode()); upload('bindings.bin', case['bindings'])
            for owner in range(2):
                upload(f'owner{owner}.sig', sign(b'traceform.gw.compute-approval.v2\0' + bytes([owner])
                    + APPROVED_CIRCUIT.encode() + case['bindings'], owner))
            upload('fixture.json', json.dumps({'case': label, 'expected_valid': case['valid'],
                                               'approved_computation': True, 'lifecycle': lifecycle}).encode())
            shell(['run-as', PACKAGE, 'rm', '-f', ROOT + '/withdraw.signal'])
            shell(['am', 'broadcast', '-n', PACKAGE + '/org.traceform.edge.SyntheticPrivateAlignmentReceiver'])
            dispatch = json.loads(shell(['run-as', PACKAGE, 'cat', 'files/private_dispatch.json']))
            started = time.monotonic()
            peer, observation = run_peer(case, lifecycle)
            device = receipt('files/private_uniform16.json', dispatch['nonce']); wait_terminal(adb, 34716)
            outputs = [line[14:] for line in peer['stdout'].splitlines() if line.startswith('PUBLIC_OUTPUT=')]
            passed = device.get('passed') is True and (observation is not None and peer['exit_code'] != 0
                if lifecycle else peer['exit_code'] == 0 and outputs == [case['expected']]
                and (device.get('output') == case['expected'] if case['valid'] else device.get('witness_rejected') is True))
            cache = shell(['run-as', PACKAGE, 'ls', 'cache']).decode()
            passed = passed and 'private-circuit-' not in cache
            record = {'case': label, 'passed': passed, 'elapsed_seconds': time.monotonic() - started,
                      'peer': peer, 'device': device, 'cpu_observation': observation, 'snapshot_removed': 'private-circuit-' not in cache}
            report['cases'].append(record); save()
            print(json.dumps({'case': label, 'passed': passed}), flush=True)
            if not passed: raise RuntimeError('guarded case failed: ' + label)

        # Use bytes exported by the actual last Android computation, never substitute expected bytes.
        discovered = shell(['run-as', PACKAGE, 'cat', ROOT + '/discovered_statement.bin'])
        report['discovered_statement_sha256'] = hashlib.sha256(discovered).hexdigest()
        witness_path = base / 'discovered-synthetic-witness.bin'
        witness_path.write_bytes(synthetic_witness(case, discovered)); witness_path.chmod(0o600)
        public = base / ('discovered-proof-' + str(time.time_ns()))
        proof = subprocess.run([str(args.prover.resolve()), str(witness_path), str(public)], capture_output=True, timeout=1800)
        report['centralized_prover'] = {'exit_code': proof.returncode, 'stderr': proof.stderr.decode(), 'stdout': proof.stdout.decode()}; save()
        if proof.returncode != 0: raise RuntimeError('centralized synthetic proof failed')
        if (public / 'public_inputs.bin').read_bytes() != discovered: raise RuntimeError('prover changed discovered statement')
        key_hash = hashlib.sha256((public / 'verifying_key.bin').read_bytes()).hexdigest()
        original_proof = (public / 'proof.bin').read_bytes()
        for name in ('verifying_key.bin', 'owner0.der', 'owner1.der'):
            upload(name, (public / name if name == 'verifying_key.bin' else identities / name).read_bytes(), 'files/proof_v2')
        for label in ('bad_owner', 'bad_binding', 'bad_proof', 'valid', 'replay'):
            statement = bytearray(discovered); proof_bytes = bytearray(original_proof)
            if label == 'bad_binding': statement[0] ^= 1
            if label == 'bad_proof': proof_bytes[:48] = original_proof[144:192]
            upload('public_inputs.bin', statement, 'files/proof_v2'); upload('proof.bin', proof_bytes, 'files/proof_v2')
            for owner in range(2):
                signature = bytearray(sign(b'traceform.gw.owner-approval.v1\0' + bytes([owner])
                    + key_hash.encode() + statement + proof_bytes, owner))
                if label == 'bad_owner' and owner == 1: signature[-1] ^= 1
                upload(f'owner{owner}.sig', signature, 'files/proof_v2')
            upload('fixture.json', json.dumps({'case': label, 'trusted_key_hash': key_hash,
                                               'bind_discovered': True}).encode(), 'files/proof_v2')
            shell(['am', 'broadcast', '-n', PACKAGE + '/org.traceform.edge.SyntheticProofReceiver'])
            dispatch = json.loads(shell(['run-as', PACKAGE, 'cat', 'files/proof_dispatch.json']))
            device = receipt('files/proof_v2.json', dispatch['nonce']); wait_terminal(adb, 34717)
            report['proof_cases'].append(device); save()
            print(json.dumps({'proof_case': label, 'passed': device.get('passed')}), flush=True)
            if device.get('passed') is not True: raise RuntimeError('proof case failed: ' + label)
        report['passed'] = True; save()
    finally:
        subprocess.run(adb + ['forward', '--remove', 'tcp:24885'], check=True)


if __name__ == '__main__':
    main()
