from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class LocalModelFilesTests(unittest.TestCase):
    def test_checked_install_preserves_previous_model_on_failure(self):
        with tempfile.TemporaryDirectory(prefix="traceform-model-test-") as directory:
            sources = [ROOT / "agent_os/android/src/com/inceptless/traceweave/LocalModelFiles.java",
                       ROOT / "agent_os/android/src/com/inceptless/traceweave/LocalModelCatalog.java",
                       ROOT / "agent_os/tests/android/LocalModelFilesHostTest.java"]
            subprocess.run(["javac", "--release", "8", "-d", directory, *map(str, sources)], check=True)
            subprocess.run(["java", "-cp", directory, "com.inceptless.traceweave.LocalModelFilesHostTest"], check=True)


if __name__ == "__main__":
    unittest.main()
