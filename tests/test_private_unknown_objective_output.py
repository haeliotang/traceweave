from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.verify_uniform_private_sdk_usb import statement_bytes


class TestPrivateUnknownObjectiveOutput(unittest.TestCase):
    def test_unknown_score_preserves_binding_and_exact_statement_boundaries(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        bindings = bytes(range(32)) + bytes([13] * 32) + bytes([27] * 32)
        binding_bits = ''.join(format(value, '08b')[::-1] for value in bindings)
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            for name, value in [('zero', 0), ('large', (1 << 79) + 17), ('overflow', 10**24 + 1)]:
                bits = '1' + format(value, '080b')[::-1] + binding_bits
                (base / (name + '.bits')).write_text(bits)
                if name == 'zero':
                    (base / 'approved.bin').write_bytes(statement_bytes(bits, True)[32:])
                    (base / 'zero.bin').write_bytes(statement_bytes(bits, True))
            harness = base / 'UnknownOutputTest.java'
            harness.write_text('''package com.inceptless.traceweave.edge;
import java.nio.file.*;
import java.util.Arrays;
public final class UnknownOutputTest {
    static void rejects(String bits, byte[] bindings) {
        try { PrivateAlignmentOutput.verifyBindings(bits, bindings); }
        catch (SecurityException expected) { return; }
        throw new AssertionError("bad output/binding accepted");
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        byte[] bindings = Files.readAllBytes(root.resolve("approved.bin"));
        byte[] original = bindings.clone();
        String zero = new String(Files.readAllBytes(root.resolve("zero.bits")), "US-ASCII");
        String large = new String(Files.readAllBytes(root.resolve("large.bits")), "US-ASCII");
        byte[] discovered = PrivateAlignmentOutput.verifyBindings(large, bindings);
        byte[] publicStatement = PrivateAlignmentOutput.encodePublicStatement(large);
        if (!Arrays.equals(publicStatement, discovered)) throw new AssertionError("public statement changed");
        publicStatement[0] ^= 1;
        if (!Arrays.equals(PrivateAlignmentOutput.encodePublicStatement(large), discovered))
            throw new AssertionError("public statement aliases previous result");
        for (String invalid : new String[]{null, large.substring(1), "2"+large.substring(1),
                "0"+large.substring(1), new String(Files.readAllBytes(root.resolve("overflow.bits")),"US-ASCII")}) {
            try { PrivateAlignmentOutput.encodePublicStatement(invalid); }
            catch (SecurityException expected) { continue; }
            throw new AssertionError("invalid public statement exported");
        }
        if (discovered.length != 224 || discovered[0] != 17 || (discovered[9]&128) == 0)
            throw new AssertionError("unknown score truncated");
        PrivateAlignmentOutput.verify(large, discovered);
        if (!Arrays.equals(bindings, original)) throw new AssertionError("approved bindings mutated");
        byte[] knownZero = Files.readAllBytes(root.resolve("zero.bin"));
        PrivateAlignmentOutput.verify(zero, knownZero);
        boolean strict = false;
        try { PrivateAlignmentOutput.verify(large, knownZero); }
        catch (SecurityException expected) { strict = true; }
        if (!strict) throw new AssertionError("existing complete statement check weakened");
        for (int index=0;index<bindings.length;index++) {
            byte[] changed = bindings.clone();changed[index]^=1;
            rejects(large,changed);
        }
        rejects(large,null);rejects(large,new byte[191]);rejects(large,new byte[193]);
        rejects(null,bindings);rejects(large.substring(1),bindings);
        rejects("2"+large.substring(1),bindings);rejects("0"+large.substring(1),bindings);
        rejects(new String(Files.readAllBytes(root.resolve("overflow.bits")),"US-ASCII"),bindings);
        discovered[32]^=1;
        if (!Arrays.equals(bindings,original)) throw new AssertionError("returned statement aliases approvals");
    }
}''')
            compiled = subprocess.run(['javac', '--release', '8', '-d', directory,
                str(root / 'android/src/com/inceptless/traceweave/edge/PrivateAlignmentOutput.java'), str(harness)], capture_output=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr.decode())
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.UnknownOutputTest', directory],
                                    capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
