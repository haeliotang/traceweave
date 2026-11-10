# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class TestApprovedUnknownComputation(unittest.TestCase):
    def test_approval_reservation_launch_and_unknown_output_order(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            script = base / 'participant.py'
            script.write_text('''import sys
from pathlib import Path
ledger=Path(sys.argv[3])
assert ledger.exists() and ledger.stat().st_size % 32 == 0
count=Path(sys.argv[0]+'.count');count.write_text(str(int(count.read_text())+1) if count.exists() else '1')
assert sys.stdin.read() == '101\\n'
assert '101' not in sys.argv
if sys.argv[1] == 'fail': sys.exit(1)
score=42
print('PUBLIC_OUTPUT='+'1'+format(score,'080b')[::-1]+'0'*768)
''')
            harness = base / 'ApprovedUnknownTest.java'
            harness.write_text('''package com.inceptless.traceweave.edge;
import java.io.File;
import java.nio.file.Files;
import java.security.*;
public final class ApprovedUnknownTest {
    static String run(File root,File circuit,String[] command,String hash,PublicKey[] owners,Runnable resources) throws Exception {
        try (AlignmentQueryLease lease=AlignmentQueryLease.acquire(root)) {
            byte[] approved=OwnerAuthentication.verifyComputation(root,hash,owners);
            byte[] session=java.util.Arrays.copyOfRange(approved,0,32);session[31]=approved[32];
            return PrivateParticipant.runPinnedComputation(command,circuit,root,hash,"101",approved,128L*1024*1024,resources,() -> {
                ComputationSessions.reserve(root,session,lease);return null;
            });
        }
    }
    public static void main(String[] args) throws Exception {
        File root=new File(args[0]),circuit=new File(root,"circuit.txt"),ledger=new File(root,"computation_sessions.bin");
        byte[] circuitBytes={1,2,3};Files.write(circuit.toPath(),circuitBytes);
        StringBuilder pin=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(circuitBytes)) pin.append(String.format("%02x",b&255));
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(256);
        KeyPair[] keys={generator.generateKeyPair(),generator.generateKeyPair()};
        PublicKey[] owners={keys[0].getPublic(),keys[1].getPublic()};
        byte[] bindings=new byte[192];Files.write(new File(root,"bindings.bin").toPath(),bindings);
        for(int owner=0;owner<2;owner++) {
            Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(keys[owner].getPrivate());
            signer.update("traceform.gw.compute-approval.v2\\0".getBytes("UTF-8"));signer.update((byte)owner);
            signer.update(pin.toString().getBytes("US-ASCII"));signer.update(bindings);
            Files.write(new File(root,"owner"+owner+".sig").toPath(),signer.sign());
        }
        String[] command={args[1],args[2],"valid","unused",ledger.getAbsolutePath(),"","","",
            new String(new char[64]).replace('\\0','0')};
        boolean rejected=false;
        try { run(root,circuit,command,pin.toString(),new PublicKey[]{owners[1],owners[0]},() -> {}); }
        catch(SecurityException expected) { rejected=true; }
        if(!rejected || ledger.exists()) throw new AssertionError("bad approval consumed session");
        Files.write(circuit.toPath(),new byte[]{9});rejected=false;
        try { run(root,circuit,command,pin.toString(),owners,() -> {}); }
        catch(SecurityException expected) { rejected=expected.getMessage().equals("untrusted circuit"); }
        if(!rejected || ledger.exists()) throw new AssertionError("bad circuit consumed session");
        Files.write(circuit.toPath(),circuitBytes);rejected=false;
        try { run(root,circuit,command,pin.toString(),owners,() -> {throw new IllegalStateException("no resources");}); }
        catch(IllegalStateException expected) { rejected=true; }
        if(!rejected || ledger.exists()) throw new AssertionError("resource rejection consumed session");
        String output=run(root,circuit,command,pin.toString(),owners,() -> {});
        byte[] statement=PrivateAlignmentOutput.verifyBindings(output,bindings);
        if(statement[0]!=42 || ledger.length()!=32) throw new AssertionError("unknown score or reservation missing");
        rejected=false;
        try { run(root,circuit,command,pin.toString(),owners,() -> {}); }
        catch(SecurityException expected) { rejected=expected.getMessage().equals("replayed computation session"); }
        if(!rejected || !new String(Files.readAllBytes(new File(args[2]+".count").toPath()),"US-ASCII").equals("1"))
            throw new AssertionError("replay launched participant");
        bindings[0]=1;Files.write(new File(root,"bindings.bin").toPath(),bindings);
        for(int owner=0;owner<2;owner++) {
            Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(keys[owner].getPrivate());
            signer.update("traceform.gw.compute-approval.v2\\0".getBytes("UTF-8"));signer.update((byte)owner);
            signer.update(pin.toString().getBytes("US-ASCII"));signer.update(bindings);
            Files.write(new File(root,"owner"+owner+".sig").toPath(),signer.sign());
        }
        command[8]="01"+command[8].substring(2);command[2]="fail";rejected=false;
        try { run(root,circuit,command,pin.toString(),owners,() -> {}); }
        catch(SecurityException expected) { rejected=expected.getMessage().equals("private computation rejected"); }
        if(!rejected || ledger.length()!=64) throw new AssertionError("failed computation released reservation");
        rejected=false;
        try { run(root,circuit,command,pin.toString(),owners,() -> {}); }
        catch(SecurityException expected) { rejected=expected.getMessage().equals("replayed computation session"); }
        if(!rejected || !new String(Files.readAllBytes(new File(args[2]+".count").toPath()),"US-ASCII").equals("2"))
            throw new AssertionError("failed session replay launched participant");
        for(File file:root.listFiles()) if(file.getName().startsWith("private-circuit-")) throw new AssertionError("snapshot retained");
    }
}''')
            names = ['OwnerAuthentication', 'PrivateAlignmentOutput', 'PrivateParticipant', 'AlignmentQueryLease', 'ComputationSessions']
            result = subprocess.run(['javac', '--release', '8', '-d', directory,
                *[str(root / 'android/src/com/inceptless/traceweave/edge' / (name + '.java')) for name in names], str(harness)], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.ApprovedUnknownTest', directory,
                                     sys.executable, str(script)], capture_output=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
