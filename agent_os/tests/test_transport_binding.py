"""P-256 channel binding: a copied seed alone must not be able to sync."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave/TransportBinding.java"
TEST = ROOT / "agent_os/tests/android/TransportBindingHostTest.java"


class TransportBindingTests(unittest.TestCase):
    def test_binding_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "11", "-d", directory, str(SRC), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "TransportBindingHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS transport_binding", result.stdout)


if __name__ == "__main__":
    unittest.main()
