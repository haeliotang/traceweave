import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave"
TEST = ROOT / "agent_os/tests/android/SyncResultDescribeHostTest.java"


class SyncDescribeTests(unittest.TestCase):
    def test_describe_maps_results(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "8", "-d", directory,
                            str(SRC / "SyncResultText.java"), str(SRC / "T.java"), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "com.inceptless.traceweave.SyncResultDescribeHostTest"],
                                    capture_output=True, text=True, check=True)
            self.assertIn("SYNC_DESCRIBE_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
