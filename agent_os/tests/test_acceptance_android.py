import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('acceptance', ROOT / 'agent_os/scripts/acceptance_android.py')
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class UpdatePreflightTests(unittest.TestCase):
    def test_installed_package_must_match_the_known_previous_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = Path(directory) / 'previous.apk'
            previous.write_bytes(b'known previous APK')
            with patch.object(acceptance, 'installed_apk', return_value=b'known previous APK'):
                acceptance.check_update_base(['unused'], previous)
            with patch.object(acceptance, 'installed_apk', return_value=b'different installed APK'):
                with self.assertRaisesRegex(RuntimeError, 'update aborted'):
                    acceptance.check_update_base(['unused'], previous)

    def test_cross_install_check_rejects_shared_uid(self):
        def fake(args, **kwargs):
            return SimpleNamespace(stdout='package:' + args[-1] + ' uid:10100\n')
        with patch.object(acceptance, 'execute', side_effect=fake):
            with self.assertRaisesRegex(RuntimeError, 'share an Android UID'):
                acceptance.distinct_validation_uids(['unused'])


if __name__ == '__main__':
    unittest.main()
