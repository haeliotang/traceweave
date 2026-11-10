# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Every catalog key used in code must exist in T.java.

The catalog throws on an unknown key, which turns a typo into a crash; this
static check catches it before the build reaches a device.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / "agent_os/android/src/com/inceptless/traceweave"
CATALOG = SOURCE_DIR / "T.java"
KEY_PATTERN = re.compile(r'T\.(?:s|format)\("([^"]+)"')


class StringKeyTests(unittest.TestCase):
    def test_every_used_key_exists(self):
        catalog = CATALOG.read_text()
        defined = set(re.findall(r'add\("([^"]+)"', catalog))
        used = set()
        for path in SOURCE_DIR.glob("*.java"):
            if path.name == "T.java":
                continue
            used.update(KEY_PATTERN.findall(path.read_text()))
        missing = sorted(used - defined)
        self.assertEqual(missing, [], "Catalog keys used in code but missing from T.java")


if __name__ == "__main__":
    unittest.main()
