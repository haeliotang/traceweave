import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "agent_os/android/src/org/traceform/agentos/CrashLog.java"
TEST = ROOT / "agent_os/tests/android/CrashLogHostTest.java"


class CrashLogTests(unittest.TestCase):
    def test_formatting_and_truncation(self):
        with tempfile.TemporaryDirectory() as directory:
            stub = pathlib.Path(directory) / "android" / "content"
            stub.mkdir(parents=True)
            (stub / "Context.java").write_text(
                "package android.content;\n"
                "public class Context {\n"
                "  public java.io.File getFilesDir() { return null; }\n"
                "}\n")
            subprocess.run(["javac", "--release", "8", "-d", directory, str(SOURCE), str(TEST),
                            str(stub / "Context.java")],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "org.traceform.agentos.CrashLogHostTest"],
                                    capture_output=True, text=True, check=True)
            self.assertIn("CRASH_LOG_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
