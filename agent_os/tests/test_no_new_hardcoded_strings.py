"""Ratchet against new hardcoded Chinese literals outside the catalog.

The bilingual catalog is the only place user-facing text should live. The
codebase still carries older literals; this test freezes the current count per
file so new ones cannot slip in while the backlog shrinks.
"""
import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "agent_os/android/src/org/traceform/agentos"
BASELINE = ROOT / "agent_os/tests/data/hardcoded_cjk_baseline.json"
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
CJK = re.compile(r"[\u4e00-\u9fff]")


def counts():
    result = {}
    for path in sorted(SOURCE.glob("*.java")):
        if path.name == "T.java":
            continue
        total = 0
        for line in path.read_text().splitlines():
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            for match in LITERAL.finditer(line):
                if CJK.search(match.group(1)):
                    total += 1
                    break
        if total:
            result[path.name] = total
    return result


class HardcodedStringTests(unittest.TestCase):
    def test_no_new_hardcoded_chinese_literals(self):
        baseline = json.loads(BASELINE.read_text())
        current = counts()
        regressions = {
            name: (baseline.get(name, 0), count)
            for name, count in current.items()
            if count > baseline.get(name, 0)
        }
        self.assertEqual(regressions, {}, "New hardcoded Chinese literals found")


if __name__ == "__main__":
    unittest.main()
