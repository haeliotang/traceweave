# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""The recovery drill must read both container layouts without touching real keys."""
import io
import json
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import edge_encrypted_archive as eea  # noqa: E402
import recover_signing_archive as drill  # noqa: E402

KEY = bytes(range(32))


def write_archive(path, payload):
    with open(path, "wb") as destination:
        eea.encrypt_stream(io.BytesIO(payload), destination, KEY)


class RecoverSigningArchiveTests(unittest.TestCase):
    def test_json_payload_is_inspected_and_extracted(self):
        manifest = {"keys": [{"keystore_b64": "MIIB" * 32, "store_password": "synthetic"}],
                    "note": "synthetic"}
        with tempfile.TemporaryDirectory() as directory:
            archive = pathlib.Path(directory) / "backup.edgea"
            write_archive(archive, json.dumps(manifest).encode())
            data, digest = drill.read_plaintext(archive, KEY)
            report = drill.describe_payload(data)
            self.assertEqual(report["format"], "json")
            self.assertIn("keys", report["keys"])
            self.assertEqual(len(digest), 64)

    def test_tar_payload_is_extracted(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = pathlib.Path(directory) / "backup.edgea"
            buffer = io.BytesIO()
            with tarfile.open(fileobj=buffer, mode="w") as bundle:
                payload = b"synthetic keystore placeholder"
                info = tarfile.TarInfo("keys/fake.p12")
                info.size = len(payload)
                bundle.addfile(info, io.BytesIO(payload))
            write_archive(archive, buffer.getvalue())
            target = pathlib.Path(directory) / "out"
            target.mkdir()
            written = drill.extract(archive, KEY, target)
            self.assertEqual(written, ["keys/fake.p12"])
            self.assertEqual((target / "keys/fake.p12").read_bytes(), payload)

    def test_tampered_archive_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = pathlib.Path(directory) / "backup.edgea"
            write_archive(archive, json.dumps({"a": "b"}).encode())
            data = bytearray(archive.read_bytes())
            data[-1] ^= 0xFF  # break the GCM tag
            archive.write_bytes(bytes(data))
            with self.assertRaises(Exception):
                drill.read_plaintext(archive, KEY)

    def test_wrong_key_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = pathlib.Path(directory) / "backup.edgea"
            write_archive(archive, b'{"a":"b"}')
            with self.assertRaises(Exception):
                drill.read_plaintext(archive, bytes(range(1, 33)))

    def test_unsafe_tar_paths_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = pathlib.Path(directory) / "backup.edgea"
            buffer = io.BytesIO()
            with tarfile.open(fileobj=buffer, mode="w") as bundle:
                info = tarfile.TarInfo("../escape.p12")
                info.size = 1
                bundle.addfile(info, io.BytesIO(b"x"))
            write_archive(archive, buffer.getvalue())
            target = pathlib.Path(directory) / "out"
            target.mkdir()
            with self.assertRaises(SystemExit):
                drill.extract(archive, KEY, target)


if __name__ == "__main__":
    unittest.main()
