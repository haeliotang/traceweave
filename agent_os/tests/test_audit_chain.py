"""The audit hash chain must detect edits and document its own limits."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "agent_os/android/src/com/inceptless/traceweave/AuditChain.java"
TEST = ROOT / "agent_os/tests/android/AuditChainHostTest.java"


class AuditChainTests(unittest.TestCase):
    def test_chain_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "8", "-d", directory, str(SOURCE), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "AuditChainHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS audit_chain", result.stdout)


if __name__ == "__main__":
    unittest.main()
