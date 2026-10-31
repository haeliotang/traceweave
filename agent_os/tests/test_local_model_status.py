from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class LocalModelStatusTests(unittest.TestCase):
    def test_model_status_distinguishes_file_runtime_and_verification(self):
        with tempfile.TemporaryDirectory(prefix="traceform-model-status-test-") as directory:
            sources = [ROOT / "agent_os/android/src/org/traceform/agentos/LocalModelStatus.java",
                       ROOT / "agent_os/android/src/org/traceform/agentos/T.java",
                       ROOT / "agent_os/android/src/org/traceform/agentos/LocalModelCatalog.java",
                       ROOT / "agent_os/tests/android/LocalModelStatusHostTest.java"]
            subprocess.run(["javac", "--release", "8", "-d", directory, *map(str, sources)], check=True)
            subprocess.run(["java", "-cp", directory, "org.traceform.agentos.LocalModelStatusHostTest"], check=True)


if __name__ == "__main__":
    unittest.main()
