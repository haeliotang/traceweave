#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Prepare a same-signer debug recovery APK; never install it or access app data."""
import argparse
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
from build_scoped_edge_update import ANDROID, PACKAGE, ROOT, apk_identity, digest, manifest_nodes, run
from build_edge_hardened_candidate import verify_hardened_manifest


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('baseline-apk', 'android-jar', 'build-tools', 'keystore', 'output-dir'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--expected-signer', required=True)
    p.add_argument('--key-alias', default='traceform')
    p.add_argument('--password-env', default='TRACEFORM_EDGE_KEYSTORE_PASSWORD')
    args = p.parse_args()
    baseline, out, tools = args.baseline_apk.resolve(), args.output_dir.resolve(), args.build_tools.resolve()
    if not baseline.is_file() or baseline.is_relative_to(out): p.error('Keep a separate baseline APK')
    if not os.environ.get(args.password_env): p.error('Set the named signing password environment variable')
    old = apk_identity(baseline, tools)
    if old['package'] != PACKAGE or old['signer_sha256'] != args.expected_signer.lower() or old['debuggable']:
        raise ValueError('Baseline must be the trusted non-debuggable Edge APK')
    baseline_hash = digest(baseline.read_bytes())
    out.mkdir(parents=True, exist_ok=True)
    (out / 'candidate_report.json').unlink(missing_ok=True)
    ET.register_namespace('android', ANDROID[1:-1])
    tree = ET.parse(ROOT / 'android/AndroidManifest.xml'); root = tree.getroot(); app = root.find('application')
    root.set(ANDROID + 'versionCode', str(old['version_code'] + 1))
    root.set(ANDROID + 'versionName', old['version'] + '-recovery')
    app.set(ANDROID + 'debuggable', 'true')
    manifest = out / 'manifest.xml'; tree.write(manifest, encoding='utf-8', xml_declaration=True)
    unsigned, aligned, candidate = out / 'unsigned.apk', out / 'aligned.apk', out / 'edge-recovery.apk'
    run([tools / 'aapt2', 'link', '-I', args.android_jar, '--manifest', manifest, '-o', unsigned])
    with zipfile.ZipFile(baseline) as original, zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as result:
        names = original.namelist()
        if len(names) != len(set(names)): raise ValueError('Duplicate baseline APK entries')
        if original.read('resources.arsc') != result.read('resources.arsc'): raise ValueError('Resource table changed')
        for item in original.infolist():
            if item.filename not in ('AndroidManifest.xml', 'resources.arsc') and not item.filename.startswith('META-INF/'):
                result.writestr(item, original.read(item))
    run([tools / 'zipalign', '-f', '-p', '4', unsigned, aligned])
    run([tools / 'apksigner', 'sign', '--ks', args.keystore, '--ks-key-alias', args.key_alias,
         '--ks-pass', 'env:' + args.password_env, '--out', candidate, aligned])
    new = apk_identity(candidate, tools)
    if new['package'] != PACKAGE or new['signer_sha256'] != old['signer_sha256'] or new['version_code'] != old['version_code'] + 1 or not new['debuggable']:
        raise ValueError('Recovery candidate identity is incorrect')
    verify_hardened_manifest(manifest_nodes(candidate, tools), manifest_nodes(baseline, tools))
    with zipfile.ZipFile(baseline) as original, zipfile.ZipFile(candidate) as result:
        old_names = {n for n in original.namelist() if not n.startswith('META-INF/')}
        new_names = {n for n in result.namelist() if not n.startswith('META-INF/')}
        if old_names != new_names or len(result.namelist()) != len(set(result.namelist())): raise ValueError('Unexpected APK entries')
        if any(original.read(n) != result.read(n) for n in old_names if n != 'AndroidManifest.xml'): raise ValueError('Existing code or resources changed')
    if digest(baseline.read_bytes()) != baseline_hash: raise ValueError('Baseline changed during build')
    report = {'scope': 'temporary_debug_recovery_candidate_only_not_installed', 'baseline_sha256': baseline_hash,
              'candidate_sha256': digest(candidate.read_bytes()), 'same_package_and_signer': True,
              'version_code': new['version_code'], 'debuggable': new['debuggable'],
              'existing_code_and_resources_identical': True, 'only_version_and_debug_changed': True,
              'restore_tested': False, 'installed': False}
    (out / 'candidate_report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Debug recovery candidate built; not installed and restoration not tested')


if __name__ == '__main__':
    main()
