from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class LocalModelCatalogTests(unittest.TestCase):
    def test_catalog_lookup_and_budgets(self):
        with tempfile.TemporaryDirectory(prefix="traceform-catalog-") as directory:
            sources = [ROOT / "agent_os/android/src/org/traceform/agentos/LocalModelCatalog.java",
                       ROOT / "agent_os/android/src/org/traceform/agentos/LocalModelStatus.java",
                       ROOT / "agent_os/tests/android/LocalModelCatalogHostTest.java"]
            subprocess.run(["javac", "--release", "8", "-d", directory, *map(str, sources)], check=True)
            result = subprocess.run(["java", "-cp", directory, "org.traceform.agentos.LocalModelCatalogHostTest"],
                                    capture_output=True, text=True, check=True)
            self.assertIn("CATALOG_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
