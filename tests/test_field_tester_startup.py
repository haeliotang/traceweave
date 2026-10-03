import fcntl
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STARTUP = ROOT / 'scripts/start_field_tester_service.sh'
PROBE = ROOT / 'scripts/daemon_lock_probe.py'

# Stand-in for python3 inside the startup script. The lock probe runs for real so
# the test exercises the actual flock semantics; the daemon and sync launches are
# recorded instead of executed.
EDGE_RUNTIME_STUB = '''#!/bin/sh
echo "daemon:${TRACEFORM_INTERVENTION_MODE:-unset}:$(printf %s "${TRACEFORM_INTERVENTION_ACTIONS:-}" | tr ' ' ,)" >> "$TASK_MARKER"
'''
PYTHON_STUB = f'''#!/bin/sh
case "$*" in
  *daemon_lock_probe.py*) exec {sys.executable} "$@" ;;
  *runtime_launch_mode.py*) exec {sys.executable} "$@" ;;
  *phone_field_sync.py*) echo sync >> "$TASK_MARKER" ;;
esac
exit 0
'''


class FieldTesterStartupCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        for name in ('scripts', 'data', 'bin'):
            (self.root / name).mkdir()
        self.script = self.root / 'scripts/start_field_tester_service.sh'
        self.script.write_text(STARTUP.read_text().replace(
            'export PATH="/data/data/com.termux/files/usr/bin:$PATH"', 'export PATH="$PATH"'))
        (self.root / 'scripts/daemon_lock_probe.py').write_text(PROBE.read_text())
        (self.root / 'scripts/runtime_launch_mode.py').write_text((ROOT / 'scripts/runtime_launch_mode.py').read_text())
        (self.root / 'scripts/edge_runtime.sh').write_text(EDGE_RUNTIME_STUB)
        self.marker = self.root / 'launches'
        self.lock_path = self.root / 'data/edge_manifold_daemon.lock'

    def stub(self, name, body):
        path = self.root / 'bin' / name
        path.write_text('#!/bin/sh\n' + body + '\n')
        path.chmod(0o755)

    def run_startup(self, pgrep_body='exit 1'):
        # pgrep deliberately reports "no match": a process started from another
        # SELinux domain (adb run-as vs the Termux app) is invisible to it.
        self.stub('pgrep', pgrep_body)
        (self.root / 'bin/python3').write_text(PYTHON_STUB)
        (self.root / 'bin/python3').chmod(0o755)
        env = dict(os.environ, PATH=str(self.root / 'bin') + ':' + os.environ['PATH'],
                   TASK_MARKER=str(self.marker))
        return subprocess.run(['bash', str(self.script)], env=env, capture_output=True, text=True)

    def launches(self):
        # The daemon launch is a background job; give it a moment to record itself.
        time.sleep(0.3)
        return self.marker.read_text().split() if self.marker.exists() else []


class TestFieldTesterStartup(FieldTesterStartupCase):
    def test_running_daemon_in_another_process_domain_keeps_silent_state(self):
        holder = open(self.lock_path, 'w')
        self.addCleanup(holder.close)
        fcntl.flock(holder.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        result = self.run_startup(pgrep_body='exit 1')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / 'data/.silent').exists(),
                         'invisible-but-locked daemon must not be muted or relaunched every tick')
        self.assertEqual([x for x in self.launches() if x.startswith('daemon')], [])
        self.assertIn('sync', self.launches())

    def test_existing_stop_switch_is_preserved_while_daemon_runs(self):
        holder = open(self.lock_path, 'w')
        self.addCleanup(holder.close)
        fcntl.flock(holder.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        (self.root / 'data/.silent').touch()
        result = self.run_startup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / 'data/.silent').exists())

    def test_absent_daemon_launches_silent_and_marks_stop_switch(self):
        result = self.run_startup(pgrep_body='exit 0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / 'data/.silent').exists(),
                        'a fresh unattended launch defaults to silent')
        self.assertEqual([x for x in self.launches() if x.startswith('daemon')], ['daemon:unset:'])

    def authorize(self, **fields):
        auth = {'mode': 'active', 'actions': ['pulse', 'notification', 'volume_duck'],
                'restart_policy': 'relaunch_active_after_runtime_failure'}
        auth.update(fields)
        (self.root / 'data/active_authorization.json').write_text(json.dumps(auth))

    def test_authorized_active_runtime_is_relaunched_active_without_muting(self):
        self.authorize()
        result = self.run_startup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / 'data/.silent').exists())
        self.assertEqual([x for x in self.launches() if x.startswith('daemon')],
                         ['daemon:active:pulse,notification,volume_duck'])

    def test_stop_switch_beats_the_active_relaunch_policy(self):
        self.authorize()
        (self.root / 'data/.silent').touch()
        result = self.run_startup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([x for x in self.launches() if x.startswith('daemon')], ['daemon:unset:'])
        self.assertTrue((self.root / 'data/.silent').exists())

    def test_authorization_without_the_restart_policy_still_relaunches_silent(self):
        self.authorize(restart_policy='none')
        self.run_startup()
        self.assertEqual([x for x in self.launches() if x.startswith('daemon')], ['daemon:unset:'])
        self.assertTrue((self.root / 'data/.silent').exists())

    def test_damaged_authorization_relaunches_silent(self):
        (self.root / 'data/active_authorization.json').write_text('{broken')
        self.run_startup()
        self.assertEqual([x for x in self.launches() if x.startswith('daemon')], ['daemon:unset:'])
        self.assertTrue((self.root / 'data/.silent').exists())

    def test_lock_probe_failure_does_not_mute_or_launch(self):
        self.lock_path.mkdir()  # unopenable as a lock file -> indeterminate state
        result = self.run_startup()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'data/.silent').exists())
        self.assertEqual(self.launches(), [], 'failed liveness query must not launch or sync')


class TestDaemonLockProbe(unittest.TestCase):
    def probe(self, path):
        return subprocess.run([sys.executable, str(PROBE), str(path)], capture_output=True, text=True).returncode

    def test_held_lock_reports_running(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'daemon.lock'
            with open(path, 'w') as holder:
                fcntl.flock(holder.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.assertEqual(self.probe(path), 0)

    def test_free_lock_reports_not_running_and_does_not_keep_the_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'daemon.lock'
            self.assertEqual(self.probe(path), 1)
            self.assertEqual(self.probe(path), 1, 'probe must release the lock it acquires')
            with open(path, 'w') as holder:  # a daemon can still acquire it afterwards
                fcntl.flock(holder.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_unopenable_lock_is_an_error_not_a_verdict(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'daemon.lock'
            path.mkdir()
            self.assertGreater(self.probe(path), 1)


if __name__ == '__main__':
    unittest.main()
