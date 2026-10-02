from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.verify_uniform_private_sdk_usb import statement_bytes


class TestPrivateSdkUsbStatement(unittest.TestCase):
    def test_prepared_statement_uses_independent_sdk_verifier(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        objective = (1 << 79) + (1 << 64) + 17
        bindings = bytes(range(32)) + bytes([255] * 32) + bytes(reversed(range(32)))
        bits = '1' + format(objective, '080b')[::-1]
        bits += ''.join(format(value, '08b')[::-1] for value in bindings)
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / 'bits.txt').write_text(bits)
            (base / 'statement.bin').write_bytes(statement_bytes(bits, True))
            (base / 'rejected.bin').write_bytes(statement_bytes('0' * 849, False))
            harness = base / 'StatementTest.java'
            harness.write_text('''import org.traceform.edge.PrivateAlignmentOutput;
import java.nio.file.*;
public class StatementTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        String bits = new String(Files.readAllBytes(root.resolve("bits.txt")), "US-ASCII");
        byte[] statement = Files.readAllBytes(root.resolve("statement.bin"));
        PrivateAlignmentOutput.verify(bits, statement);
        for (int index : new int[]{0,9,32,64,96,128,160,192,223}) {
            byte[] changed = statement.clone(); changed[index] ^= 1;
            boolean rejected = false;
            try { PrivateAlignmentOutput.verify(bits, changed); }
            catch (SecurityException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("changed statement accepted");
        }
        byte[] invalid = Files.readAllBytes(root.resolve("rejected.bin"));
        for (int index=0;index<31;index++)
            if (invalid[32+index] != index) throw new AssertionError("transport session changed");
        if (invalid[64] != 31) throw new AssertionError("last transport session byte changed");
        try { PrivateAlignmentOutput.verify(new String(new char[849]).replace('\\0','0'), invalid); }
        catch (SecurityException expected) {
            if (!expected.getMessage().equals("private alignment witness rejected")) throw expected;
            return;
        }
        throw new AssertionError("invalid output accepted");
    }
}''')
            compiled = subprocess.run(['javac', '--release', '8', '-d', directory,
                str(root / 'android/src/org/traceform/edge/PrivateAlignmentOutput.java'), str(harness)], capture_output=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr.decode())
            result = subprocess.run(['java', '-cp', directory, 'StatementTest', directory], capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
