"""Every T.format call site must supply as many arguments as the template needs.

A mismatch only shows up at runtime as MissingFormatArgumentException, so this
check parses call sites with balanced parentheses instead of a regex.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "agent_os/android/src/org/traceform/agentos"
PLACEHOLDER = re.compile(r"%(?!%)(?:\d+\$)?[-+ 0,]*\d*(?:\.\d+)?[sdf]")


def catalog():
    text = (SOURCE / "T.java").read_text()
    pairs = {}
    for key, en, zh in re.findall(r'add\("([^"]+)",\s*"((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)"\);', text):
        pairs[key] = (en, zh)
    return pairs


def call_sites(text):
    sites = []
    for match in re.finditer(r'T\.format\("([^"]+)"', text):
        index = match.end()
        depth = 0
        args = []
        current = []
        while index < len(text):
            char = text[index]
            if char in "([{":
                depth += 1
            elif char in ")]}":
                if depth == 0:
                    args.append("".join(current))
                    break
                depth -= 1
            elif char == "," and depth == 0:
                args.append("".join(current))
                current = []
                index += 1
                continue
            current.append(char)
            index += 1
        sites.append((match.group(1), args))
    return sites


class FormatArgumentTests(unittest.TestCase):
    def test_argument_counts_match_placeholders(self):
        entries = catalog()
        problems = []
        for path in sorted(SOURCE.glob("*.java")):
            if path.name == "T.java":
                continue
            for key, args in call_sites(path.read_text()):
                if key not in entries:
                    continue
                en, zh = entries[key]
                needed = max(len(PLACEHOLDER.findall(en)), len(PLACEHOLDER.findall(zh)))
                supplied = max(0, len(args) - 2)  # drop key and language flag
                if needed != supplied:
                    problems.append(f"{path.name}:{key} needs {needed}, got {supplied}")
        self.assertEqual(problems, [], "T.format argument mismatch")


if __name__ == "__main__":
    unittest.main()
