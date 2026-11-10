# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import importlib.util
import hashlib
import shutil
import tempfile
import unittest


class TestNativeSigningIdentity(unittest.TestCase):
    def build_module(self):
        source = Path(__file__).resolve().parents[1] / 'scripts/build_native_android.py'
        spec = importlib.util.spec_from_file_location('native_signing_build', source)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_explicit_key_preserves_upgrade_identity_across_output_directories(self):
        if not shutil.which('keytool'):
            self.skipTest('JDK keytool unavailable')
        module = self.build_module()
        with tempfile.TemporaryDirectory() as directory:
            first, second = Path(directory) / 'first', Path(directory) / 'second'
            first.mkdir(); second.mkdir()
            key = module.prepare_signing_key(first, None)
            digest = hashlib.sha256(key.read_bytes()).hexdigest()
            reused = module.prepare_signing_key(second, key)
            self.assertEqual(reused.resolve(), key.resolve())
            self.assertEqual(hashlib.sha256(reused.read_bytes()).hexdigest(), digest)
            self.assertFalse((second / 'development.keystore').exists())

    def test_missing_explicit_key_never_creates_replacement_identity(self):
        module = self.build_module()
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            with self.assertRaises(FileNotFoundError):
                module.prepare_signing_key(base, base / 'existing-owner.keystore')
            self.assertEqual(list(base.iterdir()), [])
