#!/usr/bin/env python3
"""Build isolated synthetic test APKs that preserve the supplied Edge APK code and assets."""
import argparse
import json
import os
from pathlib import Path
import re
import zipfile
from build_scoped_edge_update import ROOT, apk_identity, digest, run

PACKAGE = 'org.traceform.edge.applicationvalidation'


def verify_probe_classes(descriptors):
    if descriptors != {'Lorg/traceform/edge/EdgeApplicationRecoveryProbe;',
                       'Lorg/traceform/edge/EdgeApplicationRecoveryProbe$$ExternalSyntheticLambda0;'}:
        raise ValueError('Unexpected test DEX classes')


def probe_debuggable(version):
    if version not in (1, 2, 3, 4):
        raise ValueError('Unsupported probe version')
    return version in (1, 3)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('baseline-apk', 'compiled-classes', 'android-jar', 'build-tools', 'keystore', 'output-dir'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--expected-baseline-sha256', required=True)
    p.add_argument('--expected-signer', required=True)
    p.add_argument('--password-env', default='TRACEFORM_EDGE_KEYSTORE_PASSWORD')
    p.add_argument('--key-alias', default='traceform')
    a = p.parse_args(); baseline=a.baseline_apk.resolve(); out=a.output_dir.resolve(); tools=a.build_tools.resolve()
    if not os.environ.get(a.password_env):p.error('Set the signing password environment variable')
    if not baseline.is_file() or baseline.is_relative_to(out):p.error('Keep a separate baseline APK')
    identity=apk_identity(baseline,tools);baseline_hash=digest(baseline.read_bytes())
    if (identity['package']!='org.traceform.edge' or identity['signer_sha256']!=a.expected_signer.lower()
            or baseline_hash!=a.expected_baseline_sha256.lower()):raise ValueError('Untrusted Edge baseline')
    out.mkdir(parents=True,exist_ok=True)
    source=ROOT/'agent_os/tests/android/EdgeApplicationRecoveryProbe.java'
    source_hash=digest(source.read_bytes());classes=out/'classes';dex=out/'dex';classes.mkdir(exist_ok=True);dex.mkdir(exist_ok=True)
    run(['javac','--release','8','-classpath',str(a.android_jar.resolve())+os.pathsep+str(a.compiled_classes.resolve()),'-d',classes,source])
    compiled=classes/'org/traceform/edge/EdgeApplicationRecoveryProbe.class'
    run([tools/'d8','--min-api','26','--lib',a.android_jar,'--classpath',a.compiled_classes,'--output',dex,compiled])
    dump=run([tools/'dexdump','-f',dex/'classes.dex'],capture_output=True,text=True).stdout
    verify_probe_classes(set(re.findall(r"Class descriptor\s+: '([^']+)'",dump)))
    results=[]
    for version in (1,2,3,4):
        manifest=out/f'manifest-{version}.xml'
        debug=' android:debuggable="true"' if probe_debuggable(version) else ''
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}" android:versionCode="{version}" android:versionName="{version}.0"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35"/><application android:label="Isolated Edge application recovery" android:allowBackup="false" android:extractNativeLibs="true"{debug}><activity android:name="org.traceform.edge.EdgeApplicationRecoveryProbe" android:exported="true"/></application></manifest>''')
        unsigned=out/f'unsigned-{version}.apk';aligned=out/f'aligned-{version}.apk';apk=out/f'probe-{version}.apk'
        run([tools/'aapt2','link','-I',a.android_jar,'--manifest',manifest,'-o',unsigned])
        with zipfile.ZipFile(baseline) as original,zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as target:
            if len(original.namelist())!=len(set(original.namelist())) or 'classes3.dex' in original.namelist():raise ValueError('Unexpected baseline entries')
            for item in original.infolist():
                if item.filename not in ('AndroidManifest.xml','resources.arsc') and not item.filename.startswith('META-INF/'):
                    target.writestr(item,original.read(item))
            target.write(dex/'classes.dex','classes3.dex')
        run([tools/'zipalign','-f','-p','4',unsigned,aligned])
        run([tools/'apksigner','sign','--ks',a.keystore,'--ks-key-alias',a.key_alias,'--ks-pass','env:'+a.password_env,'--out',apk,aligned])
        info=apk_identity(apk,tools)
        if info['package']!=PACKAGE or info['signer_sha256']!=identity['signer_sha256'] or info['version_code']!=version or info['debuggable']!=probe_debuggable(version):raise ValueError('Invalid test APK identity')
        with zipfile.ZipFile(baseline) as original,zipfile.ZipFile(apk) as target:
            for item in original.namelist():
                if item not in ('AndroidManifest.xml','resources.arsc') and not item.startswith('META-INF/') and original.read(item)!=target.read(item):raise ValueError('Baseline code or assets changed')
        results.append({'version':version,'debuggable':info['debuggable'],'apk_sha256':digest(apk.read_bytes())})
    if digest(baseline.read_bytes())!=baseline_hash or digest(source.read_bytes())!=source_hash:raise ValueError('Source changed during build')
    (out/'build_report.json').write_text(json.dumps({'scope':'synthetic_isolated_application_probe_not_production','baseline_sha256':baseline_hash,'probe_source_sha256':source_hash,'variants':results},indent=2)+'\n')
    print('Isolated application recovery probes built; no installation or personal data access')


if __name__=='__main__':main()
