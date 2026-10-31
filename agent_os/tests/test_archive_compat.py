"""The pre-rename archive rules must keep 0.6.x archives importable."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "agent_os/android/src/com/inceptless/traceweave/ArchiveCompat.java"
TEST = ROOT / "agent_os/tests/android/ArchiveCompatHostTest.java"


class ArchiveCompatTests(unittest.TestCase):
    def test_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["javac", "--release", "8", "-d", directory, str(SOURCE), str(TEST)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "ArchiveCompatHostTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS archive_compat", result.stdout)


class LegacyFixtureTests(unittest.TestCase):
    """A real 0.6.14 export must pass the compatibility gate."""

    def test_real_legacy_archive(self):
        fixture = ROOT / "agent_os/tests/data/legacy-0.6.14-memory.taos"
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run([
                "javac", "--release", "8", "-d", directory,
                str(SOURCE), str(ROOT / "agent_os/tests/android/LegacyArchiveFixtureTest.java"),
            ], check=True, capture_output=True)
            result = subprocess.run([
                "java", "-cp", directory, "LegacyArchiveFixtureTest",
                str(fixture), "traceweave-migration-2026",
            ], check=True, capture_output=True, text=True)
            self.assertIn("PASS legacy_fixture", result.stdout)
            self.assertIn("org.traceform.agentos.memory", result.stdout)


if __name__ == "__main__":
    unittest.main()
