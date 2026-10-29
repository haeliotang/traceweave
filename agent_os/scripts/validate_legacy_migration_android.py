#!/usr/bin/env python3
"""Test an old synthetic archive between isolated debug packages, never production."""
import argparse
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'agent_os/scripts'))
from acceptance_android import distinct_validation_uids
from verify_release_android import apk_identity

SOURCE = 'org.traceform.agentos.actionsvalidation'
TARGET = 'org.traceform.agentos.documentsvalidation'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('adb', 'serial', 'source-apk', 'target-apk', 'build-tools'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    identities = []
    for apk, package in ((args.source_apk, SOURCE), (args.target_apk, TARGET)):
        identity = apk_identity(Path(apk), Path(args.build_tools))
        if identity['package'] != package or not identity['debuggable']:
            raise RuntimeError('Only the expected isolated debug packages are supported')
        identities.append(identity)
    if identities[0]['signer_sha256'] != identities[1]['signer_sha256']:
        raise RuntimeError('Validation package signers differ')
    adb = [args.adb, '-s', args.serial]

    def run(*arguments, **kwargs):
        return subprocess.run(adb + list(arguments), check=True, **kwargs)

    packages = run('shell', 'pm', 'list', 'packages', capture_output=True, text=True).stdout.splitlines()
    if any('package:' + package in packages for package in (SOURCE, TARGET)):
        raise RuntimeError('Existing validation applications must be preserved')
    installed = []
    try:
        for package, apk in ((SOURCE, args.source_apk), (TARGET, args.target_apk)):
            run('install', apk, stdout=subprocess.DEVNULL)
            installed.append(package)
        distinct_validation_uids(adb)
        for package, marker in ((SOURCE, 'synthetic-source-credential'), (TARGET, 'synthetic-target-credential')):
            prefs = ('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>'
                     '<map><boolean name="cloud_model_enabled" value="true" />'
                     '<string name="cloud_endpoint_url">https://example.invalid/fixture</string>'
                     '<string name="cloud_api_key">' + marker + '</string>'
                     '<string name="cloud_model_name">synthetic-fixture</string></map>').encode()
            command = shlex.join(['run-as', package, 'sh', '-c',
                'mkdir -p shared_prefs && cat > shared_prefs/agent_os_cloud_model_prefs.xml'])
            run('shell', command, input=prefs)
        with tempfile.TemporaryDirectory(prefix='traceform-legacy-validation-') as directory:
            common = [sys.executable, str(ROOT / 'agent_os/scripts/validate_actions_android.py'),
                      '--adb', args.adb, '--serial', args.serial]
            subprocess.run(common + ['--case', 'EXPORT_MIGRATION', '--output', str(Path(directory) / 'export.json')],
                           cwd=ROOT, check=True)
            source_prefs = run('exec-out', 'run-as', SOURCE, 'cat', 'shared_prefs/agent_os_cloud_model_prefs.xml',
                               capture_output=True).stdout
            if b'synthetic-source-credential' not in source_prefs:
                raise RuntimeError('Synthetic source cloud credential was not present at export')
            subprocess.run([sys.executable, str(ROOT / 'agent_os/scripts/copy_document_validation_fixture.py'),
                            '--adb', args.adb, '--serial', args.serial], cwd=ROOT, check=True)
            subprocess.run(common + ['--package', TARGET, '--case', 'IMPORT_MIGRATION_WITH_CLOUD_FIXTURE',
                                     '--output', str(Path(directory) / 'import.json')], cwd=ROOT, check=True)
        print('PASS legacy synthetic archive migrated without overwriting target cloud configuration')
    finally:
        for package in reversed(installed):
            run('uninstall', package, stdout=subprocess.DEVNULL)
        print('Isolated applications and synthetic archives removed')


if __name__ == '__main__':
    main()
