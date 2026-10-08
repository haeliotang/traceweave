#!/usr/bin/env python3
"""Bounded cached-app LMK attempt; only Android LOW_MEMORY exit evidence can pass."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.verify_guarded_private_sdk_usb import prepare_case, wait_terminal
from scripts.preflight_private_computation_fixture import APPROVED_CIRCUIT, approval_bits

PACKAGE = 'org.traceform.edge.synthetic'
ROOT = 'files/private_uniform16'


def verified_system_lmk(recovery, child_absent, normal_passed, controlled):
    return (not controlled and child_absent and normal_passed and recovery.get('passed') is True
        and recovery.get('actual_system_lmk') is True
        and recovery.get('prepared_epoch_ms', 0) > 0
        and any(record.get('pid') == recovery.get('old_pid') and record.get('reason') == 3
                and record.get('timestamp_epoch_ms', 0) >= recovery['prepared_epoch_ms']
                for record in recovery.get('exit_records', [])))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--openssl', type=Path, required=True)
    parser.add_argument('--pressure-executable', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--resume-prepared', action='store_true', help='Continue an existing prepared cached-priority attempt')
    parser.add_argument('--controlled-recovery-only', action='store_true',
                        help='Stop only the retained synthetic app and test recovery; never certify LMK')
    parser.add_argument('--prepare-only', action='store_true', help='Verify cached preparation without applying pressure')
    args = parser.parse_args()
    base = args.runtime.resolve(); identities = base / 'fixture'
    adb = [str(args.adb), '-s', args.serial]
    report = {'passed': False, 'run_tag': uuid.uuid4().hex, 'status': 'in_progress',
              'scope': 'cached_approved_request_waiting_for_peer_LMK_not_CPU_phase',
              'pressure_limits': {'step_mib': 16, 'maximum_mib': 1024, 'kernel_floor_mib': 160,
                                  'child_timeout_seconds': 120, 'pressure_child_oom_score_adj': 800}}

    if args.resume_prepared or args.controlled_recovery_only:
        report = json.loads(args.output.read_text())
        report['passed'] = False
        report['status'] = 'in_progress'

    def save():
        args.output.write_text(json.dumps(report, indent=2) + '\n')

    def shell(command, data=None):
        return subprocess.run(adb + ['shell', shlex.join(command)], input=data, capture_output=True,
                              check=True, timeout=30).stdout

    def upload(name, payload):
        shell(['run-as', PACKAGE, 'sh', '-c', 'cat > ' + ROOT + '/' + name], payload)

    def prepare(index):
        case = prepare_case('different_scale', index, report['run_tag'])
        upload('own.bits', case['own'].encode()); upload('bindings.bin', case['bindings'])
        for owner in range(2):
            material = base / 'lmk-approval-message.bin'
            material.write_bytes(b'traceform.gw.compute-approval.v2\0' + bytes([owner])
                                 + APPROVED_CIRCUIT.encode() + case['bindings'])
            signature = subprocess.run([str(args.openssl), 'dgst', '-sha256', '-sign',
                str(identities / f'owner{owner}.key'), str(material)], check=True, capture_output=True).stdout
            upload(f'owner{owner}.sig', signature)
        return case

    def receipt(path, nonce, timeout=35):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = subprocess.run(adb + ['shell', shlex.join(['run-as', PACKAGE, 'cat', path])],
                                    capture_output=True, timeout=10)
            if result.returncode == 0:
                value = json.loads(result.stdout)
                if value.get('nonce') == nonce: return value
            time.sleep(.2)
        raise TimeoutError('fresh fixture receipt unavailable')

    def alive(pid):
        return subprocess.run(adb + ['shell', f'test -d /proc/{int(pid)}'], capture_output=True, timeout=10).returncode == 0

    def health():
        code = "import pathlib,sqlite3,time,json;r=pathlib.Path('/data/data/com.termux/files/home/traceform');p=int((r/'data/edge_manifold_daemon.pid').read_text());d=sqlite3.connect('file:'+str(r/'data/edge_storage.sqlite')+'?mode=ro',uri=True);t=d.execute('select max(ts) from hot').fetchone()[0];print(json.dumps({'physical_alive':pathlib.Path('/proc/12155').exists(),'physical_pid':12155,'edge_pid':p,'edge_alive':pathlib.Path('/proc/'+str(p)).exists(),'latest_age_seconds':time.time()-t}))"
        return json.loads(shell(['run-as', 'com.termux', '/data/data/com.termux/files/usr/bin/python', '-c', code]))

    def recover_and_run_new_session(prepared, controlled):
        old_pid = prepared['pid']
        if controlled:
            shell(['am', 'force-stop', PACKAGE])
        nonce = str(uuid.uuid4())
        shell(['am', 'broadcast', '-n', PACKAGE + '/org.traceform.edge.SyntheticLmkRecoveryReceiver',
               '--ei', 'old_pid', str(old_pid), '--el', 'prepared_epoch_ms', str(prepared['prepared_epoch_ms']),
               '--es', 'nonce', nonce])
        recovered = receipt('files/lmk_recovery.json', nonce)
        report['controlled_cleanup_requested'] = controlled
        report['recovery'] = recovered
        report['computation_child_absent'] = not alive(prepared['child_pid'])
        report['actual_system_lmk'] = recovered.get('actual_system_lmk') is True and not controlled
        report['snapshot_recovery_verified'] = (recovered.get('snapshots_before', 0) > 0
            and recovered.get('snapshots_after') == 0 and recovered.get('replay_rejected') is True
            and recovered.get('ledger_unchanged') is True and report['computation_child_absent'])
        save()
        before = shell(['run-as', PACKAGE, 'cat', 'files/computation_sessions.bin'])
        case = prepare(1)
        upload('fixture.json', json.dumps({'case': 'fresh_after_cached_recovery', 'expected_valid': True,
                                         'approved_computation': True}).encode())
        if 'tcp:24885' in subprocess.run(adb + ['forward', '--list'], capture_output=True, check=True).stdout.decode():
            raise RuntimeError('dedicated peer port already forwarded')
        subprocess.run(adb + ['forward', 'tcp:24885', 'tcp:24885'], check=True, capture_output=True)
        try:
            shell(['am', 'set-standby-bucket', PACKAGE, 'active'])
            shell(['am', 'broadcast', '-n', PACKAGE + '/org.traceform.edge.SyntheticPrivateAlignmentReceiver'])
            dispatch = json.loads(shell(['run-as', PACKAGE, 'cat', 'files/private_dispatch.json']))
            command = [str(base / 'host_phase_participant'), '2', '24885', str(base / 'guarded.txt'),
                       str(identities / '2.pem'), str(identities / '2.key'), str(identities / '1.pem'),
                       (identities / '1.pin').read_text(), case['session'].hex()]
            deadline = time.monotonic() + 60; retries = 0
            while True:
                peer = subprocess.run(command, input=(case['peer'] + approval_bits(case['bindings']) + '\n').encode(),
                                      capture_output=True, timeout=150)
                if (peer.returncode == 1 and peer.stdout == b'connected\n'
                    and b'unexpected eof while reading' in peer.stderr and time.monotonic() < deadline):
                    retries += 1; continue
                break
            device = receipt('files/private_uniform16.json', dispatch['nonce']); wait_terminal(adb, 34716)
            after = shell(['run-as', PACKAGE, 'cat', 'files/computation_sessions.bin'])
            normal = {'device': device, 'peer_exit': peer.returncode,
                      'peer_stdout': peer.stdout.decode(), 'peer_stderr': peer.stderr.decode(),
                      'transient_startup_retries': retries, 'ledger_before_bytes': len(before), 'ledger_after_bytes': len(after)}
            normal['passed'] = (peer.returncode == 0 and device.get('passed') is True
                and device.get('output') == case['expected'] and len(after) == len(before) + 32
                and [line[14:] for line in peer.stdout.decode().splitlines() if line.startswith('PUBLIC_OUTPUT=')]
                    == [case['expected']])
            report['fresh_session_recovery'] = normal
        finally:
            subprocess.run(adb + ['forward', '--remove', 'tcp:24885'], capture_output=True, check=True)
        report['health_after_recovery'] = health()
        report['passed'] = verified_system_lmk(recovered, report['computation_child_absent'], normal['passed'], controlled)
        report['status'] = 'system_lmk_recovery_verified' if report['passed'] else 'LMK_unverified_recovery_tested'
        save()
        return report['passed']

    report['health_before'] = initial = health()
    if not initial['physical_alive'] or not initial['edge_alive'] or initial['latest_age_seconds'] > 120:
        raise RuntimeError('collector baseline unhealthy')
    if args.controlled_recovery_only:
        recover_and_run_new_session(report['prepared'], True)
        print(json.dumps({'passed': report['passed'], 'status': report['status'],
                          'snapshot_recovery_verified': report['snapshot_recovery_verified']}))
        return
    circuit = base / 'guarded.txt'
    if hashlib.sha256(circuit.read_bytes()).hexdigest() != APPROVED_CIRCUIT: raise ValueError('circuit pin mismatch')
    subprocess.run(adb + ['push', str(args.pressure_executable), '/data/local/tmp/traceform_lmk_pressure'], check=True, capture_output=True)
    shell(['chmod', '755', '/data/local/tmp/traceform_lmk_pressure'])
    if not args.resume_prepared:
        shell(['run-as', PACKAGE, 'mkdir', '-p', ROOT])
        for name in ('1.pem', '1.key', '2.pem', '2.pin', 'owner0.der', 'owner1.der'):
            upload(name, (identities / name).read_bytes())
        upload('circuit.txt', circuit.read_bytes()); prepare(0)
        nonce = str(uuid.uuid4())
        shell(['am', 'start', '-n', PACKAGE + '/org.traceform.edge.SyntheticLmkActivity', '--es', 'nonce', nonce])
        report['prepared'] = prepared = receipt('files/lmk_prepared.json', nonce)
    else:
        prepared = report['prepared']
    save()
    if not prepared.get('low_memory_kill_reporting_supported'):
        raise RuntimeError('system LMK reason reporting unsupported; pressure not attempted')
    report['pressure_limits']['pressure_child_oom_score_adj'] = 800
    report['pressure_limits']['kernel_floor_mib'] = 160
    report['pressure_limits']['touch_existing_pages_per_command_seconds'] = 2
    old_pid = prepared['pid']
    shell(['am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'])
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        score = int(shell(['run-as', PACKAGE, 'cat', f'/proc/{old_pid}/oom_score_adj']))
        if score >= 900: break
        time.sleep(.2)
    else: raise RuntimeError('fixture did not enter cached priority; pressure not attempted')
    report['cached_target_oom_score_adj'] = score
    if args.prepare_only:
        report['status'] = 'cached_preparation_verified'
        report['pressure_started'] = False
        save(); print(json.dumps({'status': report['status'], 'cached_oom_score_adj': score}))
        return
    holder = None
    try:
        holder = subprocess.Popen(adb + ['shell', 'exec /data/local/tmp/traceform_lmk_pressure lmk'],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        deadline = time.monotonic() + 60; hold_started = None; next_health = 0
        report['memory_samples'] = []; report['health_during'] = []
        while time.monotonic() < deadline:
            line = holder.stdout.readline()
            if not line: break
            sample = json.loads(line); report['memory_samples'].append(sample)
            if not alive(old_pid): break
            if time.monotonic() >= next_health:
                current = health(); report['health_during'].append(current)
                if (not current['physical_alive'] or not current['edge_alive']
                    or current['edge_pid'] != initial['edge_pid'] or current['latest_age_seconds'] > 120):
                    raise RuntimeError('collector health guard aborted pressure')
                next_health = time.monotonic() + 3
            if sample['allocated_mib'] >= 1024 or sample['available_kb'] < 176 * 1024:
                if hold_started is None: hold_started = time.monotonic()
                if time.monotonic() - hold_started > 5: break
                holder.stdin.write(b'TOUCH\n')
            else: holder.stdin.write(b'GROW\n')
            holder.stdin.flush()
        report['target_disappeared_before_manual_cleanup'] = not alive(old_pid)
    finally:
        if holder is not None:
            if holder.poll() is None:
                try: holder.stdin.write(b'RELEASE\n'); holder.stdin.flush(); holder.stdin.close()
                except BrokenPipeError: pass
                try: holder.wait(timeout=5)
                except subprocess.TimeoutExpired: holder.kill(); holder.wait(timeout=5)
            report['pressure_child_exit'] = holder.returncode
            report['pressure_stderr'] = holder.stderr.read().decode()
        report['health_after_pressure'] = health(); save()

    if report['target_disappeared_before_manual_cleanup']:
        recover_and_run_new_session(prepared, False)
    else:
        report['actual_system_lmk'] = False
        report['retained_cache_names'] = shell(['run-as', PACKAGE, 'ls', 'cache']).decode().splitlines()
    report['status'] = 'system_lmk_recovery_verified' if report['passed'] else 'bounded_LMK_condition_not_verified'
    save(); print(json.dumps({'passed': report['passed'], 'status': report['status']}))


if __name__ == '__main__':
    main()
