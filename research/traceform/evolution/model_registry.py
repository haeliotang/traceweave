# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Versioned model bundles with atomic activation and explicit rollback."""

from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path

from traceform.representation.provenance import content_digest


def atomic_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as output:
        json.dump(value, output, sort_keys=True, allow_nan=False)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
    descriptor = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


class ModelRegistry:
    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    @contextmanager
    def lock(self):
        with (self.root / ".lock").open("a") as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            yield

    def register(self, manifold, normalizer, validation):
        bundle = {"manifold": manifold, "normalizer": normalizer, "validation": validation}
        version = content_digest(bundle)
        bundle["version"] = version
        with self.lock():
            path = self.root / (version + ".json")
            if not path.exists():
                atomic_json(path, bundle)
        return version

    def bundle(self, version):
        if len(version) != 64 or any(character not in "0123456789abcdef" for character in version):
            raise ValueError("invalid model version")
        bundle = json.loads((self.root / (version + ".json")).read_text())
        if content_digest({key: bundle[key] for key in ("manifold", "normalizer", "validation")}) != version:
            raise ValueError("model bundle digest mismatch")
        return bundle

    def active(self):
        path = self.root / "current.json"
        if not path.exists():
            return None
        pointer = json.loads(path.read_text())
        return self.bundle(pointer["version"])

    def activate(self, version, expected_current=None):
        bundle = self.bundle(version)
        if not bundle["validation"].get("accepted"):
            raise ValueError("candidate has not passed acceptance")
        with self.lock():
            pointer_path = self.root / "current.json"
            pointer = json.loads(pointer_path.read_text()) if pointer_path.exists() else {}
            if pointer.get("version") != expected_current:
                raise ValueError("active model changed during candidate fitting")
            atomic_json(pointer_path, {"version": version, "previous": pointer.get("version")})

    def rollback(self):
        with self.lock():
            pointer_path = self.root / "current.json"
            pointer = json.loads(pointer_path.read_text())
            previous = pointer.get("previous")
            if not previous:
                raise ValueError("no rollback version")
            self.bundle(previous)
            atomic_json(pointer_path, {"version": previous, "previous": pointer["version"]})
        return previous

    def prune(self, keep=4):
        if keep < 2:
            raise ValueError("retain active and rollback versions")
        with self.lock():
            pointer_path = self.root / "current.json"
            pointer = json.loads(pointer_path.read_text()) if pointer_path.exists() else {}
            protected = {value for value in (pointer.get("version"), pointer.get("previous")) if value}
            files = sorted((path for path in self.root.glob("*.json") if len(path.stem) == 64 and all(character in "0123456789abcdef" for character in path.stem)), key=lambda path: path.stat().st_mtime, reverse=True)
            retained = set(protected)
            for path in files:
                if len(retained) < keep:
                    retained.add(path.stem)
            for path in files:
                if path.stem not in retained:
                    path.unlink()
            return sorted(retained)
