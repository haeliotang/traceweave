import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / 'scripts/runtime_launch_mode.py'
RUNTIME = ROOT / 'scripts/edge_runtime.sh'
POLICY = 'relaunch_active_after_runtime_failure'


def authorization(**overrides):
    base = {'mode': 'active', 'actions': ['pulse', 'notification', 'volume_duck'], 'restart_policy': POLICY}
    base.update(overrides)
    return base


class TestLaunchModeHelper(unittest.TestCase):
    def mode(self, data_dir):
        result = subprocess.run([sys.executable, str(HELPER), str(data_dir)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def case(self, auth=None, silent=False, raw=None):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        data = Path(directory.name)
        if auth is not None:
            (data / 'active_authorization.json').write_text(json.dumps(auth))
        if raw is not None:
            (data / 'active_authorization.json').write_text(raw)
        if silent:
            (data / '.silent').touch()
        return data

    def test_active_requires_policy_authorization_and_no_stop_switch(self):
        self.assertEqual(self.mode(self.case(authorization())), 'active pulse notification volume_duck')

    def test_stop_switch_always_wins(self):
        self.assertEqual(self.mode(self.case(authorization(), silent=True)), 'silent')

    def test_no_authorization_or_no_policy_stays_silent(self):
        self.assertEqual(self.mode(self.case()), 'silent')
        self.assertEqual(self.mode(self.case({'mode': 'active', 'actions': ['pulse']})), 'silent')
        self.assertEqual(self.mode(self.case(authorization(restart_policy='something_else'))), 'silent')

    def test_malformed_or_unknown_content_stays_silent(self):
        self.assertEqual(self.mode(self.case(raw='{not json')), 'silent')
        self.assertEqual(self.mode(self.case(authorization(mode='shadow'))), 'silent')
        self.assertEqual(self.mode(self.case(authorization(actions=['pulse', 'format_disk']))), 'silent')
        self.assertEqual(self.mode(self.case(authorization(actions=[]))), 'silent')
        self.assertEqual(self.mode(self.case(raw='[1,2]')), 'silent')

    def test_missing_data_directory_is_silent(self):
        self.assertEqual(self.mode('/nonexistent/traceform/data'), 'silent')


class TestEdgeRuntimeArguments(unittest.TestCase):
    def arguments(self, **env):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'bin').mkdir()
            script = root / 'scripts/edge_runtime.sh'
            script.write_text(RUNTIME.read_text().replace(
                'export PATH="/data/data/com.termux/files/usr/bin:$PATH"', 'export PATH="$PATH"'))
            record = root / 'args.txt'
            for name, body in {'python3': 'printf "%s\\n" "$@" > "$RECORD"; echo "PYTHONTZPATH=$PYTHONTZPATH" >> "$RECORD"',
                               'getprop': 'echo Asia/Kathmandu'}.items():
                stub = root / 'bin' / name
                stub.write_text('#!/bin/sh\n' + body + '\n')
                stub.chmod(0o755)
            full_env = dict(os.environ, PATH=str(root / 'bin') + ':' + os.environ['PATH'], RECORD=str(record), **env)
            result = subprocess.run(['bash', str(script)], env=full_env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            return record.read_text().splitlines()

    def test_default_is_the_documented_shadow_runtime(self):
        lines = self.arguments()
        self.assertIn('--disable-haptics', lines)
        self.assertEqual(lines[lines.index('--intervention-mode') + 1], 'shadow')
        self.assertIn('tiered', lines)
        self.assertEqual(lines[lines.index('--timezone') + 1], 'Asia/Kathmandu')
        self.assertTrue(any(line.startswith('PYTHONTZPATH=/data/data/com.termux') for line in lines))

    def test_active_mode_enables_the_authorized_actions_without_disabling_haptics(self):
        lines = self.arguments(TRACEFORM_INTERVENTION_MODE='active',
                               TRACEFORM_INTERVENTION_ACTIONS='pulse notification volume_duck')
        self.assertNotIn('--disable-haptics', lines)
        self.assertEqual(lines[lines.index('--intervention-mode') + 1], 'active')
        at = lines.index('--intervention-actions')
        self.assertEqual(lines[at + 1:at + 4], ['pulse', 'notification', 'volume_duck'])
        self.assertEqual(lines[lines.index('--csd-input-space') + 1], 'continuous')
        self.assertEqual(lines[lines.index('--timezone') + 1], 'Asia/Kathmandu')

    def test_unknown_mode_falls_back_to_shadow(self):
        lines = self.arguments(TRACEFORM_INTERVENTION_MODE='whatever')
        self.assertEqual(lines[lines.index('--intervention-mode') + 1], 'shadow')
        self.assertIn('--disable-haptics', lines)


if __name__ == '__main__':
    unittest.main()
