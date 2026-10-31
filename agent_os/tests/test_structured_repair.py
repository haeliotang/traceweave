import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "agent_os/android/src/org/traceform/agentos/StructuredRepair.java"
TEST = ROOT / "agent_os/tests/android/StructuredRepairHostTest.java"


class StructuredRepairTests(unittest.TestCase):
    def test_repair_loop(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "8", "-d", directory, str(SOURCE), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "org.traceform.agentos.StructuredRepairHostTest"],
                                    capture_output=True, text=True, check=True)
            self.assertIn("STRUCTURED_REPAIR_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
