# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Lost-response recovery must converge without resetting the replay chain."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave/ChallengeChain.java"
TEST = ROOT / "agent_os/tests/android/ChallengeChainHostTest.java"


class ChallengeChainTests(unittest.TestCase):
    def test_recovery_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "11", "-d", directory, str(SRC), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "ChallengeChainHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS challenge_chain", result.stdout)


if __name__ == "__main__":
    unittest.main()
