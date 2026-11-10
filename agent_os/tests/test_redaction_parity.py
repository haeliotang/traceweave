# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Java and Rust redaction must agree; a narrower native rule leaked secrets."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "agent_os/android/src/com/inceptless/traceweave/RedactionRules.java"
TEST = ROOT / "agent_os/tests/android/ContextRedactorHostTest.java"
RUST = ROOT / "agent_os/core/src/redactor.rs"


class RedactionParityTests(unittest.TestCase):
    def test_java_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "8", "-d", directory, str(SRC), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "ContextRedactorHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS context_redactor", result.stdout)

    def test_rust_checks_the_same_key_value_secrets(self):
        text = RUST.read_text(encoding="utf-8")
        for key in ("password", "passwd", "secret", "token", "密码", "口令", "AKIA", "eyJ"):
            self.assertIn(key, text, "the native rule must cover %s" % key)


if __name__ == "__main__":
    unittest.main()
