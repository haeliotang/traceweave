#!/usr/bin/env python3
"""Report whether the edge manifold daemon holds its single-instance lock.

Exit status: 0 = lock held (daemon running), 1 = lock free, 2 = indeterminate.

The daemon serializes itself with an flock on edge_manifold_daemon.lock. Unlike
process-table queries, a file lock is visible across Android SELinux domains: a
daemon started through ``adb run-as`` (runas_app) is invisible to ``pgrep`` run
by a Termux job (untrusted_app) yet still holds this lock.
"""
import fcntl
import sys


def main(argv):
    if len(argv) != 2:
        print("usage: daemon_lock_probe.py LOCK_FILE", file=sys.stderr)
        return 2
    try:
        handle = open(argv[1], "a")
    except OSError as error:
        print(f"lock file unavailable: {error}", file=sys.stderr)
        return 2
    with handle:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return 0
        except OSError as error:
            print(f"lock query failed: {error}", file=sys.stderr)
            return 2
        return 1  # closing the handle releases the probe's own lock


if __name__ == "__main__":
    sys.exit(main(sys.argv))
