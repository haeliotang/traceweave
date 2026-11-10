# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""The AGPL section 13 source link must point at the running build."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave/SourceInfo.java"
TEST = ROOT / "agent_os/tests/android/SourceInfoHostTest.java"


class SourceInfoTests(unittest.TestCase):
    def test_source_link(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "11", "-d", directory, str(SRC), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "SourceInfoHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS source_info", result.stdout)


if __name__ == "__main__":
    unittest.main()
