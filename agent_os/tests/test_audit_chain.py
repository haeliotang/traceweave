# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""The audit digest must be unambiguous for new rows and still verify old ones."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave/AuditChain.java"
TEST = ROOT / "agent_os/tests/android/AuditChainHostTest.java"


class AuditChainTests(unittest.TestCase):
    def test_digest_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "8", "-d", directory, str(SRC), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "AuditChainHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS audit_chain", result.stdout)


if __name__ == "__main__":
    unittest.main()
