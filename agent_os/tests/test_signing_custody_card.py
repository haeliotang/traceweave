# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""The recovery card must describe the archive without ever holding a secret."""
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import signing_custody_card as card  # noqa: E402


class CustodyCardTests(unittest.TestCase):
    def _archive(self, directory, payload=b"TFEDGE01" + b"\x00" * 64):
        path = pathlib.Path(directory) / "signing-backup-synthetic.edgea"
        path.write_bytes(payload)
        return path

    def test_card_records_checksum_and_instructions(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = self._archive(directory)
            text = card.card_text(archive, "account-123", "safe at home", "2026-11-04")
            self.assertIn("signing-backup-synthetic.edgea", text)
            self.assertIn("safe at home", text)
            self.assertIn("2026-11-04", text)
            self.assertIn("scripts/recover_signing_archive.py", text)
            self.assertIn("9effee32f57b35c256529e97cd8db435813e84025d48601c00770b6adf95a6db", text)
            self.assertIn("e4bd7ecd43aba24b7759519e054b89d937417c1dbc08def02b7e9fd11584cf0c", text)

    def test_passphrase_placeholder_when_location_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = self._archive(directory)
            text = card.card_text(archive, "account-123", None, "2026-11-04")
            self.assertIn("fill in by hand", text)

    def test_card_holds_no_archive_bytes(self):
        marker = b"PAYLOAD-MARKER-DO-NOT-COPY"
        with tempfile.TemporaryDirectory() as directory:
            archive = self._archive(directory, b"TFEDGE01" + marker + bytes(range(60)))
            text = card.card_text(archive, "account-123", "somewhere", "2026-11-04")
            # The format name is documented on purpose; the encrypted payload is not.
            self.assertNotIn(marker.decode(), text, "archive payload must not reach the card")
            self.assertNotIn("bytes(range", text)
            self.assertLess(len(text), 8000, "card should stay a one-page document")

    def test_generator_writes_the_file(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = self._archive(directory)
            output = pathlib.Path(directory) / "card.md"
            result = subprocess.run([
                sys.executable, str(ROOT / "scripts/signing_custody_card.py"),
                "--archive", str(archive), "--keychain-account", "account-123",
                "--output", str(output)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(output.is_file())
            self.assertIn("Signing recovery card", output.read_text())

    def test_missing_archive_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "card.md"
            result = subprocess.run([
                sys.executable, str(ROOT / "scripts/signing_custody_card.py"),
                "--archive", str(pathlib.Path(directory) / "nope.edgea"),
                "--keychain-account", "account-123", "--output", str(output)],
                capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
