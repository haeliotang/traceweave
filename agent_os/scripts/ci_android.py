#!/usr/bin/env python3
"""Run the portable Android build and host gates with a disposable test signer."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def run(command, env):
    subprocess.run([str(x) for x in command], cwd=ROOT, env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-jar', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--model-runtime', type=Path)
    args = parser.parse_args()
    if not args.android_jar.is_file() or not (args.build_tools / 'aapt2').is_file():
        parser.error('Android platform 35 and build-tools 35.0.0 must already be installed')
    build_root = ROOT / 'agent_os/build'
    build_root.mkdir(parents=True, exist_ok=True)
    if args.model_runtime:
        sys.path.insert(0, str(ROOT / 'agent_os/scripts'))
        from prepare_release_android import validate_model_runtime
        validate_model_runtime(args.model_runtime, ROOT)
    with tempfile.TemporaryDirectory(prefix='traceform-ci-', dir=build_root) as directory:
        base = Path(directory)
        env = os.environ.copy()
        env['AGENT_OS_KEYSTORE_PASSWORD'] = secrets.token_urlsafe(24)
        key = base / 'ci-test-key.p12'
        run(['keytool', '-genkeypair', '-alias', 'ci-test-only', '-keyalg', 'RSA', '-keysize', '2048',
             '-validity', '2', '-dname', 'CN=Traceform CI Test', '-storetype', 'PKCS12',
             '-keystore', key, '-storepass:env', 'AGENT_OS_KEYSTORE_PASSWORD',
             '-keypass:env', 'AGENT_OS_KEYSTORE_PASSWORD', '-noprompt'], env)
        out = base / 'candidate'
        command = [sys.executable, 'agent_os/scripts/build_android.py', '--android-jar', args.android_jar,
             '--build-tools', args.build_tools, '--keystore', key, '--key-alias', 'ci-test-only',
             '--output-dir', out]
        if args.model_runtime:
            command += ['--native-lib', args.model_runtime]
        run(command, env)
        env['AGENT_OS_BUILD_REPORT'] = str(out / 'build_report.json')
        run([sys.executable, '-m', 'unittest', 'discover', '-s', 'agent_os/tests', '-p', 'test_*.py', '-v'], env)
        sys.path.insert(0, str(ROOT / 'agent_os/scripts'))
        from verify_release_android import apk_identity, verify
        apk = out / 'agent-os.apk'
        identity = apk_identity(apk, args.build_tools)
        result = verify(apk, json.loads((out / 'build_report.json').read_text()), args.build_tools, identity['signer_sha256'],
                        require_model_runtime=bool(args.model_runtime))
        print('CI test APK passed package, inputs, signing and manifest gates:', result['version'])
        print('Disposable CI signer is not the production signing identity; no device or update is tested here.')


if __name__ == '__main__':
    main()
