# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Anchors must bind a chain head to a key the database does not contain."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "agent_os/android/src/com/inceptless/traceweave/AnchorMac.java"
TEST = ROOT / "agent_os/tests/android/AnchorMacHostTest.java"


class AnchorMacTests(unittest.TestCase):
    def test_anchor_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "8", "-d", directory, str(SOURCE), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "AnchorMacHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS anchor_mac", result.stdout)


if __name__ == "__main__":
    unittest.main()
