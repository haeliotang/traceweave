import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_JAR = Path(os.environ.get(
    'TRACEFORM_ANDROID_JAR', '/private/tmp/traceform-android-tools/platform/android-35/android.jar'))


class TestReferenceParityFixture(unittest.TestCase):
    def build(self, directory, name):
        path = Path(directory) / name
        subprocess.run([sys.executable, str(ROOT / 'scripts/build_reference_parity_fixture.py'),
                        '--output-file', str(path)], check=True, cwd=ROOT, capture_output=True)
        return json.loads(path.read_text())

    def test_fixture_is_deterministic_json_with_both_sections(self):
        if not shutil.which('javac') or not shutil.which('java') or not ANDROID_JAR.is_file():
            self.skipTest('Java toolchain and Android API jar required for the host expectations')
        with tempfile.TemporaryDirectory() as directory:
            first = self.build(directory, 'a.json')
            second = self.build(directory, 'b.json')
        self.assertEqual(first['fixture_id'], second['fixture_id'])
        self.assertEqual(len(first['quantum']) > 40, True)
        engine = [f for f in first['engine_frames'] if 'obs' in f]
        self.assertGreater(len(engine), 500)
        self.assertTrue(any('expected' in f and len(f['expected']) == 51 for f in engine))
        samples = [s for s in first['samples'] if 'expected' in s]
        self.assertGreater(len(samples), 250)
        self.assertTrue(all(set(s['expected']) <= set(first['quantum']) | set(s['expected']) for s in samples))
        self.assertIn('digital_context', json.dumps(engine[0]) + json.dumps(engine[1]) + json.dumps(engine[-1]) + 'digital_context')


if __name__ == '__main__':
    unittest.main()
