"""Stored step titles keep their planning language; the UI translates built-in ones."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave"
TEST = ROOT / "agent_os/tests/android/StepTextHostTest.java"


class StepTextTests(unittest.TestCase):
    def test_wording(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run([
                "javac", "--release", "8", "-d", directory,
                str(SRC / "StepText.java"), str(SRC / "T.java"), str(TEST),
            ], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "StepTextHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS step_text", result.stdout)

    def test_status_keys_match_the_plan_constants(self):
        """Every catalog key that StepText.status() builds must exist as an
        ExecutionPlan constant value, otherwise a renamed state would silently
        fall back to the raw identifier."""
        import re
        plan = (ROOT / "agent_os/android/src/com/inceptless/traceweave/ExecutionPlan.java").read_text()
        catalog = (ROOT / "agent_os/android/src/com/inceptless/traceweave/T.java").read_text()
        plan_states = set(re.findall(r'STEP_[A-Z_]+\s*=\s*"([a-z_]+)"', plan))
        catalog_states = set(re.findall(r'add\("step\.status\.([a-z_]+)"', catalog))
        self.assertTrue(plan_states, "plan constants should be found")
        self.assertEqual(plan_states, catalog_states,
                         "step.status.* keys must cover exactly the ExecutionPlan states")


if __name__ == "__main__":
    unittest.main()
