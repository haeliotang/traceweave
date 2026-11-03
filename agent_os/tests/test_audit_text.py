"""Audit identifiers must render as readable lines in both languages."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave"
TEST = ROOT / "agent_os/tests/android/AuditTextHostTest.java"


class AuditTextTests(unittest.TestCase):
    def test_wording(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run([
                "javac", "--release", "8", "-d", directory,
                str(SRC / "AuditText.java"), str(SRC / "T.java"), str(TEST),
            ], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "AuditTextHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS audit_text", result.stdout)


if __name__ == "__main__":
    unittest.main()
