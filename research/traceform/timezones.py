"""Time zone lookup that does not depend on the caller having set PYTHONTZPATH.

Termux ships no system zoneinfo; the deployment installs the TZif files it needs under its own
prefix. A process started from a shell without PYTHONTZPATH (for example through adb run-as) then
fails at the first ZoneInfo(...) call, which for the field daemon is the first real intervention
decision. Resolution therefore also looks in the known install directories.
"""
from __future__ import annotations

from pathlib import Path, PurePosixPath
from typing import Iterable, Optional
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

EXTRA_ZONEINFO_DIRS = ('/data/data/com.termux/files/usr/share/zoneinfo',)


def resolve_zone(name: str, extra_dirs: Optional[Iterable[str]] = None) -> ZoneInfo:
    parts = PurePosixPath(name).parts if isinstance(name, str) else ()
    if not parts or name.startswith('/') or any(part in ('..', '.') for part in parts):
        raise ValueError(f'invalid time zone name: {name!r}')
    try:
        return ZoneInfo(name)
    except ZoneInfoNotFoundError:
        pass
    for directory in (EXTRA_ZONEINFO_DIRS if extra_dirs is None else extra_dirs):
        candidate = Path(directory) / name
        if candidate.is_file():
            with candidate.open('rb') as handle:
                return ZoneInfo.from_file(handle, key=name)
    raise ZoneInfoNotFoundError(f'No time zone found with key {name}')
