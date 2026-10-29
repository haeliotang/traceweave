from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class LocalModelDraftTests(unittest.TestCase):
    def test_cancelling_waiting_request_does_not_cancel_active_request(self):
        with tempfile.TemporaryDirectory(prefix="traceform-model-draft-test-") as directory:
            sources = [ROOT / "agent_os/android/src/org/traceform/agentos/LocalModelDraft.java",
                       ROOT / "agent_os/tests/android/LocalModelDraftHostTest.java"]
            subprocess.run(["javac", "--release", "8", "-d", directory, *map(str, sources)], check=True)
            subprocess.run(["java", "-cp", directory, "org.traceform.agentos.LocalModelDraftHostTest"], check=True)


if __name__ == "__main__":
    unittest.main()
