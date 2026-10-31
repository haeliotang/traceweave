import hashlib
import json
import os
from pathlib import Path
import unittest
import importlib.util
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("release_verifier", ROOT / "agent_os/scripts/verify_release_android.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
prepare_spec = importlib.util.spec_from_file_location("release_preparer", ROOT / "agent_os/scripts/prepare_release_android.py")
preparer = importlib.util.module_from_spec(prepare_spec)
prepare_spec.loader.exec_module(preparer)


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
      A: name="com.inceptless.traceweave.BackgroundProbeActivity"
      A: exported=false
'''
        with self.assertRaises(ValueError):
            verifier.check_manifest(verifier.tree_nodes(text))

    def test_hidden_local_model_probes_are_rejected(self):
        for name in ("LocalModelProbe", "LocalModelQualityProbe", "LocalModelDocumentProbe"):
            text = f'''E: application
  A: allowBackup=false
  A: usesCleartextTraffic=false
    E: activity
      A: name="com.inceptless.traceweave.{name}"
      A: exported=false
'''
            with self.subTest(name=name), self.assertRaises(ValueError):
                verifier.check_manifest(verifier.tree_nodes(text))

    def test_network_audit_follows_the_manifest_resource_reference(self):
        manifest = verifier.tree_nodes('E: application\n  A: networkSecurityConfig=@0x7f010002\n')
        resources = 'resource 0x7f010002 xml/other_policy\n  () (file) res/xml/other_policy.xml type=XML\n'
        self.assertEqual(verifier.network_policy_path(manifest, resources), 'res/xml/other_policy.xml')
        with self.assertRaises(ValueError):
            verifier.network_policy_path(manifest, '')

    def test_native_model_runtime_requires_matching_build_input_and_license(self):
        runtime = b"synthetic runtime"
        digest = hashlib.sha256(runtime).hexdigest()
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / "synthetic.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libtraceweave_local_model.so", runtime)
                archive.writestr("res/raw/llama_cpp_license.txt", "synthetic license")
            with zipfile.ZipFile(apk) as archive:
                self.assertTrue(verifier.check_model_runtime(archive, {"build/libtraceweave_local_model.so": digest}))
                with self.assertRaises(ValueError):
                    verifier.check_model_runtime(archive, {"build/libtraceweave_local_model.so": "0" * 64})
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libtraceweave_local_model.so", runtime)
            with zipfile.ZipFile(apk) as archive:
                with self.assertRaises(ValueError):
                    verifier.check_model_runtime(archive, {"build/libtraceweave_local_model.so": digest})

    def test_model_runtime_provenance_rejects_changed_binary_or_source(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "agent_os/android/native/experimental_local_model.cpp"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"synthetic source")
            runtime = root / "libtraceweave_local_model.so"
            runtime.write_bytes(b"synthetic runtime")
            report = {"llama_cpp_commit": "d78f40d9d34d7a72ecdcd2e854f75ff86afc8ca3", "android_abi": "arm64-v8a",
                      "android_api": 29, "native_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                      "runtime_sha256": hashlib.sha256(runtime.read_bytes()).hexdigest()}
            (root / "experimental_model_build.json").write_text(json.dumps(report))
            preparer.validate_model_runtime(runtime, root)
            runtime.write_bytes(b"modified runtime")
            with self.assertRaises(ValueError):
                preparer.validate_model_runtime(runtime, root)
            runtime.write_bytes(b"synthetic runtime")
            source.write_bytes(b"modified source")
            with self.assertRaises(ValueError):
                preparer.validate_model_runtime(runtime, root)


if __name__ == "__main__":
    unittest.main()
