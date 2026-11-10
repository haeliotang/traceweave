#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Fetch a Termux device archive over an authorized USB ADB connection."""

from __future__ import annotations

import argparse
import datetime
import getpass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import tarfile
import tempfile
import urllib.error
import urllib.request
import uuid

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ARCHIVE_ROOT = PROJECT_ROOT / "local_device_archives"
DEVICE_PORT = 18765
MAX_ARCHIVE_BYTES = 30 * 1024**3
MAX_UNPACKED_BYTES = 50 * 1024**3
MAX_FILE_BYTES = 25 * 1024**3
MAX_FILES = 100_000
MAX_MANIFEST_BYTES = 16 * 1024**2
CHUNK_SIZE = 1024 * 1024


def adb_devices(adb_path: str) -> list[tuple[str, str, bool]]:
    result = subprocess.run(
        [adb_path, "devices", "-l"], capture_output=True, text=True, check=True, timeout=15
    )
    devices = []
    for line in result.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2:
            devices.append((fields[0], fields[1], any(field.startswith("usb:") for field in fields[2:])))
    return devices


def select_usb_device(adb_path: str, requested_serial: str | None) -> str:
    devices = adb_devices(adb_path)
    usb_devices = [serial for serial, state, is_usb in devices if state == "device" and is_usb]
    if requested_serial:
        selected = next((item for item in devices if item[0] == requested_serial), None)
        if selected is None or selected[1] != "device":
            raise RuntimeError(f"ADB device is not ready: {requested_serial}")
        if not selected[2]:
            raise RuntimeError("Only USB-connected devices are supported for local data archiving.")
        return requested_serial
    if len(usb_devices) == 1:
        return usb_devices[0]
    if not usb_devices:
        raise RuntimeError("No authorized USB ADB device found. Connect the phone by USB and authorize this computer.")
    raise RuntimeError("More than one USB ADB device is connected; select one with --serial.")


def free_local_port() -> int:
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        return server.getsockname()[1]


def download_archive(adb_path: str, serial: str, transfer_token: str, temporary_path: Path) -> None:
    local_port = free_local_port()
    subprocess.run(
        [adb_path, "-s", serial, "forward", f"tcp:{local_port}", f"tcp:{DEVICE_PORT}"],
        capture_output=True,
        text=True,
        check=True,
        timeout=15,
    )
    try:
        request = urllib.request.Request(
            f"http://127.0.0.1:{local_port}/archive/{transfer_token}",
            headers={"Accept": "application/gzip"},
        )
        with urllib.request.urlopen(request, timeout=300) as response, temporary_path.open("wb") as output:
            content_length = response.headers.get("Content-Length")
            if content_length and int(content_length) > MAX_ARCHIVE_BYTES:
                raise RuntimeError("Device archive exceeds the 30 GiB transfer limit.")
            total = 0
            while True:
                chunk = response.read(CHUNK_SIZE)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_ARCHIVE_BYTES:
                    raise RuntimeError("Device archive exceeds the 30 GiB transfer limit.")
                output.write(chunk)
    finally:
        subprocess.run(
            [adb_path, "-s", serial, "forward", "--remove", f"tcp:{local_port}"],
            capture_output=True,
            text=True,
            check=False,
            timeout=15,
        )


