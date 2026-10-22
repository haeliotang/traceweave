#!/usr/bin/env python3
"""Serve a one-time, USB-only archive of this Termux device's local data."""

from __future__ import annotations

import datetime
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import io
import json
import os
from pathlib import Path
import platform
import secrets
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import uuid

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEVICE_PORT = 18765
EXCLUDED_NAMES = {".device_fingerprint_salt", ".traceform_archive_device_id"}
EXCLUDED_SUFFIXES = (".lock", ".pid", ".log", ".tmp", ".part", "-wal", "-shm")
SQLITE_SUFFIXES = (".sqlite", ".sqlite3", ".db")


class HashingLimitedReader:
    """Read a fixed-size snapshot prefix while calculating its digest."""

    def __init__(self, source, size: int):
        self.source = source
        self.remaining = size
        self.bytes_read = 0
        self.digest = hashlib.sha256()

    def read(self, size: int = -1) -> bytes:
        if self.remaining == 0:
            return b""
        if size < 0 or size > self.remaining:
            size = self.remaining
        chunk = self.source.read(size)
        self.remaining -= len(chunk)
        self.bytes_read += len(chunk)
        self.digest.update(chunk)
        return chunk


def device_id() -> str:
    path = Path.home() / ".traceform_archive_device_id"
    if path.exists():
        return str(uuid.UUID(path.read_text(encoding="utf-8").strip()))

    value = str(uuid.uuid4())
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(value + "\n")
        output.flush()
        os.fsync(output.fileno())
    return value


def device_model() -> str:
    try:
        result = subprocess.run(
            ["getprop", "ro.product.model"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()[:128]
    except (OSError, subprocess.TimeoutExpired):
        pass
    return platform.machine()[:128] or "unknown"


def archive_sources():
    home = Path.home()
    candidates = [
        ("physical_ledger", home / ".pixel_sensorium"),
        ("daily_snapshots", home / "traceform_archive"),
        ("runtime_data", PROJECT_ROOT / "data"),
        ("field_telemetry", PROJECT_ROOT / "field_telemetry"),
    ]
    return [(name, path) for name, path in candidates if path.is_dir()]


def iter_files(root: Path, archive_prefix: str):
    for current, directories, filenames in os.walk(root, topdown=True, followlinks=False):
        current_path = Path(current)
        directories[:] = [
            name for name in directories
            if name not in {".git", "__pycache__"} and not (current_path / name).is_symlink()
        ]
        for filename in filenames:
            if filename in EXCLUDED_NAMES or filename.endswith(EXCLUDED_SUFFIXES):
                continue
            path = current_path / filename
            if path.is_symlink() or not path.is_file():
                continue
            relative = path.relative_to(root).as_posix()
            yield f"{archive_prefix}/{relative}", path


def make_sqlite_backup(source: Path, destination: Path) -> None:
    uri = source.resolve().as_uri() + "?mode=ro"
    source_db = sqlite3.connect(uri, uri=True, timeout=30)
    backup_db = sqlite3.connect(destination)
    try:
        source_db.backup(backup_db)
    finally:
        backup_db.close()
        source_db.close()


def add_bytes(archive: tarfile.TarFile, name: str, content: bytes, mtime: int) -> None:
    info = tarfile.TarInfo(name)
    info.size = len(content)
    info.mtime = mtime
    info.mode = 0o600
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    archive.addfile(info, io.BytesIO(content))


def build_archive(output_path: Path) -> dict:
    now = datetime.datetime.now(datetime.timezone.utc)
    archive_id = str(uuid.uuid4())
    current_device_id = device_id()
    files = []

    with tempfile.TemporaryDirectory(prefix="traceform-archive-") as scratch:
        scratch_path = Path(scratch)
        backup_dir = scratch_path / "sqlite"
        backup_dir.mkdir()

        with tarfile.open(output_path, mode="w:gz", compresslevel=3) as archive:
            backup_index = 0
            for prefix, root in archive_sources():
                for archive_name, source_path in iter_files(root, prefix):
                    source_for_archive = source_path
                    if prefix == "runtime_data" and source_path.name.endswith(SQLITE_SUFFIXES):
                        backup_index += 1
                        source_for_archive = backup_dir / f"{backup_index}.sqlite"
                        make_sqlite_backup(source_path, source_for_archive)

                    size = source_for_archive.stat().st_size
                    info = tarfile.TarInfo(archive_name)
                    info.size = size
                    info.mtime = int(source_path.stat().st_mtime)
                    info.mode = 0o600
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    with source_for_archive.open("rb") as source:
                        reader = HashingLimitedReader(source, size)
                        archive.addfile(info, reader)
                    if reader.bytes_read != size:
                        raise OSError(f"File changed while being archived: {archive_name}")
                    files.append({
                        "path": archive_name,
                        "size": size,
                        "sha256": reader.digest.hexdigest(),
                    })

            if not files:
                raise RuntimeError("No device data found to archive.")

            manifest = {
                "format_version": 1,
                "archive_id": archive_id,
                "device_id": current_device_id,
                "device_model": device_model(),
                "created_at_utc": now.isoformat(timespec="seconds"),
                "file_count": len(files),
                "total_bytes": sum(item["size"] for item in files),
                "files": files,
            }
            add_bytes(
                archive,
                "manifest.json",
                json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8"),
                int(now.timestamp()),
            )
    return manifest


class ArchiveHandler(BaseHTTPRequestHandler):
    archive_path: Path
    transfer_token: str

    def do_GET(self):
        if self.path != f"/archive/{self.transfer_token}":
            self.send_error(404)
            return
        size = self.archive_path.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", "application/gzip")
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        with self.archive_path.open("rb") as archive:
            shutil.copyfileobj(archive, self.wfile, length=1024 * 1024)

    def log_message(self, _format, *_args):
        return


def main() -> int:
    try:
        if Path.home() != Path("/data/data/com.termux/files/home"):
            raise RuntimeError("Run this exporter from the Traceform Termux checkout on the phone.")
        with tempfile.TemporaryDirectory(prefix="traceform-device-export-") as scratch:
            archive_path = Path(scratch) / "device-archive.tar.gz"
            manifest = build_archive(archive_path)
            transfer_token = secrets.token_urlsafe(32)
            handler = type(
                "DeviceArchiveHandler",
                (ArchiveHandler,),
                {"archive_path": archive_path, "transfer_token": transfer_token},
            )
            server = HTTPServer(("127.0.0.1", DEVICE_PORT), handler)
            server.timeout = 15 * 60
            print(f"Device ID: {manifest['device_id']}")
            print(f"Archive size: {archive_path.stat().st_size} bytes")
            print(f"One-time transfer token: {transfer_token}")
            print(f"USB archive endpoint ready on device port {DEVICE_PORT}; start the computer-side archive command.")
            try:
                server.handle_request()
            finally:
                server.server_close()
            print("Archive transfer finished or timed out. The temporary device archive was removed; source data remains.")
        return 0
    except (OSError, RuntimeError, sqlite3.Error, tarfile.TarError, ValueError) as error:
        print(f"Archive export failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
