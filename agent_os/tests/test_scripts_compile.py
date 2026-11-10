# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Every Python helper under agent_os/scripts must at least compile."""
import pathlib
import py_compile
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "agent_os/scripts"


class ScriptCompileTests(unittest.TestCase):
    def test_all_scripts_compile(self):
        failures = []
        with tempfile.TemporaryDirectory() as directory:
            for path in sorted(SCRIPTS.glob("*.py")):
                try:
                    py_compile.compile(str(path), cfile=str(pathlib.Path(directory) / (path.stem + ".pyc")),
                                       doraise=True)
                except py_compile.PyCompileError as error:
                    failures.append(f"{path.name}: {error.msg}")
        self.assertEqual(failures, [], "scripts failed to compile")


if __name__ == "__main__":
    unittest.main()
