#!/usr/bin/env python3
"""Build the public-only proof verifier with an isolated official Rust toolchain."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rust-root', type=Path, required=True)
    parser.add_argument('--ndk-root', type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parent.parent
    rust_root = args.rust_root.resolve()
    compiler = rust_root / 'bin/rustc'
    linker = args.ndk_root.resolve() / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang'
    if not compiler.is_file() or not linker.is_file():
        parser.error('official compiler and Android NDK linker required')
    environment = dict(os.environ, RUSTC=str(compiler),
                       RUSTFLAGS=f'--sysroot={rust_root}',
                       CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=str(linker))
    package = project / 'native/zk_alignment'
    subprocess.run(['cargo', 'build', '--locked', '--offline', '--release',
                    '--target', 'aarch64-linux-android', '--manifest-path',
                    str(package / 'Cargo.toml'), '--bin', 'verify'],
                   env=environment, check=True)
    binary = package / 'target/aarch64-linux-android/release/verify'
    sources = list((package / 'src').rglob('*.rs')) + [package / 'Cargo.toml', package / 'Cargo.lock']
    report = {'binary': str(binary), 'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
              'sources': {str(path.relative_to(project)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
              'rustc': subprocess.check_output([str(compiler), '--version'], text=True).strip(),
              'target': 'aarch64-linux-android',
              'scope': 'public_proof_verifier_build_not_device_validation'}
    print(json.dumps(report))


if __name__ == '__main__':
    main()
