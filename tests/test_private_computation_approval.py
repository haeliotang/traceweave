from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestPrivateComputationApproval(unittest.TestCase):
    def test_distinct_owner_approvals_bind_circuit_session_and_models(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory, 'ComputationApprovalTest.java')
            harness.write_text('''package com.inceptless.traceweave.edge;
import java.io.File;
import java.nio.file.Files;
import java.security.*;
public final class ComputationApprovalTest {
    static void sign(File root, KeyPair[] owners, String pin, byte[] body, String domain) throws Exception {
        for (int owner=0;owner<2;owner++) {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(owners[owner].getPrivate());
            signer.update(domain.getBytes("UTF-8"));signer.update((byte)owner);
            signer.update(pin.getBytes("US-ASCII"));signer.update(body);
            Files.write(new File(root,"owner"+owner+".sig").toPath(),signer.sign());
        }
    }
    static void reject(File root,String pin,PublicKey[] owners) throws Exception {
        try { OwnerAuthentication.verifyComputation(root,pin,owners); }
        catch (Exception expected) { return; }
        throw new AssertionError("invalid computation approval accepted");
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");generator.initialize(256);
        KeyPair[] keys = {generator.generateKeyPair(),generator.generateKeyPair()};
        PublicKey[] owners = {keys[0].getPublic(),keys[1].getPublic()};
        String pin = new String(new char[64]).replace('\\0','a');
        byte[] body = new byte[192];body[0]=1;body[64]=2;body[128]=3;
        Files.write(new File(root,"bindings.bin").toPath(),body);
        sign(root,keys,pin,body,"traceform.gw.compute-approval.v2\\0");
        byte[] approved = OwnerAuthentication.verifyComputation(root,pin,owners);
        if (!java.util.Arrays.equals(body,approved)) throw new AssertionError("approved bindings changed");
        reject(root,pin,new PublicKey[]{owners[1],owners[0]});
        reject(root,pin,new PublicKey[]{owners[0],owners[0]});
        reject(root,pin,new PublicKey[]{owners[0],null});reject(root,pin,null);
        reject(root,"b"+pin.substring(1),owners);reject(root,"x",owners);
        for (int offset:new int[]{0,32,64,96,128,160}) {
            byte[] changed=body.clone();changed[offset]^=1;
            Files.write(new File(root,"bindings.bin").toPath(),changed);reject(root,pin,owners);
        }
        for (int offset:new int[]{31,33,63,95,97,127,159,161,191}) {
            byte[] changed=body.clone();changed[offset]=1;
            Files.write(new File(root,"bindings.bin").toPath(),changed);
            sign(root,keys,pin,changed,"traceform.gw.compute-approval.v2\\0");
            reject(root,pin,owners);
        }
        Files.write(new File(root,"bindings.bin").toPath(),new byte[193]);reject(root,pin,owners);
        Files.write(new File(root,"bindings.bin").toPath(),body);
        sign(root,keys,pin,body,"traceform.gw.owner-approval.v1\\0");reject(root,pin,owners);
        sign(root,keys,pin,body,"traceform.gw.compute-approval.v2\\0");
        byte[] signature=Files.readAllBytes(new File(root,"owner0.sig").toPath());signature[signature.length-1]^=1;
        Files.write(new File(root,"owner0.sig").toPath(),signature);reject(root,pin,owners);
    }
}''')
            result = subprocess.run(['javac', '--release', '8', '-d', directory,
                str(root / 'android/src/com/inceptless/traceweave/edge/OwnerAuthentication.java'), str(harness)], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.ComputationApprovalTest', directory],
                                    capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
