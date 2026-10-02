"""Bounded durable JSONL segments and an inode-aware live reader."""

from contextlib import contextmanager
import datetime
import fcntl
from functools import lru_cache
import json
import os
from pathlib import Path
import time


def _record_timestamp(record, field):
    value = datetime.datetime.fromisoformat(record[field].replace('Z', '+00:00'))
    if value.tzinfo is None:
        raise ValueError("timezone-aware record timestamp required")
    return value.timestamp()


@lru_cache(maxsize=256)
def _oldest_record_time(path, inode, size, mtime_ns, field):
    oldest = float('inf')
    with Path(path).open('rb') as source:
        for line in source:
            try:
                timestamp = _record_timestamp(json.loads(line), field)
            except (ValueError, KeyError, TypeError, AttributeError):
                timestamp = mtime_ns / 1e9
            oldest = min(oldest, timestamp)
    return oldest


class RollingLedger:
    def __init__(self, path, max_bytes=64 * 1024 * 1024, retention_seconds=48 * 3600, timestamp_field=None):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if max_bytes < 1 or retention_seconds <= 0:
            raise ValueError("positive segment byte limit and retention required")
        self.max_bytes = max_bytes
        self.retention_seconds = retention_seconds
        self.timestamp_field = timestamp_field

    @contextmanager
    def lock(self):
        with self.path.with_name(self.path.name + '.lock').open('a') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            yield

    def append(self, record, now=None):
        now = now or datetime.datetime.now(datetime.timezone.utc)
        if now.tzinfo is None:
            raise ValueError("timezone-aware append time required")
        instant = now.timestamp()
        if self.timestamp_field is not None:
            self._record_timestamp(record)
        line = (json.dumps(record, separators=(',', ':'), allow_nan=False) + '\n').encode()
        if len(line) > self.max_bytes:
            raise ValueError("record exceeds segment byte limit")
        with self.lock():
            if self.path.exists():
                info = self.path.stat()
                incomplete = False
                if info.st_size:
                    with self.path.open('rb') as source:
                        source.seek(-1, os.SEEK_END)
                        incomplete = source.read(1) != b'\n'
                if info.st_size and (incomplete or info.st_size + len(line) > self.max_bytes or int(info.st_mtime // 3600) != int(instant // 3600)):
                    previous = max((int(path.name.rsplit('.', 1)[1]) for path in self.path.parent.glob(self.path.name + '.segment.*')), default=0)
                    generation = max(time.time_ns(), previous + 1)
                    segment = self.path.with_name(self.path.name + f'.segment.{generation:020d}')
                    os.rename(self.path, segment)
            with self.path.open('ab') as output:
                output.write(line)
                output.flush()
                os.fsync(output.fileno())
                os.utime(output.fileno(), (instant, instant))
            self._sync_directory()
            self._compact(instant)

    def _sync_directory(self):
        descriptor = os.open(self.path.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    def _record_timestamp(self, record):
        return _record_timestamp(record, self.timestamp_field)

    def _trim_records(self, path, cutoff):
        info = path.stat()
        if _oldest_record_time(str(path), info.st_ino, info.st_size, info.st_mtime_ns, self.timestamp_field) >= cutoff:
            return False
        def retained(line):
            try:
                return self._record_timestamp(json.loads(line)) >= cutoff
            except (ValueError, KeyError, TypeError, AttributeError):
                # Incomplete crash evidence has no trustworthy record time.
                return info.st_mtime >= cutoff
        temporary = self.path.with_name('.' + self.path.name + '.retention.tmp')
        try:
            with path.open('rb') as source, temporary.open('wb') as output:
                for line in source:
                    if retained(line):
                        output.write(line)
                output.flush()
                os.chmod(temporary, info.st_mode & 0o777)
                os.utime(temporary, (info.st_atime, info.st_mtime))
                os.fsync(output.fileno())
            if temporary.stat().st_size == 0 and path != self.path:
                path.unlink()
                deleted = True
            else:
                os.replace(temporary, path)
                deleted = False
            self._sync_directory()
            return deleted
        finally:
            temporary.unlink(missing_ok=True)

    def _compact(self, instant):
        temporary = self.path.with_name('.' + self.path.name + '.retention.tmp')
        if temporary.exists():
            temporary.unlink()
            self._sync_directory()
        deleted = 0
        paths = list(self.path.parent.glob(self.path.name + '.segment.*'))
        if self.timestamp_field is not None and self.path.exists():
            paths.append(self.path)
        for segment in paths:
            if self.timestamp_field is not None:
                deleted += int(self._trim_records(segment, instant - self.retention_seconds))
            elif segment.stat().st_mtime < instant - self.retention_seconds:
                segment.unlink()
                deleted += 1
        if deleted:
            self._sync_directory()
        return deleted

    def compact(self, now):
        if now.tzinfo is None:
            raise ValueError("timezone-aware compaction time required")
        with self.lock():
            if self.timestamp_field is None and self.path.exists() and self.path.stat().st_mtime < now.timestamp() - self.retention_seconds:
                # No writer has an open handle outside this lock; a reader may
                # retain the old inode and will mark the missing segment.
                self.path.unlink()
                self.path.touch()
                with self.path.open('rb') as source:
                    os.fsync(source.fileno())
                self._sync_directory()
            return self._compact(now.timestamp())


class RotationAwareTail:
    def __init__(self, path, follow=False):
        self.path = Path(path)
        self.ledger = RollingLedger(path)
        with self.ledger.lock():
            self.handle = self.path.open('r', encoding='utf-8')
        self.discontinuous = False
        self.last_line = None
        if follow:
            self.handle.seek(0, os.SEEK_END)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.handle.close()

    def read_record(self):
        line = self.read_line()
        return json.loads(line) if line is not None else None

    def read_line(self):
        with self.ledger.lock():
            return self._read_line()

    def _read_line(self):
        while True:
            inode = os.fstat(self.handle.fileno()).st_ino
            if not self.path.exists() or self.path.stat().st_ino != inode:
                segments = sorted(self.path.parent.glob(self.path.name + '.segment.*'))
                if not any(path.stat().st_ino == inode for path in segments):
                    # Retention replaced/deleted this inode. Do not drain expired
                    # bytes through an otherwise still-valid open descriptor.
                    self.discontinuous = True
                    self.handle.close()
                    next_path = segments[0] if segments else self.path
                    self.handle = next_path.open('r', encoding='utf-8')
                    if self.last_line is not None:
                        while True:
                            candidate = self.handle.readline()
                            if candidate == self.last_line:
                                break
                            if not candidate:
                                self.handle.seek(0)
                                break
            position = self.handle.tell()
            line = self.handle.readline()
            if line.endswith('\n'):
                self.last_line = line
                return line
            self.handle.seek(position)
            inode = os.fstat(self.handle.fileno()).st_ino
            if not self.path.exists() or self.path.stat().st_ino == inode:
                return None
            segments = sorted(self.path.parent.glob(self.path.name + '.segment.*'))
            index = next((index for index, path in enumerate(segments) if path.stat().st_ino == inode), None)
            if index is None:
                self.discontinuous = True
                next_path = segments[0] if segments else self.path
            else:
                next_path = segments[index + 1] if index + 1 < len(segments) else self.path
            if line:
                self.discontinuous = True  # rotated, incomplete record cannot be recovered
            self.handle.close()
            self.handle = next_path.open('r', encoding='utf-8')
