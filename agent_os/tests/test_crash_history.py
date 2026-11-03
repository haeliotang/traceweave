"""Crash history is stored as one tab-separated line per crash."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave"
TEST = ROOT / "agent_os/tests/android/CrashHistoryHostTest.java"


class CrashHistoryTests(unittest.TestCase):
    def test_format_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run([
                "javac", "--release", "8", "-d", directory,
                str(SRC / "CrashHistory.java"), str(TEST),
            ], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "CrashHistoryHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS crash_history", result.stdout)


if __name__ == "__main__":
    unittest.main()
