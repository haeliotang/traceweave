from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class TestApprovedPrivateAlignment(unittest.TestCase):
    def test_signatures_and_key_pin_gate_private_input_release(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory, 'child.py')
            script.write_text("import sys\nfrom pathlib import Path\nassert sys.stdin.read() == '101\\n'\nPath(sys.argv[3]).write_text('launched')\nprint('PUBLIC_OUTPUT=' + '1' + '0'*848)\n")
            harness = Path(directory, 'ApprovedTest.java')
            harness.write_text('''package com.inceptless.traceweave.edge;
import java.io.File;
import java.nio.file.Files;
import java.security.*;
public final class ApprovedTest {
    static String hash(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) value.append(String.format("%02x", b & 255));
        return value.toString();
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[2]), marker = new File(root, "launched");
        File circuit = new File(root, "circuit"); Files.write(circuit.toPath(), new byte[]{3});
        byte[] key = new byte[]{7}, inputs = new byte[224], proof = new byte[192];
        String pin = hash(key);
        Files.write(new File(root, "verifying_key.bin").toPath(), key);
        Files.write(new File(root, "public_inputs.bin").toPath(), inputs);
        Files.write(new File(root, "proof.bin").toPath(), proof);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        PublicKey[] owners = new PublicKey[2]; byte[][] signatures = new byte[2][];
        for (int owner = 0; owner < 2; owner++) {
            KeyPair pair = generator.generateKeyPair(); owners[owner] = pair.getPublic();
            Signature signer = Signature.getInstance("SHA256withECDSA"); signer.initSign(pair.getPrivate());
            signer.update("traceform.gw.owner-approval.v1\\0".getBytes("UTF-8")); signer.update((byte)owner);
            signer.update(pin.getBytes("UTF-8")); signer.update(inputs); signer.update(proof);
            signatures[owner] = signer.sign();
            Files.write(new File(root, "owner" + owner + ".sig").toPath(), signatures[owner]);
        }
        String[] command = new String[]{args[0], args[1], "1", "unused", marker.getAbsolutePath(), "", "", "",
            new String(new char[64]).replace('\\0', '0')};
        for (int test = 0; test < 3; test++) {
            if (test == 0) Files.write(new File(root, "owner0.sig").toPath(), signatures[1]);
            if (test == 1) Files.write(new File(root, "verifying_key.bin").toPath(), new byte[]{8});
            if (test == 2) Files.write(new File(root, "proof.bin").toPath(), new byte[191]);
            boolean rejected = false;
            try { ApprovedPrivateAlignment.run(command, circuit, root, hash(new byte[]{3}), root, pin, owners, "101"); }
            catch (Exception expected) { rejected = true; }
            if (!rejected || marker.exists()) throw new AssertionError("unapproved private input released");
            Files.write(new File(root, "owner0.sig").toPath(), signatures[0]);
            Files.write(new File(root, "verifying_key.bin").toPath(), key);
            Files.write(new File(root, "proof.bin").toPath(), proof);
        }
        String output = ApprovedPrivateAlignment.run(command, circuit, root, hash(new byte[]{3}), root, pin, owners, "101");
        if (!marker.exists() || output.length() != 849) throw new AssertionError("approved execution failed");
        Files.delete(marker.toPath());
        File ledger = new File(root, "used.bin");
        byte[][] ledgers = new byte[][]{MessageDigest.getInstance("SHA-256").digest(new byte[32]), new byte[1], new byte[131072]};
        for (byte[] records : ledgers) {
            Files.write(ledger.toPath(), records);
            boolean rejected = false;
            try { ApprovedPrivateAlignment.run(command, circuit, root, hash(new byte[]{3}), root, pin, owners, "101", ledger); }
            catch (SecurityException expected) { rejected = true; }
            if (!rejected || marker.exists()) throw new AssertionError("session preflight released private input");
            if (!java.util.Arrays.equals(records, Files.readAllBytes(ledger.toPath()))) throw new AssertionError("preflight modified ledger");
        }
        Files.write(ledger.toPath(), new byte[0]);
        ApprovedPrivateAlignment.run(command, circuit, root, hash(new byte[]{3}), root, pin, owners, "101", ledger);
        if (!marker.exists() || ledger.length() != 0) throw new AssertionError("empty ledger preflight failed");
        for (File file : root.listFiles())
            if (file.getName().startsWith("approved-alignment-") || file.getName().startsWith("private-circuit-"))
                throw new AssertionError("snapshot retained");
    }
}''')
            sources = ['PrivateAlignmentOutput', 'PrivateParticipant', 'OwnerAuthentication', 'ApprovedPrivateAlignment']
            subprocess.run(['javac', '--release', '8', '-d', directory,
                *(str(root / f'android/src/com/inceptless/traceweave/edge/{name}.java') for name in sources), str(harness)],
                check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.ApprovedTest',
                sys.executable, str(script), directory], capture_output=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
