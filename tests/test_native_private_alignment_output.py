from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from traceform.dynamics.alignment_statement import SCALE


class TestNativePrivateAlignmentOutput(unittest.TestCase):
    def test_full_statement_match_and_rejection_boundaries(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        binding = bytes(range(96))
        bits = '1' + format(SCALE ** 4, '080b')[::-1] + ''.join(format(v, '08b')[::-1] for v in binding)
        statement = (SCALE ** 4).to_bytes(32, 'little')
        for offset in range(0, 96, 32):
            statement += binding[offset:offset + 31] + b'\0' + binding[offset + 31:offset + 32] + b'\0' * 31
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'bits.txt').write_text(bits)
            Path(directory, 'inputs.bin').write_bytes(statement)
            harness = Path(directory, 'PrivateOutputTest.java')
            harness.write_text('''package com.inceptless.traceweave.edge;
import java.nio.file.Files;
import java.nio.file.Paths;
public final class PrivateOutputTest {
    static void reject(String bits, byte[] inputs) {
        try { PrivateAlignmentOutput.verify(bits, inputs); }
        catch (SecurityException expected) { return; }
        throw new AssertionError("invalid statement accepted");
    }
    public static void main(String[] args) throws Exception {
        String bits = new String(Files.readAllBytes(Paths.get(args[0], "bits.txt")), "UTF-8");
        byte[] inputs = Files.readAllBytes(Paths.get(args[0], "inputs.bin"));
        PrivateAlignmentOutput.verify(bits, inputs);
        for (int offset : new int[]{0, 1, 81, 337, 593}) {
            char[] changed = bits.toCharArray();
            changed[offset] = changed[offset] == '1' ? '0' : '1';
            reject(new String(changed), inputs);
        }
        for (int offset : new int[]{10, 63, 65, 127, 129, 191, 193}) {
            byte[] changed = inputs.clone(); changed[offset] = 1;
            reject(bits, changed);
        }
        byte[] swapped = inputs.clone();
        System.arraycopy(inputs, 160, swapped, 96, 64);
        System.arraycopy(inputs, 96, swapped, 160, 64);
        reject(bits, swapped);
        reject(null, inputs); reject(bits.substring(1), inputs);
        reject("x" + bits.substring(1), inputs); reject(bits + "0", inputs);
        reject(bits, null); reject(bits, new byte[223]);
        reject("1" + new String(new char[80]).replace('\\0', '1') + bits.substring(81), inputs);
    }
}''')
            subprocess.run(['javac', '--release', '8', '-d', directory,
                            str(root / 'android/src/com/inceptless/traceweave/edge/PrivateAlignmentOutput.java'),
                            str(harness)], check=True, capture_output=True)
            subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.PrivateOutputTest', directory],
                           check=True, capture_output=True)
