#!/usr/bin/env python3
"""Real bounded memory pressure during authenticated synthetic MPC; no collector restart."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys
import threading
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.verify_guarded_private_sdk_usb import prepare_case, wait_terminal
from scripts.preflight_private_computation_fixture import APPROVED_CIRCUIT, approval_bits

PACKAGE = 'org.traceform.edge.synthetic'
ROOT = 'files/private_uniform16'


def verified_withdrawal(device):
    evidence = device.get('lifecycle', {})
    memory = evidence.get('withdrawal_memory') or {}
    return (device.get('passed') is True and evidence.get('passed') is True
        and evidence.get('mode') == 'pressure'
        and evidence.get('withdrawal_reason') == 'private alignment memory reserve exhausted'
        and all(evidence.get(field) is True for field in
                ('child_reaped', 'snapshot_removed', 'replay_rejected', 'pressure_child_reaped'))
        and any(sample.get('allocated_mib', 0) > 0 for sample in evidence.get('memory_samples', []))
        and (memory.get('low_memory') is True or
             0 < memory.get('available_bytes', float('inf')) < 192 * 1024 * 1024))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--openssl', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--resume-recovery', action='store_true',
                        help='Resume a recorded verified withdrawal without repeating memory pressure')
    args = parser.parse_args()
    base = args.runtime.resolve()
    adb = [str(args.adb), '-s', args.serial]
    identities, circuit = base / 'fixture', base / 'guarded.txt'
    if hashlib.sha256(circuit.read_bytes()).hexdigest() != APPROVED_CIRCUIT:
        raise ValueError('guarded circuit digest mismatch')
    report = {'passed': False, 'scope': 'real_running_resource_guard_test_not_system_LMK',
              'run_tag': uuid.uuid4().hex, 'pressure_limits': {'step_mib': 16, 'maximum_mib': 768,
                  'available_floor_mib': 208, 'child_timeout_seconds': 120, 'oom_score_adj': 1000}}

    if args.resume_recovery:
        report = json.loads(args.output.read_text())
        if not verified_withdrawal(report.get('device', {})) or not report.get('ledger_unchanged_after_restart'):
            raise ValueError('verified withdrawal and durable replay receipt required for recovery resume')
        if report.get('fresh_session_recovery', {}).get('passed') is True:
            raise ValueError('fresh-session recovery already completed')
        report['passed'] = False
        report['status'] = 'recovery_pending'

    def save():
        args.output.write_text(json.dumps(report, indent=2) + '\n')

    def shell(command, data=None):
        return subprocess.run(adb + ['shell', shlex.join(command)], input=data, capture_output=True,
                              check=True, timeout=30).stdout

    def upload(name, data):
        shell(['run-as', PACKAGE, 'sh', '-c', 'cat > ' + ROOT + '/' + name], data)

    def health():
        code = "import pathlib,sqlite3,time,json; r=pathlib.Path('/data/data/com.termux/files/home/traceform'); p=int((r/'data/edge_manifold_daemon.pid').read_text()); d=sqlite3.connect('file:'+str(r/'data/edge_storage.sqlite')+'?mode=ro',uri=True); t=d.execute('select max(ts) from hot').fetchone()[0]; print(json.dumps({'physical_pid':12155,'physical_alive':pathlib.Path('/proc/12155').exists(),'edge_pid':p,'edge_alive':pathlib.Path('/proc/'+str(p)).exists(),'latest_age_seconds':time.time()-t}))"
        return json.loads(shell(['run-as', 'com.termux', '/data/data/com.termux/files/usr/bin/python', '-c', code]))

    def cpu_process():
        for line in shell(['ps', '-A', '-o', 'PID,ARGS']).decode().splitlines():
            if 'libtraceform_private_participant.so' in line and 'private-circuit-' in line:
                pid = int(line.split()[0])
                stat = shell(['run-as', PACKAGE, 'cat', f'/proc/{pid}/stat']).decode().rpartition(')')[2].split()
                return pid, int(stat[11]) + int(stat[12])
        return None

    def receipt(path, nonce):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            result = subprocess.run(adb + ['shell', shlex.join(['run-as', PACKAGE, 'cat', path])],
                                    capture_output=True, timeout=10)
            if result.returncode == 0:
                candidate = json.loads(result.stdout)
                if candidate.get('nonce') == nonce:
                    return candidate
            time.sleep(.2)
        raise RuntimeError('matching terminal receipt missing')

    def run_peer(case, stage, pressure):
        nonlocal peer, readers
        command = [str(base / 'host_phase_participant'), '2', '24885', str(circuit),
                   str(identities / '2.pem'), str(identities / '2.key'), str(identities / '1.pem'),
                   (identities / '1.pin').read_text(), case['session'].hex()]
        deadline = time.monotonic() + 150
        boot_deadline = time.monotonic() + 60
        stage['health_during'] = []
        stage['transient_handshake_retries'] = 0
        while True:
            phase.clear(); output.clear(); errors.clear()
            peer = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            def drain(stream, target):
                for line in iter(stream.readline, b''):
                    target.append(line)
                    if b'TRACEFORM_MPC_PHASE=independent' in line: phase.set()
            readers = [threading.Thread(target=drain, args=(peer.stdout, output)),
                       threading.Thread(target=drain, args=(peer.stderr, errors))]
            for reader in readers: reader.start()
            peer.stdin.write((case['peer'] + approval_bits(case['bindings']) + '\n').encode()); peer.stdin.close()
            baseline = None; signaled = False; next_health = 0
            while peer.poll() is None and time.monotonic() < deadline:
                if pressure and phase.is_set() and not signaled:
                    current = cpu_process()
                    if current:
                        if baseline is None: baseline = current
                        if current[0] == baseline[0] and current[1] - baseline[1] >= 50:
                            stage['cpu_observation'] = {'authenticated_phase': 'independent', 'pid': current[0],
                                                         'cpu_ticks_after_phase': current[1] - baseline[1]}
                            upload('withdraw.signal', b'begin_real_pressure\n'); signaled = True
                if time.monotonic() >= next_health:
                    current_health = health(); stage['health_during'].append(current_health)
                    if (not current_health['physical_alive'] or not current_health['edge_alive']
                        or current_health['edge_pid'] != initial['edge_pid'] or current_health['latest_age_seconds'] > 120):
                        upload('pressure.abort', b'health_monitor\n')
                        raise RuntimeError('collector continuity guard aborted pressure')
                    next_health = time.monotonic() + 3
                time.sleep(.1)
            if peer.poll() is None:
                upload('pressure.abort', b'timeout\n'); raise TimeoutError('bounded MPC runtime exceeded')
            for reader in readers: reader.join(timeout=5)
            stage['peer'] = {'exit_code': peer.returncode, 'stdout': b''.join(output).decode(),
                              'stderr': b''.join(errors).decode()}
            transient = (peer.returncode == 1 and b''.join(output) == b'connected\n'
                and b'unexpected eof while reading' in b''.join(errors) and not phase.is_set())
            if transient and time.monotonic() < boot_deadline:
                stage['transient_handshake_retries'] += 1
                continue
            break
        return stage['peer'], signaled

    def approve_case(case, lifecycle):
        upload('own.bits', case['own'].encode()); upload('bindings.bin', case['bindings'])
        for owner in range(2):
            material = base / 'pressure-approval-message.bin'
            material.write_bytes(b'traceform.gw.compute-approval.v2\0' + bytes([owner])
                                 + APPROVED_CIRCUIT.encode() + case['bindings'])
            signature = subprocess.run([str(args.openssl), 'dgst', '-sha256', '-sign',
                                       str(identities / f'owner{owner}.key'), str(material)], capture_output=True, check=True).stdout
            upload(f'owner{owner}.sig', signature)
        upload('fixture.json', json.dumps({'case': case['case'], 'expected_valid': True,
                                           'approved_computation': True, 'lifecycle': lifecycle}).encode())

    initial = health()
    if not args.resume_recovery:
        report['health_before'] = initial
    if not initial['physical_alive'] or not initial['edge_alive'] or initial['latest_age_seconds'] > 120:
        raise RuntimeError('field baseline unhealthy')
    if 'tcp:24885' in subprocess.run(adb + ['forward', '--list'], capture_output=True, check=True).stdout.decode():
        raise RuntimeError('dedicated peer port already forwarded')
    if not args.resume_recovery:
        case = prepare_case('different_scale', 0, report['run_tag'])
        shell(['run-as', PACKAGE, 'mkdir', '-p', ROOT])
        for name in ('1.pem', '1.key', '2.pem', '2.pin', 'owner0.der', 'owner1.der'):
            upload(name, (identities / name).read_bytes())
        upload('circuit.txt', circuit.read_bytes())
        approve_case(case, 'pressure')
        shell(['run-as', PACKAGE, 'rm', '-f', ROOT + '/withdraw.signal', ROOT + '/pressure.abort', ROOT + '/running_pressure_attempt.json'])
    subprocess.run(adb + ['forward', 'tcp:24885', 'tcp:24885'], check=True)
    phase = threading.Event(); output, errors = [], []
    peer = None; readers = []
    save()
    try:
        if not args.resume_recovery:
            shell(['am', 'set-standby-bucket', PACKAGE, 'active'])
            shell(['am', 'broadcast', '-n', PACKAGE + '/org.traceform.edge.SyntheticPrivateAlignmentReceiver'])
            dispatch = json.loads(shell(['run-as', PACKAGE, 'cat', 'files/private_dispatch.json']))
            peer_result, signaled = run_peer(case, report, True)
            report['device'] = device = receipt('files/private_uniform16.json', dispatch['nonce'])
            wait_terminal(adb, 34716)
            report['attempt'] = json.loads(shell(['run-as', PACKAGE, 'cat', ROOT + '/running_pressure_attempt.json']))
            report['actual_running_withdrawal_verified'] = verified_withdrawal(device)
            report['snapshot_removed'] = 'private-circuit-' not in shell(['run-as', PACKAGE, 'ls', 'cache']).decode()
            ledger_before = shell(['run-as', PACKAGE, 'cat', 'files/computation_sessions.bin'])
            pid_before = shell(['pidof', PACKAGE]).decode().strip()
            report['app_pid_before_restart'] = int(pid_before)
            shell(['am', 'force-stop', PACKAGE])
            nonce = uuid.uuid4().hex
            shell(['am', 'broadcast', '-n', PACKAGE + '/org.traceform.edge.SyntheticComputationRestartReceiver',
                   '--es', 'phase', 'aborted_replay', '--es', 'nonce', nonce])
            replay = receipt('files/computation_restart.json', nonce)
            ledger_after = shell(['run-as', PACKAGE, 'cat', 'files/computation_sessions.bin'])
            report['restart_replay'] = replay
            report['actual_app_pid_changed'] = pid_before != str(replay.get('pid'))
            report['ledger_unchanged_after_restart'] = ledger_before == ledger_after
            report['ledger_bytes'] = len(ledger_after)
            report['ledger_sha256'] = hashlib.sha256(ledger_after).hexdigest()
        else:
            peer_result = report['peer']
            signaled = report.get('cpu_observation', {}).get('cpu_ticks_after_phase', 0) >= 50
            replay = report['restart_replay']
        save()
        recovery_case = prepare_case('different_scale', 1, report['run_tag'])
        approve_case(recovery_case, '')
        shell(['am', 'set-standby-bucket', PACKAGE, 'active'])
        shell(['am', 'broadcast', '-n', PACKAGE + '/org.traceform.edge.SyntheticPrivateAlignmentReceiver'])
        recovery_dispatch = json.loads(shell(['run-as', PACKAGE, 'cat', 'files/private_dispatch.json']))
        report['fresh_session_recovery'] = recovery = {}
        recovered_peer, _ = run_peer(recovery_case, recovery, False)
        recovered_device = receipt('files/private_uniform16.json', recovery_dispatch['nonce'])
        wait_terminal(adb, 34716)
        recovery['device'] = recovered_device
        recovery['passed'] = (recovered_peer['exit_code'] == 0 and recovered_device.get('passed') is True
            and recovered_device.get('output') == recovery_case['expected']
            and [line[14:] for line in recovered_peer['stdout'].splitlines() if line.startswith('PUBLIC_OUTPUT=')]
                == [recovery_case['expected']])
        recovered_ledger = shell(['run-as', PACKAGE, 'cat', 'files/computation_sessions.bin'])
        report['ledger_bytes_after_fresh_recovery'] = len(recovered_ledger)
        recovery['reservation_appended_once'] = len(recovered_ledger) == report['ledger_bytes'] + 32
        recovery['passed'] = recovery['passed'] and recovery['reservation_appended_once']
        report['health_after'] = health()
        report['passed'] = (report['actual_running_withdrawal_verified'] and signaled
            and peer_result['exit_code'] != 0 and 'PUBLIC_OUTPUT=' not in peer_result['stdout']
            and report['snapshot_removed'] and replay.get('passed') is True
            and report['actual_app_pid_changed'] and report['ledger_unchanged_after_restart'] and recovery['passed'])
        report['status'] = 'verified' if report['passed'] else 'bounded_condition_not_verified'
        save(); print(json.dumps({'passed': report['passed'], 'status': report['status']}))
    except Exception as error:
        report['error'] = str(error); save(); raise
    finally:
        if peer is not None and peer.poll() is None:
            peer.kill(); peer.wait(timeout=5)
        for reader in readers: reader.join(timeout=5)
        subprocess.run(adb + ['forward', '--remove', 'tcp:24885'], capture_output=True, check=True)


if __name__ == '__main__':
    main()
