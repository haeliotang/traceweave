"""Persistent v2 acquisition intent with identity-bound stop and recovery."""

from contextlib import contextmanager
import ctypes
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import time

from traceform.evolution.model_registry import atomic_json


def current_boot_id():
    return Path('/proc/sys/kernel/random/boot_id').read_text().strip()


def open_pidfd(pid):
    if hasattr(os, 'pidfd_open'):
        return os.pidfd_open(pid)
    libc = ctypes.CDLL('libc.so', use_errno=True)
    libc.pidfd_open.argtypes = [ctypes.c_int, ctypes.c_uint]
    libc.pidfd_open.restype = ctypes.c_int
    descriptor = libc.pidfd_open(pid, 0)
    if descriptor < 0:
        raise OSError(ctypes.get_errno(), 'pidfd_open failed')
    return descriptor


def send_pidfd_stop(descriptor):
    if hasattr(signal, 'pidfd_send_signal'):
        signal.pidfd_send_signal(descriptor, signal.SIGTERM)
        return
    libc = ctypes.CDLL('libc.so', use_errno=True)
    libc.pidfd_send_signal.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint]
    libc.pidfd_send_signal.restype = ctypes.c_int
    if libc.pidfd_send_signal(descriptor, signal.SIGTERM, None, 0) < 0:
        raise OSError(ctypes.get_errno(), 'pidfd_send_signal failed')


def process_identity(pid, runner_argument):
    try:
        process = Path('/proc') / str(pid)
        arguments = process.joinpath('cmdline').read_bytes().split(b'\0')
        stat = process.joinpath('stat').read_text().rsplit(') ', 1)[1].split()
        if os.fsencode(runner_argument) not in arguments or stat[0] == 'Z':
            return None
        return {'pid': pid, 'start_ticks': stat[19],
                'boot_id': Path('/proc/sys/kernel/random/boot_id').read_text().strip()}
    except (FileNotFoundError, ProcessLookupError):
        return None


class PhysicalSupervisor:
    def __init__(self, root, ledger_dir, command, runner_argument, preflight=None):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.ledger_dir = Path(ledger_dir)
        self.ledger_dir.mkdir(parents=True, exist_ok=True)
        self.command = command
        self.runner_argument = runner_argument
        self.preflight = preflight
        self.state_path = self.root / 'physical_v2_intent.json'

    @contextmanager
    def lock(self):
        with (self.root / 'physical_v2_supervision.lock').open('a') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            yield

    def state(self):
        return json.loads(self.state_path.read_text()) if self.state_path.exists() else {'desired': 'STOPPED', 'generation': 0}

    def owner(self):
        path = self.ledger_dir / 'physical_acquisition.pid'
        try:
            return process_identity(int(path.read_text()), self.runner_argument)
        except (FileNotFoundError, ValueError):
            return None

    def lock_held(self):
        with (self.ledger_dir / 'physical_acquisition.lock').open('a') as handle:
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return True
            return False

    def transact(self, action='tick'):
        if action not in ('start', 'stop', 'tick'):
            raise ValueError('invalid supervision action')
        with self.lock():
            state = self.state()
            if action in ('start', 'stop'):
                state['desired'] = 'RUNNING' if action == 'start' else 'STOPPED'
                state['generation'] += 1
                atomic_json(self.state_path, state)  # persist intent before actuation
            owner = self.owner()
            held = self.lock_held()
            if owner and held:
                expected = state.get('owner')
                if expected and expected != owner:
                    return {'status': 'identity_changed', 'desired': state['desired']}
                state['owner'] = owner
                atomic_json(self.state_path, state)
                if state['desired'] == 'STOPPED':
                    descriptor = open_pidfd(owner['pid'])
                    try:
                        if self.owner() != owner:
                            return {'status': 'identity_changed', 'desired': state['desired']}
                        send_pidfd_stop(descriptor)
                    finally:
                        os.close(descriptor)
                    return {'status': 'stop_requested', 'owner': owner}
                return {'status': 'running', 'owner': owner}
            if held:
                return {'status': 'foreign_or_unidentified_owner', 'desired': state['desired']}
            if state['desired'] == 'STOPPED':
                return {'status': 'stopped'}
            now = time.time()
            monotonic = time.monotonic()
            boot_id = current_boot_id()
            if state.get('last_launch_boot') == boot_id and state.get('last_launch_monotonic') is not None and 0 <= monotonic - state['last_launch_monotonic'] < 60:
                return {'status': 'recovery_cooldown'}
            state.pop('owner', None)
            state['last_launch_epoch'] = now
            state['last_launch_monotonic'] = monotonic
            state['last_launch_boot'] = boot_id
            atomic_json(self.state_path, state)
            if self.preflight is not None:
                readiness = self.preflight()
                state['last_preflight'] = readiness
                atomic_json(self.state_path, state)
                if not readiness['ready']:
                    return {'status': 'dependency_unhealthy', 'preflight': readiness}
            with (self.root / 'physical_v2.log').open('ab') as output:
                child = subprocess.Popen(self.command, stdin=subprocess.DEVNULL, stdout=output,
                                         stderr=output, start_new_session=True)
            return {'status': 'launch_requested', 'launcher_pid': child.pid,
                    'evidence_scope': 'process_spawn_not_healthy_acquisition'}
