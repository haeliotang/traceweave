"""One local-day snapshot of the edge store, kept for a fixed number of days.

The hot tier holds only 48 hours of raw frames, so later evaluations over several independent days
need copies. A snapshot is a consistent SQLite online backup of the whole store, written beside the
store on the device, verified, and never exported. Intended to run from the hourly maintenance job:
it does nothing after the first successful snapshot of the local day.
"""
from __future__ import annotations

import datetime
import hashlib
import json
import os
import re
import shutil
import sqlite3
from pathlib import Path
from typing import Callable, Dict, Optional

PATTERN = re.compile(r'^edge_storage_snapshot_(\d{8}T\d{6})\.sqlite$')
MANIFEST = 'MANIFEST.jsonl'
SPACE_FACTOR = 2.5  # free space required, as a multiple of the store size


def _stamp(name: str) -> Optional[datetime.datetime]:
    match = PATTERN.match(name)
    if not match:
        return None
    return datetime.datetime.strptime(match.group(1), '%Y%m%dT%H%M%S').replace(tzinfo=datetime.timezone.utc)


def _snapshots(archive: Path):
    found = [(path, _stamp(path.name)) for path in archive.glob('edge_storage_snapshot_*.sqlite')]
    return sorted(((path, stamp) for path, stamp in found if stamp is not None), key=lambda item: item[1])


def _store_size(database: Path) -> int:
    return sum(path.stat().st_size for path in (database, Path(str(database) + '-wal')) if path.exists())


def _clear_temporary(archive: Path) -> None:
    for suffix in ('', '-wal', '-shm', '-journal'):
        (archive / ('.snapshot.tmp' + suffix)).unlink(missing_ok=True)


def _default_verify(path: Path, rows: int) -> bool:
    connection = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
    try:
        return rows > 0 and connection.execute('PRAGMA quick_check').fetchone()[0] == 'ok' \
            and connection.execute('SELECT COUNT(*) FROM hot').fetchone()[0] == rows
    finally:
        connection.close()


def _prune(archive: Path, now: datetime.datetime, keep_days: int) -> list:
    snapshots = _snapshots(archive)
    cutoff = now - datetime.timedelta(days=keep_days)
    removed = []
    for path, stamp in snapshots[:-1]:  # the newest snapshot is always kept
        if stamp < cutoff:
            path.unlink()
            removed.append(path.name)
    return removed


def snapshot_if_due(database, archive_dir, now: datetime.datetime, zone, keep_days: int = 14,
                    free_bytes: Optional[Callable[[Path], int]] = None,
                    verify: Optional[Callable[[Path, int], bool]] = None) -> Dict[str, object]:
    database, archive = Path(database), Path(archive_dir)
    archive.mkdir(parents=True, exist_ok=True)
    local_day = now.astimezone(zone).date()
    if any(stamp.astimezone(zone).date() == local_day for _, stamp in _snapshots(archive)):
        return {'status': 'already_taken_today', 'pruned': _prune(archive, now, keep_days)}
    source = sqlite3.connect(f'file:{database}?mode=ro', uri=True)
    temporary = archive / '.snapshot.tmp'
    try:
        rows = source.execute('SELECT COUNT(*) FROM hot').fetchone()[0]
        if rows == 0:
            return {'status': 'skipped_empty_hot_tier', 'pruned': []}
        available = (free_bytes or (lambda path: shutil.disk_usage(path).free))(archive)
        if available < SPACE_FACTOR * _store_size(database):
            return {'status': 'skipped_low_disk_space', 'free_bytes': available, 'pruned': []}
        _clear_temporary(archive)  # a stale write-ahead log must never attach to the new copy
        destination = sqlite3.connect(temporary)
        try:
            source.backup(destination)
            # The copy inherits the store's WAL mode; make it one self-contained file instead.
            destination.execute('PRAGMA journal_mode=DELETE')
            copied = destination.execute('SELECT COUNT(*) FROM hot').fetchone()[0]
        finally:
            destination.close()
        verified = (verify or _default_verify)(temporary, copied)
        for suffix in ('-wal', '-shm', '-journal'):
            (archive / ('.snapshot.tmp' + suffix)).unlink(missing_ok=True)
        if not verified:
            return {'status': 'failed_verification', 'pruned': []}
        with temporary.open('rb') as handle:
            os.fsync(handle.fileno())
        name = 'edge_storage_snapshot_' + now.astimezone(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S') + '.sqlite'
        final = archive / name
        os.replace(temporary, final)
        digest = hashlib.sha256(final.read_bytes()).hexdigest()
        with (archive / MANIFEST).open('a') as manifest:
            manifest.write(json.dumps({'file': name, 'sha256': digest, 'hot_rows': copied,
                                       'created_utc': name[len('edge_storage_snapshot_'):-len('.sqlite')],
                                       'scope': 'local_only_device_derived_raw_snapshot_never_exported'}) + '\n')
        return {'status': 'created', 'file': name, 'hot_rows': copied, 'bytes': final.stat().st_size,
                'pruned': _prune(archive, now, keep_days)}
    finally:
        source.close()
        _clear_temporary(archive)
