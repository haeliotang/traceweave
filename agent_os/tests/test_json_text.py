from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class JsonTextTests(unittest.TestCase):
    def test_recovers_json_from_model_prose(self):
        with tempfile.TemporaryDirectory(prefix="traceform-json-text-") as directory:
            sources = [ROOT / "agent_os/android/src/org/traceform/agentos/JsonText.java",
                       ROOT / "agent_os/tests/android/JsonTextHostTest.java"]
            subprocess.run(["javac", "--release", "8", "-d", directory, *map(str, sources)], check=True)
            result = subprocess.run(["java", "-cp", directory, "org.traceform.agentos.JsonTextHostTest"],
                                    capture_output=True, text=True, check=True)
            self.assertIn("JSONTEXT_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
