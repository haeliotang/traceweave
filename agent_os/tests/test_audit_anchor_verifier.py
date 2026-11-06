"""Anchors alone are not enough: the out-of-database state must be enforced."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave"
TEST = ROOT / "agent_os/tests/android/AuditAnchorVerifierHostTest.java"


class AuditAnchorVerifierTests(unittest.TestCase):
    def test_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run([
                "javac", "--release", "8", "-d", directory,
                str(SRC / "AuditAnchorVerifier.java"), str(SRC / "AnchorMac.java"), str(TEST),
            ], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "AuditAnchorVerifierHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS audit_anchor_verifier", result.stdout)


if __name__ == "__main__":
    unittest.main()
