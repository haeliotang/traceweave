import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("verify_release", ROOT / "agent_os/scripts/verify_github_release.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReleaseVerifyTests(unittest.TestCase):
    def test_matching_assets(self):
        local = {"a.apk": "abc", "SHA256SUMS": "def"}
        remote = [{"name": "a.apk", "digest": "sha256:abc"}, {"name": "SHA256SUMS", "digest": "sha256:def"}]
        report = MODULE.compare(local, remote)
        self.assertTrue(report["ok"])

    def test_missing_and_mismatch(self):
        local = {"a.apk": "abc", "b.json": "111"}
        remote = [{"name": "a.apk", "digest": "sha256:zzz"}]
        report = MODULE.compare(local, remote)
        self.assertFalse(report["ok"])
        self.assertEqual(report["missing"], ["b.json"])
        self.assertEqual(report["mismatched"], ["a.apk"])


if __name__ == "__main__":
    unittest.main()
