#!/usr/bin/env python3
"""Rebuild the experimental ARM64 TLS participant from explicitly supplied dependencies."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk-root', type=Path, required=True)
    parser.add_argument('--emp-prefix', type=Path, required=True)
    parser.add_argument('--emp-tool-source', type=Path, required=True)
    parser.add_argument('--emp-ag2pc-source', type=Path, required=True)
    parser.add_argument('--openssl-source', type=Path, required=True)
    parser.add_argument('--openssl-build', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    include = args.emp_prefix / 'include'
    net = include / 'emp-tool/io/net_io_channel.h'
    text = net.read_text()
    if 'htonl(INADDR_ANY)' in text or 'htonl(INADDR_LOOPBACK)' not in text:
        raise ValueError('loopback-only EMP listener required')
    compiler = args.ndk_root / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++'
    sources = [root / 'native/private_alignment_probe/smoke.cpp', args.emp_tool_source / 'emp-tool/emp-tool.cpp']
    libraries = [args.openssl_build / 'libssl.a', args.openssl_build / 'libcrypto.a']
    args.output_dir.mkdir(parents=True, exist_ok=True)
    binary = args.output_dir / 'libtraceform_private_participant.so'
    command = [str(compiler), '-std=c++11', '-O3', '-pthread', '-DTRACEFORM_TLS_PROBE',
               '-march=armv8-a+simd+crypto+crc', '-static-libstdc++', '-Wl,-z,max-page-size=16384',
               '-I' + str(include), '-I' + str(args.emp_ag2pc_source),
               '-I' + str(args.openssl_build / 'include'), '-I' + str(args.openssl_source / 'include'), *map(str, sources),
               *map(str, libraries), '-ldl', '-o', str(binary)]
    subprocess.run(command, check=True)
    bound = [Path(__file__).resolve(), sources[0], root / 'native/private_alignment_probe/tls_io.h',
             root / 'native/private_alignment_probe/dependencies.json', sources[1], *libraries]
    # Hash all used dependency header trees, including the patched listener.
    headers = sorted(set(include.rglob('*.h')) | set((args.emp_ag2pc_source / 'emp-ag2pc').rglob('*.h'))
                     | set((args.openssl_build / 'include').rglob('*.h'))
                     | set((args.openssl_source / 'include').rglob('*.h')))
    report = {'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(), 'binary_bytes': binary.stat().st_size,
              'abi': 'arm64-v8a', 'android_api': 26, 'command': command,
              'source_and_dependency_sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*bound, *headers]},
              'scope': 'experimental_source_bound_TLS_participant_build_not_security_or_device_acceptance'}
    (args.output_dir / 'build_report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'binary': str(binary), 'binary_sha256': report['binary_sha256']}))


if __name__ == '__main__':
    main()
