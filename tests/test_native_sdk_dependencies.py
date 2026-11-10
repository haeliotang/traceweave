# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import importlib.util
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile


class TestNativeSdkDependencies(unittest.TestCase):
    def test_unknown_score_entry_is_usable_from_built_sdk(self):
        root = Path(__file__).resolve().parents[1]
        android_jar = Path('/private/tmp/traceform-android-tools/platform/android-35/android.jar')
        if not android_jar.exists() or any(not shutil.which(tool) for tool in ('javac', 'jdeps', 'jar')):
            self.skipTest('Android platform or JDK tools unavailable')
        spec = importlib.util.spec_from_file_location('native_android_build', root / 'scripts/build_native_android.py')
        build = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(build)
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            classes = base / 'classes'
            classes.mkdir()
            subprocess.run(['javac', '--release', '8', '-classpath', str(android_jar), '-d', str(classes),
                            *map(str, sorted((root / 'android/src').rglob('*.java')))], check=True, capture_output=True)
            sdk = base / 'traceform-sdk.jar'
            build.build_sdk_jar(classes, sdk, android_jar)
            client = base / 'UnknownScoreClient.java'
            client.write_text('''import com.inceptless.traceweave.edge.UniformPrivateAlignment;
public final class UnknownScoreClient {
    public static String compute(android.content.Context context, String[] command,
        java.io.File circuit, java.io.File request, java.security.PublicKey[] owners,
        String ownBits) throws Exception {
        return UniformPrivateAlignment.computeApproved(context, command, circuit, request, owners, ownBits);
    }
}''')
            result = subprocess.run(['javac', '--release', '8', '-classpath', str(sdk) + ':' + str(android_jar),
                                     '-d', str(base), str(client)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            analysis = subprocess.run(['jdeps', '-verbose:class', '-filter:none', str(sdk)],
                                      check=True, capture_output=True, text=True)
            with zipfile.ZipFile(sdk) as archive:
                entries = set(archive.namelist())
            referenced = set(re.findall(r'->\s+(org\.traceform\.edge\.[\w$]+)', analysis.stdout))
            self.assertEqual(sorted(name for name in referenced if name.replace('.', '/') + '.class' not in entries), [])

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
