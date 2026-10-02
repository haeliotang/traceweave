from pathlib import Path
import os
import subprocess
import tempfile
import unittest


class TestFieldTesterStartup(unittest.TestCase):
    def test_existing_daemon_does_not_restore_silent_flag(self):
        source = Path(__file__).resolve().parents[1] / 'scripts/start_field_tester_service.sh'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'data').mkdir()
            (root / 'bin').mkdir()
            script = root / 'scripts/start_field_tester_service.sh'
            script.write_text(source.read_text().replace('export PATH="/data/data/com.termux/files/usr/bin:$PATH"', 'export PATH="$PATH"'))
            for name, body in {'pgrep': 'exit 0', 'python3': 'exit 0'}.items():
                stub = root / 'bin' / name
                stub.write_text('#!/bin/sh\n' + body + '\n')
                stub.chmod(0o755)
            env = dict(os.environ, PATH=str(root / 'bin') + ':' + os.environ['PATH'])
            result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse((root / 'data/.silent').exists(), 'startup silently disables a running authorized daemon')
            (root / 'data/.silent').touch()
            result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue((root / 'data/.silent').exists(), 'startup must preserve an existing stop switch')

    def test_process_query_failure_does_not_mute_or_launch(self):
        source = Path(__file__).resolve().parents[1] / 'scripts/start_field_tester_service.sh'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('scripts', 'data', 'bin'):
                (root / name).mkdir()
            script = root / 'scripts/start_field_tester_service.sh'
            script.write_text(source.read_text().replace('export PATH="/data/data/com.termux/files/usr/bin:$PATH"', 'export PATH="$PATH"'))
            for name, body in {'pgrep': 'exit 2', 'python3': 'touch "$TASK_MARKER"; exit 0'}.items():
                stub = root / 'bin' / name
                stub.write_text('#!/bin/sh\n' + body + '\n')
                stub.chmod(0o755)
            marker = root / 'python-called'
            env = dict(os.environ, PATH=str(root / 'bin') + ':' + os.environ['PATH'], TASK_MARKER=str(marker))
            result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertFalse((root / 'data/.silent').exists())
            self.assertFalse(marker.exists(), 'failed process query must not launch or sync')
