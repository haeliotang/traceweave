# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Cross-application names must stay consistent after renames.

The Traceform -> Traceweave rename broke things that still compiled: a shortcut
intent, the <queries> entry and two signature permissions kept pointing at package
names that no longer existed, and the failure was silent at runtime. This test
compares every cross-application string in the app sources with the package names
that actually exist in the repository.
"""
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "agent_os/android"
SRC = APP / "src"
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOKEN = re.compile(r"\b(?:com|org)\.[a-z0-9_]+\.[a-z0-9_.]+")

# Platform packages we talk to and the one deliberate pre-rename constant.
EXTERNAL_PREFIXES = ("com.android.",)
LEGACY_ALLOWED = {"org.traceform.agentos.memory"}
LEGACY_FILE = "ArchiveCompat.java"


def manifests():
    return sorted(APP.glob("*.xml")) + sorted((ROOT / "agent_os/tests/android").glob("*.xml")) + sorted(
        pathlib.Path(ROOT / "android").glob("*.xml"))


def declared_packages():
    names = set()
    for path in manifests():
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        package = root.get("package")
        if package:
            names.add(package)
    return names


def java_package_dirs():
    names = set()
    for path in SRC.rglob("*.java"):
        text = path.read_text(encoding="utf-8", errors="replace")
        match = re.search(r"^package\s+([\w.]+);", text, re.MULTILINE)
        if match:
            names.add(match.group(1))
    return names


class PackageConsistencyTests(unittest.TestCase):
    def setUp(self):
        self.packages = declared_packages()
        self.own = self.packages | java_package_dirs()

    def test_manifest_is_the_app_package(self):
        root = ET.parse(APP / "AndroidManifest.xml").getroot()
        self.assertEqual(root.get("package"), "com.inceptless.traceweave")

    def test_no_renamed_away_packages_survive_in_sources(self):
        stale = []
        for path in list(SRC.rglob("*.java")) + list(SRC.rglob("*.xml")) + [APP / "AndroidManifest.xml"]:
            text = path.read_text(encoding="utf-8", errors="replace")
            for token in TOKEN.findall(text):
                if not token.startswith("org.traceform"):
                    continue
                if path.name == LEGACY_FILE and token in LEGACY_ALLOWED:
                    continue
                stale.append(f"{path.relative_to(ROOT)}: {token}")
        self.assertEqual(stale, [], "pre-rename package names still referenced")

    def test_cross_app_packages_exist(self):
        refs = set()
        for path in list(SRC.rglob("*.java")) + list(SRC.rglob("*.xml")) + [APP / "AndroidManifest.xml"]:
            text = path.read_text(encoding="utf-8", errors="replace")
            refs.update(TOKEN.findall(text))
        unknown = sorted(
            token for token in refs
            if token not in LEGACY_ALLOWED
            and not token.startswith(EXTERNAL_PREFIXES)
            and not any(token == name or token.startswith(name + ".") for name in self.own)
        )
        self.assertEqual(unknown, [], "references to package names that no repository manifest declares")

    def test_manifest_components_exist(self):
        root = ET.parse(APP / "AndroidManifest.xml").getroot()
        package = root.get("package")
        missing = []
        for element in root.iter():
            name = element.get(ANDROID + "name")
            if not name or element.tag not in ("activity", "service", "receiver", "provider"):
                continue
            if not name.startswith("."):
                continue
            relative = name[1:].replace(".", "/")
            if not (SRC / package.replace(".", "/") / (relative + ".java")).is_file():
                missing.append(name)
        self.assertEqual(missing, [], "manifest declares components without sources")

    def test_shortcuts_target_the_app(self):
        root = ET.parse(APP / "res/xml/shortcuts.xml").getroot()
        package = ET.parse(APP / "AndroidManifest.xml").getroot().get("package")
        problems = []
        for intent in root.iter("intent"):
            if intent.get(ANDROID + "targetPackage") != package:
                problems.append(intent.get(ANDROID + "targetPackage"))
            target = intent.get(ANDROID + "targetClass")
            relative = target[len(package) + 1:].replace(".", "/")
            if not (SRC / package.replace(".", "/") / (relative + ".java")).is_file():
                problems.append(target)
        self.assertEqual(problems, [], "shortcut intents must target classes that exist in this app")


if __name__ == "__main__":
    unittest.main()