def checked_archive_path(name: str) -> PurePosixPath:
    if not isinstance(name, str):
        raise ValueError("Archive paths must be strings.")
    path = PurePosixPath(name)
    if (
        not name
        or "\\" in name
        or path.is_absolute()
        or path.as_posix() != name
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        raise ValueError(f"Unsafe archive path: {name!r}")
    return path


def parse_uuid(value, field: str) -> str:
    try:
        return str(uuid.UUID(value))
    except (ValueError, TypeError, AttributeError) as error:
        raise ValueError(f"Invalid {field} in archive manifest.") from error


def validate_archive(archive_path: Path) -> dict:
    with tarfile.open(archive_path, mode="r:gz") as archive:
        members = archive.getmembers()
        if len(members) > MAX_FILES + 1:
            raise ValueError("Archive contains too many files.")
        member_by_name = {}
        expanded_size = 0
        for member in members:
            checked_archive_path(member.name)
            if member.name in member_by_name:
                raise ValueError(f"Duplicate archive entry: {member.name}")
            if not member.isfile() or member.size < 0 or member.size > MAX_FILE_BYTES:
                raise ValueError(f"Unsupported archive entry: {member.name}")
            expanded_size += member.size
            if expanded_size > MAX_UNPACKED_BYTES:
                raise ValueError("Unpacked archive exceeds the 50 GiB verification limit.")
            member_by_name[member.name] = member

        manifest_member = member_by_name.get("manifest.json")
        if manifest_member is None or manifest_member.size > MAX_MANIFEST_BYTES:
            raise ValueError("Archive manifest is missing or too large.")
        manifest_file = archive.extractfile(manifest_member)
        if manifest_file is None:
            raise ValueError("Unable to read archive manifest.")
        manifest = json.loads(manifest_file.read(MAX_MANIFEST_BYTES + 1).decode("utf-8"))
        if not isinstance(manifest, dict):
            raise ValueError("Archive manifest must be a JSON object.")

        if manifest.get("format_version") != 1:
            raise ValueError("Unsupported device archive format.")
        device_id = parse_uuid(manifest.get("device_id"), "device_id")
        archive_id = parse_uuid(manifest.get("archive_id"), "archive_id")
        declared_files = manifest.get("files")
        if not isinstance(declared_files, list) or len(declared_files) > MAX_FILES:
            raise ValueError("Invalid archive file list.")
        declared_by_name = {}
        declared_total = 0
        for item in declared_files:
            if not isinstance(item, dict):
                raise ValueError("Invalid file record in archive manifest.")
            name = item.get("path")
            checked_archive_path(name)
            size = item.get("size")
            digest = item.get("sha256")
            if isinstance(size, bool) or not isinstance(size, int) or size < 0 or size > MAX_FILE_BYTES:
                raise ValueError(f"Invalid file size in manifest: {name}")
            if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError(f"Invalid SHA-256 in manifest: {name}")
            if name in declared_by_name or name == "manifest.json":
                raise ValueError(f"Duplicate or reserved manifest path: {name}")
            declared_by_name[name] = (size, digest)
            declared_total += size

        archive_data_names = set(member_by_name) - {"manifest.json"}
        if archive_data_names != set(declared_by_name):
            raise ValueError("Archive entries do not match the manifest.")
        if manifest.get("file_count") != len(declared_files) or manifest.get("total_bytes") != declared_total:
            raise ValueError("Archive totals do not match the manifest.")

        for name, (expected_size, expected_digest) in declared_by_name.items():
            member_file = archive.extractfile(member_by_name[name])
            if member_file is None:
                raise ValueError(f"Unable to read archive entry: {name}")
            digest = hashlib.sha256()
            actual_size = 0
            while chunk := member_file.read(CHUNK_SIZE):
                actual_size += len(chunk)
                digest.update(chunk)
            if actual_size != expected_size or digest.hexdigest() != expected_digest:
                raise ValueError(f"Archive file verification failed: {name}")

        timestamp = manifest.get("created_at_utc")
        if not isinstance(timestamp, str):
            raise ValueError("Archive timestamp is missing.")
        parsed_time = datetime.datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        safe_timestamp = parsed_time.astimezone(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        model = manifest.get("device_model")
        if not isinstance(model, str) or len(model) > 128:
            raise ValueError("Invalid device model in manifest.")

    return {"device_id": device_id, "archive_id": archive_id, "timestamp": safe_timestamp, "manifest": manifest}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB serial when multiple USB devices are connected")
    args = parser.parse_args()

    temporary_path = None
    try:
        adb_path = shutil.which("adb")
        if not adb_path:
            raise RuntimeError("Android platform-tools (adb) are required on this computer.")
        serial = select_usb_device(adb_path, args.serial)
        transfer_token = getpass.getpass("One-time token shown in Termux: ").strip()
        if not transfer_token:
            raise RuntimeError("A one-time token is required.")
        ARCHIVE_ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(ARCHIVE_ROOT, 0o700)
        with tempfile.NamedTemporaryFile(
            prefix=".incoming-", suffix=".tar.gz", dir=ARCHIVE_ROOT, delete=False
        ) as temporary:
            temporary_path = Path(temporary.name)

        print("Receiving the one-time archive over USB ADB; the phone's source files will remain in place.")
        download_archive(adb_path, serial, transfer_token, temporary_path)
        verified = validate_archive(temporary_path)
        device_dir = ARCHIVE_ROOT / verified["device_id"]
        device_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(device_dir, 0o700)
        filename = f"{verified['timestamp']}-{verified['archive_id']}.tar.gz"
        destination = device_dir / filename
        if destination.exists():
            raise RuntimeError(f"This archive is already present: {destination}")
        temporary_path.replace(destination)
        temporary_path = None
        print(f"Verified {verified['manifest']['file_count']} files from {verified['manifest']['device_model']}.")
        print(f"Local archive: {destination}")
        print("The archive remains local and is excluded from Git.")
        return 0
    except (OSError, RuntimeError, subprocess.SubprocessError, urllib.error.URLError,
            tarfile.TarError, ValueError, json.JSONDecodeError) as error:
        print(f"Device archive failed: {error}")
        return 1
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
