from pathlib import Path
import re
import shutil
import subprocess
import unittest
import zipfile


class TestNativeSdkDependencies(unittest.TestCase):
    def test_built_sdk_contains_every_referenced_traceform_class(self):
        root = Path(__file__).resolve().parents[1]
        sdk = root / 'android/build/traceform-sdk.jar'
        if not sdk.exists() or not shutil.which('jdeps'):
            self.skipTest('built Android SDK or JDK dependency analyzer unavailable')
        result = subprocess.run(['jdeps', '-verbose:class', '-filter:none', str(sdk)], check=True, capture_output=True, text=True)
        with zipfile.ZipFile(sdk) as archive:
            entries = set(archive.namelist())
        referenced = set(re.findall(r'->\s+(org\.traceform\.edge\.[\w$]+)', result.stdout))
        self.assertTrue(referenced, 'SDK dependency analysis produced no Traceform references')
        missing = sorted(name for name in referenced if name.replace('.', '/') + '.class' not in entries)
        self.assertEqual(missing, [], 'SDK references classes absent from its archive')
