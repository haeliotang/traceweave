# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "agent_os/scripts/release_notes_android.py"


class ReleaseNotesTests(unittest.TestCase):
    def test_notes_include_version_signer_and_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            report = directory / "build_report.json"
            verification = directory / "verify.json"
            output = directory / "notes.md"
            report.write_text(json.dumps({"package": "com.inceptless.traceweave", "version": "1.2.3", "version_code": 42}))
            verification.write_text(json.dumps({
                "package": "com.inceptless.traceweave", "version": "1.2.3", "version_code": 42,
                "signer_sha256": "abc123", "sync_runtime_included": True,
                "model_runtime_included": False, "test_components_excluded": True}))
            subprocess.run([sys.executable, str(SCRIPT), "--report", str(report),
                            "--verification", str(verification), "--output", str(output)],
                           check=True, capture_output=True)
            text = output.read_text()
            self.assertIn("1.2.3 (versionCode 42)", text)
            self.assertIn("abc123", text)
            self.assertIn("schema", text)
            self.assertIn("同步运行库：已包含", text)
            self.assertIn("模型运行库：未包含", text)
            self.assertIn("测试组件已剔除：是", text)


if __name__ == "__main__":
    unittest.main()
