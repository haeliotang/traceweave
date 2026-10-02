#!/usr/bin/env python3
"""Bounded bootstrap retry for a synthetic TLS client over ADB forwarding.

ADB may accept TCP before the remote listener exists. Retry only the initial
unexpected EOF, before the pinned backend prints its MPC circuit dimensions.
Certificate/pin rejection and any failure after MPC setup are terminal.
"""
import subprocess
import sys
import time


def main(bootstrap_seconds=3):
    if not isinstance(bootstrap_seconds, int) or not 1 <= bootstrap_seconds <= 90:
        raise ValueError('bootstrap retry window must be between 1 and 90 seconds')
    if len(sys.argv) < 2:
        raise ValueError('participant command required')
    data = sys.stdin.buffer.read(11602)
    if len(data) > 11601:
        return 2
    deadline = time.monotonic() + bootstrap_seconds
    while True:
        process = subprocess.run(sys.argv[1:], input=data, capture_output=True, timeout=120)
        transient = (process.returncode == 1 and process.stdout == b'connected\n'
                     and b'TLS handshake rejected:' in process.stderr
                     and b'unexpected eof while reading' in process.stderr)
        if not transient or time.monotonic() >= deadline:
            sys.stdout.buffer.write(process.stdout)
            sys.stderr.buffer.write(process.stderr)
            return process.returncode


if __name__ == '__main__':
    raise SystemExit(main())
