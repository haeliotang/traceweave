# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""The database migration chain must cover every version, and checks must not hardcode one."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
STORE = ROOT / "agent_os/android/src/com/inceptless/traceweave/AgentStore.java"
DEVICE_TESTS = (ROOT / "agent_os/tests/android").glob("*.java")


class MigrationChainTests(unittest.TestCase):
    def setUp(self):
        self.text = STORE.read_text(encoding="utf-8")
        match = re.search(r"DATABASE_VERSION\s*=\s*(\d+)", self.text)
        self.assertIsNotNone(match, "DATABASE_VERSION should be a literal")
        self.version = int(match.group(1))

    def test_every_version_has_an_upgrade_branch(self):
        chain = self.text[self.text.index("public void onUpgrade"):]
        branches = {int(value) for value in re.findall(r"version == (\d+)", chain)}
        expected = set(range(1, self.version))
        self.assertEqual(branches, expected,
                         "each version below DATABASE_VERSION needs an explicit branch; "
                         "missing: %s extra: %s" % (sorted(expected - branches), sorted(branches - expected)))

    def test_upgrade_reaches_the_declared_version(self):
        chain = self.text[self.text.index("public void onUpgrade"):]
        reached = {int(value) for value in re.findall(r"version = (\d+);", chain)}
        self.assertIn(self.version, reached, "no branch assigns the current DATABASE_VERSION")

    def test_downgrade_is_handled(self):
        self.assertIn("onDowngrade", self.text,
                      "an older APK must not crash on a newer database")

    def test_device_checks_use_the_constant(self):
        offenders = []
        for path in DEVICE_TESTS:
            text = path.read_text(encoding="utf-8")
            for number in re.findall(r"getVersion\(\)\s*[!=]=\s*(\d+)", text):
                offenders.append("%s hardcodes v%s" % (path.name, number))
        self.assertEqual(offenders, [], "device migration checks must compare against currentSchemaVersion()")


if __name__ == "__main__":
    unittest.main()
