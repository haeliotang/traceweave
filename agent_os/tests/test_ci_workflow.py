"""Keep the CI workflow aligned with the pinned model runtime and scripts."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/agent-os-android.yml"
PINNED_LLAMA = "d78f40d9d34d7a72ecdcd2e854f75ff86afc8ca3"
RUNTIME_SCRIPT = ROOT / "agent_os/scripts/build_experimental_model_runtime.py"


class CiWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.text = WORKFLOW.read_text()

    def test_workflow_pins_the_model_runtime_commit(self):
        self.assertIn(PINNED_LLAMA, self.text)

    def test_workflow_commit_matches_the_runtime_script(self):
        match = re.search(r'COMMIT\s*=\s*"([0-9a-f]{40})"', RUNTIME_SCRIPT.read_text())
        self.assertIsNotNone(match, "runtime script should declare a pinned commit")
        self.assertEqual(match.group(1), PINNED_LLAMA)

    def test_push_runs_are_opt_in(self):
        """The monthly Actions quota is small: a push must not start jobs unless the
        commit message contains [ci] or a human dispatched the workflow."""
        self.assertEqual(self.text.count("contains(github.event.head_commit.message, '[ci]')"), 3,
                         "every job needs the [ci] guard")
        self.assertIn("workflow_dispatch:", self.text)

    def test_workflow_runs_the_android_gate(self):
        self.assertIn("ci_android.py", self.text)
        self.assertIn("unittest", (ROOT / "agent_os/scripts/ci_android.py").read_text())


if __name__ == "__main__":
    unittest.main()
