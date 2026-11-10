#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Build the public-only Android verifier offline from the locked Rust crate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rustc', type=Path, required=True)
    parser.add_argument('--ndk-root', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    crate = root / 'native/zk_alignment'
    compiler = args.ndk_root / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang'
    environment = dict(os.environ, RUSTC=str(args.rustc),
        CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=str(compiler),
        CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384')
    subprocess.run(['cargo', 'build', '--locked', '--offline', '--release', '--target', 'aarch64-linux-android',
                    '--manifest-path', str(crate / 'Cargo.toml'), '--bin', 'verify'], check=True, env=environment)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    binary = args.output_dir / 'libtraceform_zk_verify.so'
    shutil.copyfile(crate / 'target/aarch64-linux-android/release/verify', binary)
    binary.chmod(0o755)
    sources = [Path(__file__).resolve(), crate / 'Cargo.toml', crate / 'Cargo.lock', *sorted((crate / 'src').rglob('*.rs'))]
    report = {'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(), 'binary_bytes': binary.stat().st_size,
              'rustc_version': subprocess.run([str(args.rustc), '--version', '--verbose'], check=True, capture_output=True, text=True).stdout,
              'compiler_sha256': hashlib.sha256(args.rustc.read_bytes()).hexdigest(),
              'abi': 'arm64-v8a', 'android_api': 26, 'offline_locked': True,
              'source_sha256': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
              'scope': 'public_only_verifier_build_not_private_prover_owner_enrollment_or_device_acceptance'}
    (args.output_dir / 'build_report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'binary': str(binary), 'binary_sha256': report['binary_sha256']}))


if __name__ == '__main__':
    main()
