from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]


class ValidationSafetyTests(unittest.TestCase):
    def test_production_package_is_rejected_before_adb_runs(self):
        result = subprocess.run([sys.executable, str(ROOT / 'agent_os/scripts/validate_actions_android.py'),
            '--adb', '/no-such-adb', '--serial', 'unused', '--package', 'org.traceform.agentos',
            '--case', 'RUN_SUITE', '--output', '/tmp/unused-validation.json'], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Only isolated validation packages are supported', result.stderr)
        self.assertNotIn('CalledProcessError', result.stderr)


if __name__ == '__main__':
    unittest.main()
