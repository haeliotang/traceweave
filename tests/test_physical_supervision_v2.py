from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from traceform.streaming.physical_supervision_v2 import PhysicalSupervisor


class TestPhysicalSupervisor(unittest.TestCase):
    def setUp(self):
        boot = patch('traceform.streaming.physical_supervision_v2.current_boot_id', return_value='boot')
        boot.start()
        self.addCleanup(boot.stop)

    def supervisor(self, root):
        return PhysicalSupervisor(root / 'runtime', root / 'ledger', ['python', '/runner.py'], '/runner.py')

    def test_stopped_default_and_recovery_cooldown(self):
        with tempfile.TemporaryDirectory() as directory:
            supervisor = self.supervisor(Path(directory))
            self.assertEqual(supervisor.transact()['status'], 'stopped')
            with patch('traceform.streaming.physical_supervision_v2.subprocess.Popen', return_value=SimpleNamespace(pid=123)) as launch:
                self.assertEqual(supervisor.transact('start')['status'], 'launch_requested')
                self.assertEqual(supervisor.transact()['status'], 'recovery_cooldown')
                self.assertEqual(launch.call_count, 1)
                self.assertEqual(supervisor.state()['desired'], 'RUNNING')

    def test_foreign_lock_never_launches_or_signals(self):
        with tempfile.TemporaryDirectory() as directory:
            supervisor = self.supervisor(Path(directory))
            with patch.object(supervisor, 'lock_held', return_value=True), patch('traceform.streaming.physical_supervision_v2.os.kill') as kill, patch('traceform.streaming.physical_supervision_v2.subprocess.Popen') as launch:
                self.assertEqual(supervisor.transact('start')['status'], 'foreign_or_unidentified_owner')
                supervisor.transact('stop')
                kill.assert_not_called()
                launch.assert_not_called()

    def test_identity_binding_prevents_pid_reuse_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            supervisor = self.supervisor(Path(directory))
            original = {'pid': 123, 'start_ticks': '1', 'boot_id': 'boot'}
            with patch.object(supervisor, 'lock_held', return_value=True), patch.object(supervisor, 'owner', return_value=original):
                self.assertEqual(supervisor.transact('start')['status'], 'running')
            reused = dict(original, start_ticks='2')
            with patch.object(supervisor, 'lock_held', return_value=True), patch.object(supervisor, 'owner', return_value=reused), patch('traceform.streaming.physical_supervision_v2.os.kill') as kill:
                self.assertEqual(supervisor.transact('stop')['status'], 'identity_changed')
                kill.assert_not_called()
                self.assertEqual(supervisor.state()['desired'], 'STOPPED')

    def test_matching_stop_uses_bound_process_handle(self):
        import os
        with tempfile.TemporaryDirectory() as directory:
            supervisor = self.supervisor(Path(directory))
            owner = {'pid': 123, 'start_ticks': '1', 'boot_id': 'boot'}
            with patch.object(supervisor, 'lock_held', return_value=True), patch.object(supervisor, 'owner', return_value=owner):
                supervisor.transact('start')
                with patch('traceform.streaming.physical_supervision_v2.os.pidfd_open', create=True, side_effect=lambda pid: os.open(os.devnull, os.O_RDONLY)), patch('traceform.streaming.physical_supervision_v2.signal.pidfd_send_signal', create=True) as stop:
                    self.assertEqual(supervisor.transact('stop')['status'], 'stop_requested')
                    stop.assert_called_once()

    def test_wall_clock_rollback_does_not_extend_recovery(self):
        with tempfile.TemporaryDirectory() as directory:
            supervisor = self.supervisor(Path(directory))
            with patch('traceform.streaming.physical_supervision_v2.time.time', side_effect=[100, 10]), patch('traceform.streaming.physical_supervision_v2.time.monotonic', side_effect=[0, 61]), patch('traceform.streaming.physical_supervision_v2.current_boot_id', create=True, return_value='boot'), patch('traceform.streaming.physical_supervision_v2.subprocess.Popen', return_value=SimpleNamespace(pid=123)) as launch:
                supervisor.transact('start')
                self.assertEqual(supervisor.transact()['status'], 'launch_requested')
                self.assertEqual(launch.call_count, 2)

    def test_unhealthy_dependency_never_launches_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            supervisor = PhysicalSupervisor(Path(directory) / 'runtime', Path(directory) / 'ledger', ['python', '/runner.py'], '/runner.py', preflight=lambda: {'ready': False, 'reason': 'api_timeout'})
            with patch('traceform.streaming.physical_supervision_v2.subprocess.Popen') as launch:
                self.assertEqual(supervisor.transact('start')['status'], 'dependency_unhealthy')
                launch.assert_not_called()
                self.assertEqual(supervisor.state()['desired'], 'RUNNING')
