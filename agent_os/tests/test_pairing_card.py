# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Out-of-band pairing card: parsed strictly, rejects tampering and typos."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave/PairingCard.java"
TEST = ROOT / "agent_os/tests/android/PairingCardHostTest.java"


class PairingCardTests(unittest.TestCase):
    def test_card_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "11", "-d", directory, str(SRC), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "PairingCardHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS pairing_card", result.stdout)


if __name__ == "__main__":
    unittest.main()
