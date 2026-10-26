import hashlib
import json
import os
from pathlib import Path
import unittest
import importlib.util


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("release_verifier", ROOT / "agent_os/scripts/verify_release_android.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class BuildReportTests(unittest.TestCase):
    def test_build_report_covers_packaged_resources(self):
        report_path = Path(os.environ.get("AGENT_OS_BUILD_REPORT", ROOT / "agent_os/build/workflow-validation/build_report.json"))
        if not report_path.exists():
            self.skipTest("Build an APK first or set AGENT_OS_BUILD_REPORT")
        report = json.loads(report_path.read_text())
        resources = [p for p in (ROOT / "agent_os/android/res").rglob("*") if p.is_file()]
        missing = [str(p.relative_to(ROOT)) for p in resources if str(p.relative_to(ROOT)) not in report["source_sha256"]]
        self.assertEqual(missing, [], "Packaged resources must be part of build provenance")
        for resource in resources:
            self.assertEqual(report["source_sha256"][str(resource.relative_to(ROOT))], hashlib.sha256(resource.read_bytes()).hexdigest())

    def test_backup_enabled_or_extra_exported_activity_is_rejected(self):
        text = '''E: manifest
    E: application
      A: allowBackup=false
      A: usesCleartextTraffic=false
        E: activity
          A: name=".MainActivity"
          A: exported=true
'''
        verifier.check_manifest(verifier.tree_nodes(text))
        with self.assertRaises(ValueError):
            verifier.check_manifest(verifier.tree_nodes(text.replace("allowBackup=false", "allowBackup=true")))
        with self.assertRaises(ValueError):
            verifier.check_manifest(verifier.tree_nodes(text.replace(".MainActivity", ".TaskReviewActivity")))

    def test_hidden_test_component_is_rejected_even_when_not_exported(self):
        text = '''E: application
  A: allowBackup=false
  A: usesCleartextTraffic=false
    E: activity
      A: name="org.traceform.agentos.BackgroundProbeActivity"
      A: exported=false
'''
        with self.assertRaises(ValueError):
            verifier.check_manifest(verifier.tree_nodes(text))

    def test_network_audit_follows_the_manifest_resource_reference(self):
        manifest = verifier.tree_nodes('E: application\n  A: networkSecurityConfig=@0x7f010002\n')
        resources = 'resource 0x7f010002 xml/other_policy\n  () (file) res/xml/other_policy.xml type=XML\n'
        self.assertEqual(verifier.network_policy_path(manifest, resources), 'res/xml/other_policy.xml')
        with self.assertRaises(ValueError):
            verifier.network_policy_path(manifest, '')


if __name__ == "__main__":
    unittest.main()
