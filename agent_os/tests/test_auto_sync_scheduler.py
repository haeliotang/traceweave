import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
POLICY = ROOT / "agent_os/android/src/com/inceptless/traceweave/AutoSyncPolicy.java"
TEST = ROOT / "agent_os/tests/android/AutoSyncSchedulerHostTest.java"


class AutoSyncPolicyTests(unittest.TestCase):
    def test_interval_clamping(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "8", "-d", directory, str(POLICY), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "com.inceptless.traceweave.AutoSyncSchedulerHostTest"],
                                    capture_output=True, text=True, check=True)
            self.assertIn("AUTO_SYNC_SCHEDULER_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
