"""An archive declares the head of its own audit chain; imports must verify it."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave"
TEST = ROOT / "agent_os/tests/android/ArchiveAuditHostTest.java"


class ArchiveAuditTests(unittest.TestCase):
    def test_head_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run([
                "javac", "--release", "8", "-d", directory,
                str(SRC / "AuditChain.java"), str(SRC / "ArchiveAudit.java"), str(TEST),
            ], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "ArchiveAuditHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS archive_audit", result.stdout)


if __name__ == "__main__":
    unittest.main()
