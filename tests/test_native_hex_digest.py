import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeHexDigest(unittest.TestCase):
    def test_all_byte_values_and_sha256_match_reference(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "HexTest.java"
            harness.write_text('''package org.traceform.edge;
public final class HexTest {
    public static void main(String[] args) throws Exception {
        byte[] all = new byte[256];
        for (int index = 0; index < all.length; index++) all[index] = (byte) index;
        System.out.println(HexDigest.encode(all));
        System.out.println(HexDigest.encode(java.security.MessageDigest.getInstance("SHA-256").digest(all)));
        if (!HexDigest.encode(new byte[0]).isEmpty()) throw new AssertionError("empty input");
    }
}''')
            subprocess.run(["javac", "--release", "8", "-d", directory,
                            str(root / "android/src/org/traceform/edge/HexDigest.java"), str(harness)],
                           check=True, capture_output=True)
            output = subprocess.run(["java", "-cp", directory, "org.traceform.edge.HexTest"],
                                    check=True, capture_output=True, text=True)
            self.assertEqual(output.stdout.splitlines(), [bytes(range(256)).hex(), hashlib.sha256(bytes(range(256))).hexdigest()])
