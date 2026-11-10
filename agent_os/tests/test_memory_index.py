# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Memory search must behave like a substring search, not a whole-word lookup."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "agent_os/android/src/com/inceptless/traceweave/MemorySearch.java"
TEST = ROOT / "agent_os/tests/android/MemoryIndexHostTest.java"


class MemorySearchTests(unittest.TestCase):
    def test_search_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "8", "-d", directory, str(SOURCE), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "MemoryIndexHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS memory_index", result.stdout)


if __name__ == "__main__":
    unittest.main()
