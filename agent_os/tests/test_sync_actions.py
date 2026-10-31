from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class SyncActionsTests(unittest.TestCase):
    def test_address_parsing(self):
        # The JSON helpers depend on Android's org.json and are exercised on device.
        with tempfile.TemporaryDirectory(prefix="traceform-sync-actions-") as directory:
            sources = [ROOT / "agent_os/android/src/org/traceform/agentos/AddressParser.java",
                       ROOT / "agent_os/tests/android/AddressParserHostTest.java"]
            subprocess.run(["javac", "--release", "8", "-d", directory, *map(str, sources)], check=True)
            result = subprocess.run(["java", "-cp", directory, "org.traceform.agentos.AddressParserHostTest"],
                                    capture_output=True, text=True, check=True)
            self.assertIn("ADDRESS_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
