from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class MemoryMergeTests(unittest.TestCase):
    def test_pure_merge_rule(self):
        with tempfile.TemporaryDirectory(prefix="traceform-memory-merge-") as directory:
            sources = [ROOT / "agent_os/android/src/org/traceform/agentos/MemoryMerge.java",
                       ROOT / "agent_os/tests/android/MemoryMergeHostTest.java"]
            subprocess.run(["javac", "--release", "8", "-d", directory, *map(str, sources)], check=True)
            subprocess.run(["java", "-cp", directory, "org.traceform.agentos.MemoryMergeHostTest"], check=True)


if __name__ == "__main__":
    unittest.main()
