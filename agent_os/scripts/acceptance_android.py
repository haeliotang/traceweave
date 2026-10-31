#!/usr/bin/env python3
"""Build and verify a candidate, run isolated device regression and migration, optionally install it.

Requires an explicit device serial and existing signing key. Never uninstalls or clears
production, never exports user data and never publishes a GitHub Release.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCE = 'com.inceptless.traceweave.actionsvalidation'
TARGET = 'com.inceptless.traceweave.documentsvalidation'
PRODUCTION = 'com.inceptless.traceweave'
EXTRAS = ('DocumentFlowValidationActivity', 'DocumentFixtureProvider', 'ActionFlowValidationActivity',
          'DeviceValidationActivity', 'WorkflowValidationActivity', 'BackgroundProbeJob',
          'BackgroundProbeActivity', 'ControlledProbeService')
CASES = ('MODEL_CANCEL_RACE', 'MODEL_DOCUMENT', 'MODEL_DOCUMENT_FAILURES', 'MEMORY_BOUNDARY',
         'SNAPSHOT_BOUNDARY', 'VERIFY_HYBRID_CLOUD_AND_REDACTION', 'DUPLICATE_RECEIPT',
         'INVALID_DRAFT', 'STOP_TRACKING', 'BASELINE_CLOUD', 'VERIFY_PRIVACY', 'VERIFY_MEMORY_REVIEW',
         'RUN_DOCUMENTS', 'VERIFY_DOCUMENT_RESTART', 'ARM_DOCUMENT_CRASH', 'VERIFY_DOCUMENT_RESTART',
         'RUN_SUITE', 'VERIFY_RESTART', 'VERIFY_UPGRADE', 'VERIFY_DYNAMIC_EXECUTOR',
         'VERIFY_EXECUTOR_LIFECYCLE', 'VERIFY_MEMORY_PROVENANCE', 'VERIFY_MEMORY_CONSOLIDATION_AND_UI',
         'VERIFY_DATABASE_MIGRATION', 'WORKFLOW_RUNS', 'MEMORY_CHOICES', 'TASK_CENTER', 'WORKFLOW_GUARD',
         'VERIFY_SENSOR_OPT_IN', 'SENSOR_CONTEXT', 'SENSOR_WINDOW', 'MEMORY_LIFECYCLE',
         'DOCUMENT_CALENDAR_CHAIN', 'VERIFY_CHAIN_RESTART', 'DOCUMENT_SHARE_CHAIN', 'VERIFY_SHARE_RESTART', 'SHARE_ESCAPED_CONTENT', 'CLOUD_PLAN_GUARD', 'LOCAL_ENGINE_LABELS', 'LOCAL_MODEL_DRAFT_BOUNDARY',
         'SCOPED_SUMMARY_CONTRACT', 'REVIEW_ARCHIVE_CONFLICTS', 'ARCHIVE_DELETE_STALE')


def execute(args, **kwargs):
    return subprocess.run([str(x) for x in args], cwd=ROOT, check=True, **kwargs)


def installed_apk(adb):
    result = execute(adb + ['shell', 'pm', 'path', PRODUCTION], capture_output=True, text=True).stdout.strip()
    match = re.fullmatch(r'package:(/data/app/[A-Za-z0-9_./~+=-]+/base\.apk)', result)
    if not match:
        raise RuntimeError('The installed production APK path is unavailable or ambiguous')
    return execute(adb + ['exec-out', 'cat', match.group(1)], capture_output=True).stdout


def check_update_base(adb, previous):
    if hashlib.sha256(installed_apk(adb)).digest() != hashlib.sha256(previous.read_bytes()).digest():
        raise RuntimeError('Installed production APK differs from the supplied previous APK; update aborted')


def installed_time(adb):
    result = execute(adb + ['shell', 'dumpsys', 'package', PRODUCTION], capture_output=True, text=True).stdout
    match = re.search(r'^\s*firstInstallTime=(.+)$', result, re.MULTILINE)
    if not match:
        raise RuntimeError('Production installation time unavailable')
    return match.group(1).strip()


def distinct_validation_uids(adb):
    uids = []
    for package in (SOURCE, TARGET):
        result = execute(adb + ['shell', 'pm', 'list', 'packages', '-U', package], capture_output=True, text=True).stdout
        match = re.search(r'^package:' + re.escape(package) + r'\s+uid:(\d+)\s*$', result, re.MULTILINE)
        if not match:
            raise RuntimeError('Cannot verify isolated validation package identity')
        uids.append(match.group(1))
    if uids[0] == uids[1]:
        raise RuntimeError('Validation packages unexpectedly share an Android UID')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('android-jar', 'build-tools', 'keystore', 'previous-apk', 'output-dir'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--key-alias', required=True)
    parser.add_argument('--expected-signer', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--install', action='store_true', help='Install the verified update in place after all automated checks')
    parser.add_argument('--legacy-validation-apk', type=Path, help='Optional trusted older isolated source APK for archive compatibility')
    parser.add_argument('--model-runtime', type=Path, help='Optional pinned, locally rebuilt model runtime')
    parser.add_argument('--sync-runtime', type=Path, help='Optional locally built sync runtime')
    args = parser.parse_args()
    out = args.output_dir.resolve()
    if not args.previous_apk.is_file() or args.previous_apk.resolve().is_relative_to(out):
        parser.error('The previous APK must exist outside the output directory')
    out.mkdir(parents=True, exist_ok=True)
    receipt = out / 'acceptance.json'
    receipt.unlink(missing_ok=True)
    adb = [args.adb, '-s', args.serial]
    policy = execute(adb + ['shell', 'dumpsys', 'window', 'policy'], capture_output=True, text=True).stdout
    if not re.search(r'^\s*showing=false\s*$', policy, re.MULTILINE) or 'interactiveState=INTERACTIVE_STATE_AWAKE' not in policy:
        raise RuntimeError('Unlock and wake the test device before running the UI-driven isolated checks')
    packages = execute(adb + ['shell', 'pm', 'list', 'packages'], capture_output=True, text=True).stdout.splitlines()
    if any('package:' + p in packages for p in (SOURCE, TARGET)):
        raise RuntimeError('An isolated validation package already exists; preserve it and resolve before proceeding')
    check_update_base(adb, args.previous_apk)
    first_install = installed_time(adb)
    common = ['--android-jar', args.android_jar, '--build-tools', args.build_tools,
              '--keystore', args.keystore, '--key-alias', args.key_alias]
    candidate = out / 'production'
    release_command = [sys.executable, 'agent_os/scripts/prepare_release_android.py', *common,
                       '--expected-signer', args.expected_signer, '--previous-apk', args.previous_apk, '--output-dir', candidate]
    if args.model_runtime: release_command += ['--model-runtime', args.model_runtime]
    if args.sync_runtime: release_command += ['--sync-runtime', args.sync_runtime]
    execute(release_command)
    manifest = ROOT / 'agent_os/tests/android/manifest-documents.xml'
    target_manifest = out / 'validation-target.xml'
    text = manifest.read_text()
    if text.count(SOURCE) != 2:
        raise RuntimeError('Unexpected validation manifest identity; refusing target build')
    target_manifest.write_text(text.replace(SOURCE, TARGET))
    extra_args = []
    for name in EXTRAS:
        extra_args.extend(['--extra-source', ROOT / ('agent_os/tests/android/' + name + '.java')])
    for name, target in (('source', manifest), ('target', target_manifest)):
        native_args = ['--native-lib', args.model_runtime] if args.model_runtime else []
        execute([sys.executable, 'agent_os/scripts/build_android.py', *common, '--manifest', target,
                 *extra_args, *native_args, '--output-dir', out / name])
    sys.path.insert(0, str(ROOT / 'agent_os/scripts'))
    from verify_release_android import apk_identity, verify
    for name, expected in (('source', SOURCE), ('target', TARGET)):
        identity = apk_identity(out / name / 'agent-os.apk', args.build_tools)
        if identity['package'] != expected or not identity['debuggable'] or identity['signer_sha256'] != args.expected_signer.lower():
            raise RuntimeError('Validation APK identity or signer did not match')
    if args.legacy_validation_apk:
        legacy_identity = apk_identity(args.legacy_validation_apk, args.build_tools)
        if legacy_identity['package'] != SOURCE or not legacy_identity['debuggable'] or legacy_identity['signer_sha256'] != args.expected_signer.lower():
            raise RuntimeError('Legacy validation APK identity or signer did not match')
    installed = []
    legacy_fixture = None
    try:
        if args.legacy_validation_apk:
            execute(adb + ['install', args.legacy_validation_apk])
            installed.append(SOURCE)
            execute([sys.executable, 'agent_os/scripts/validate_actions_android.py', '--adb', args.adb, '--serial', args.serial,
                     '--case', 'EXPORT_MIGRATION', '--output', out / 'legacy_migration_export.json'])
            legacy_fixture = {name: execute(adb + ['exec-out', 'run-as', SOURCE, 'cat', 'files/' + name], capture_output=True).stdout
                              for name in ('synthetic-migration.taos', 'migration-ids.json')}
            execute(adb + ['uninstall', SOURCE])
            installed.remove(SOURCE)
        for name, package in (('source', SOURCE), ('target', TARGET)):
            execute(adb + ['install', str(out / name / 'agent-os.apk')])
            installed.append(package)
        distinct_validation_uids(adb)
        command = [sys.executable, 'agent_os/scripts/validate_actions_android.py', '--adb', args.adb,
                   '--serial', args.serial]
        for case in CASES: command += ['--case', case]
        execute(command + ['--output', out / 'device_regression.json'])
        execute([sys.executable, 'agent_os/scripts/validate_actions_android.py', '--adb', args.adb, '--serial', args.serial,
                 '--case', 'EXPORT_MIGRATION', '--output', out / 'migration_export.json'])
        execute([sys.executable, 'agent_os/scripts/copy_document_validation_fixture.py', '--adb', args.adb, '--serial', args.serial])
        execute([sys.executable, 'agent_os/scripts/validate_actions_android.py', '--adb', args.adb, '--serial', args.serial,
                 '--package', TARGET, '--case', 'IMPORT_MIGRATION', '--output', out / 'migration_import.json'])
        execute([sys.executable, 'agent_os/scripts/validate_actions_android.py', '--adb', args.adb, '--serial', args.serial,
                 '--case', 'EXPORT_LIFECYCLE', '--output', out / 'lifecycle_export.json'])
        execute([sys.executable, 'agent_os/scripts/copy_document_validation_fixture.py', '--adb', args.adb, '--serial', args.serial])
        execute([sys.executable, 'agent_os/scripts/validate_actions_android.py', '--adb', args.adb, '--serial', args.serial,
                 '--package', TARGET, '--case', 'IMPORT_LIFECYCLE', '--output', out / 'lifecycle_import.json'])
        execute([sys.executable, 'agent_os/scripts/validate_actions_android.py', '--adb', args.adb, '--serial', args.serial,
                 '--case', 'EXPORT_SHARE_MIGRATION', '--output', out / 'share_migration_export.json'])
        for name in ('share-migration.taos', 'share-migration-ids.json'):
            payload = execute(adb + ['exec-out', 'run-as', SOURCE, 'cat', 'files/' + name], capture_output=True).stdout
            command = shlex.join(['run-as', TARGET, 'sh', '-c', 'cat > files/' + name])
            execute(adb + ['shell', command], input=payload)
        execute([sys.executable, 'agent_os/scripts/validate_actions_android.py', '--adb', args.adb, '--serial', args.serial,
                 '--package', TARGET, '--case', 'IMPORT_SHARE_MIGRATION', '--case', 'VERIFY_SHARE_RESTART',
                 '--output', out / 'share_migration_import.json'])
        if legacy_fixture:
            for name, payload in legacy_fixture.items():
                command = shlex.join(['run-as', TARGET, 'sh', '-c', 'cat > files/' + name])
                execute(adb + ['shell', command], input=payload)
            execute([sys.executable, 'agent_os/scripts/validate_actions_android.py', '--adb', args.adb, '--serial', args.serial,
                     '--package', TARGET, '--case', 'IMPORT_MIGRATION', '--output', out / 'legacy_migration_import.json'])
    finally:
        for package in reversed(installed):
            execute(adb + ['uninstall', package])
    report = json.loads((candidate / 'build_report.json').read_text())
    verification = verify(candidate / 'agent-os.apk', report, args.build_tools, args.expected_signer, args.previous_apk,
                          require_model_runtime=bool(args.model_runtime))
    result = {'candidate_sha256': verification['apk_sha256'], 'automated_device_cases': len(CASES),
              'cross_install_synthetic_archive_restore': True, 'distinct_validation_uids': True,
              'lifecycle_archive_restore': True, 'share_workflow_archive_restore': True,
              'legacy_archive_restore_run': legacy_fixture is not None,
              'validation_packages_removed': True,
              'manual_system_picker_ui_not_run': True, 'physical_power_trial_not_run': True,
              'model_runtime_included': verification['model_runtime_included'],
              'production_update_installed': False}
    if args.install:
        check_update_base(adb, args.previous_apk)
        execute(adb + ['install', '-r', candidate / 'agent-os.apk'])
        if hashlib.sha256(installed_apk(adb)).hexdigest() != verification['apk_sha256'] or installed_time(adb) != first_install:
            raise RuntimeError('Installed update identity or first-install time verification failed')
        result['production_update_installed'] = True
        result['first_install_time_preserved'] = True
    receipt.write_text(json.dumps(result, indent=2) + '\n')
    print('Automated candidate, device regression and cross-install archive restore passed')


if __name__ == '__main__':
    main()
