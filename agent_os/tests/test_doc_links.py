"""Relative links in docs must point at files that exist."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
LINK = re.compile(r"\]\(([^)#\s]+)")
# file:// links that point outside the repository (old planning documents kept
# for history) are skipped: they cannot resolve anywhere but the original machine.
SKIP_PREFIXES = ("http://", "https://", "mailto:", "#", "file://")


def markdown_files():
    files = [ROOT / "README.md"] if (ROOT / "README.md").exists() else []
    files += sorted((ROOT / "docs").glob("*.md"))
    return files


class DocLinkTests(unittest.TestCase):
    def test_relative_links_resolve(self):
        broken = []
        for path in markdown_files():
            text = path.read_text(encoding="utf-8", errors="replace")
            for target in LINK.findall(text):
                if target.startswith(SKIP_PREFIXES):
                    continue
                resolved = (path.parent / target).resolve()
                if not resolved.exists():
                    broken.append(f"{path.relative_to(ROOT)} -> {target}")
        self.assertEqual(broken, [], "broken relative links in docs")


if __name__ == "__main__":
    unittest.main()
