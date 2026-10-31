from pathlib import Path
import importlib.util
import subprocess
import sys
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]


class ValidationSafetyTests(unittest.TestCase):
    def test_production_package_is_rejected_before_adb_runs(self):
        result = subprocess.run([sys.executable, str(ROOT / 'agent_os/scripts/validate_actions_android.py'),
            '--adb', '/no-such-adb', '--serial', 'unused', '--package', 'com.inceptless.traceweave',
            '--case', 'RUN_SUITE', '--output', '/tmp/unused-validation.json'], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Only isolated validation packages are supported', result.stderr)
        self.assertNotIn('CalledProcessError', result.stderr)

    def test_scoped_history_rejects_production_or_nondebug_apk_before_adb(self):
        path = ROOT / 'agent_os/scripts/validate_scoped_history_android.py'
        spec = importlib.util.spec_from_file_location('validate_scoped_history_android', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        argv = ['probe', '--adb', '/no-such-adb', '--serial', 'unused', '--edge-apk', 'edge.apk',
                '--probe-apk', 'probe.apk', '--build-tools', 'tools']
        for identity in ({'package': 'org.traceform.edge', 'debuggable': True},
                         {'package': 'org.traceform.edge.validation', 'debuggable': False}):
            with self.subTest(identity=identity), mock.patch.object(sys, 'argv', argv), \
                    mock.patch.object(module, 'apk_identity', return_value=identity), \
                    mock.patch.object(module.subprocess, 'run') as adb:
                with self.assertRaisesRegex(RuntimeError, 'isolated debug packages'):
                    module.main()
                adb.assert_not_called()

    def test_legacy_migration_rejects_unsafe_packages_or_signers_before_adb(self):
        path = ROOT / 'agent_os/scripts/validate_legacy_migration_android.py'
        spec = importlib.util.spec_from_file_location('validate_legacy_migration_android', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        argv = ['probe', '--adb', '/no-such-adb', '--serial', 'unused', '--source-apk', 'old.apk',
                '--target-apk', 'new.apk', '--build-tools', 'tools']
        valid = {'package': 'com.inceptless.traceweave.actionsvalidation', 'debuggable': True, 'signer_sha256': 'a'}
        cases = [([{'package': 'com.inceptless.traceweave', 'debuggable': True}], 'isolated debug packages'),
                 ([{'package': 'com.inceptless.traceweave.actionsvalidation', 'debuggable': False}], 'isolated debug packages'),
                 ([valid, {'package': 'com.inceptless.traceweave.documentsvalidation', 'debuggable': True,
                           'signer_sha256': 'b'}], 'signers differ')]
        for identities, message in cases:
            with self.subTest(message=message), mock.patch.object(sys, 'argv', argv), \
                    mock.patch.object(module, 'apk_identity', side_effect=identities), \
                    mock.patch.object(module.subprocess, 'run') as adb:
                with self.assertRaisesRegex(RuntimeError, message):
                    module.main()
                adb.assert_not_called()


if __name__ == '__main__':
    unittest.main()
