from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class BilingualCatalogTests(unittest.TestCase):
    def test_every_key_has_both_languages(self):
        with tempfile.TemporaryDirectory(prefix="traceform-t-test-") as directory:
            sources = [ROOT / "agent_os/android/src/com/inceptless/traceweave/T.java",
                       ROOT / "agent_os/tests/android/TStringsHostTest.java"]
            subprocess.run(["javac", "--release", "8", "-d", directory, *map(str, sources)], check=True)
            result = subprocess.run(["java", "-cp", directory, "com.inceptless.traceweave.TStringsHostTest"],
                                    capture_output=True, text=True, check=True)
            self.assertIn("T_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
