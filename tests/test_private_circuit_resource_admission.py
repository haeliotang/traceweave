# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest


class TestPrivateCircuitResourceAdmission(unittest.TestCase):
    def test_large_snapshot_limits_and_running_resource_rejection(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory, 'participant.py')
            script.write_text('''import sys,time,os
assert sys.stdin.read() == '101\\n'
if sys.argv[1] == 'sleep':
 open(sys.argv[0]+'.pid','w').write(str(os.getpid()))
 time.sleep(30)
print('PUBLIC_OUTPUT='+'1'+'0'*848)
''')
            harness = Path(directory, 'ResourceTest.java')
            harness.write_text('''package com.inceptless.traceweave.edge;
import java.io.File;
import java.nio.file.Files;
public final class ResourceTest {
    static String hash(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream source = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = source.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
    static void clean(File directory) {
        for (File file : directory.listFiles())
            if (file.getName().startsWith("private-circuit-")) throw new AssertionError("snapshot retained");
    }
    public static void main(String[] args) throws Exception {
        File file = new File(args[1]+".circuit"), cache = file.getParentFile();
        try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(file, "rw")) {
            output.setLength(65L*1024*1024);
        }
        byte[] inputs = new byte[224];
        String[] command = {args[0],args[1],"valid","unused","","","","",
            new String(new char[64]).replace('\\0','0')};
        String digest = hash(file);
        boolean rejected = false;
        try { PrivateParticipant.runPinnedCircuit(command,file,cache,digest,"101",inputs); }
        catch (SecurityException expected) { rejected = expected.getMessage().contains("snapshot exceeds bound"); }
        if (!rejected) throw new AssertionError("legacy quota widened");
        clean(cache);
        PrivateParticipant.runPinnedCircuit(command,file,cache,digest,"101",inputs,128L*1024*1024,() -> {});
        clean(cache);
        try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(file, "rw")) {
            output.setLength(129L*1024*1024);
        }
        rejected = false;
        try { PrivateParticipant.runPinnedCircuit(command,file,cache,digest,"101",inputs,128L*1024*1024,() -> {}); }
        catch (SecurityException expected) { rejected = expected.getMessage().contains("snapshot exceeds bound"); }
        if (!rejected) throw new AssertionError("large quota ignored");
        clean(cache);
        command[2] = "sleep";
        long start = System.nanoTime();
        try {
            PrivateParticipant.run(command,"101",inputs,3000,() -> {
                if (System.nanoTime()-start > 500000000L) throw new SecurityException("resources withdrawn");
            });
            throw new AssertionError("running resource failure ignored");
        } catch (SecurityException expected) {
            if (!expected.getMessage().equals("resources withdrawn")) throw expected;
        }
        Thread.sleep(100);
        clean(cache);
    }
}''')
            compiled = subprocess.run(['javac', '--release', '8', '-d', directory,
                str(root / 'android/src/com/inceptless/traceweave/edge/PrivateAlignmentOutput.java'),
                str(root / 'android/src/com/inceptless/traceweave/edge/PrivateParticipant.java'), str(harness)],
                capture_output=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr.decode())
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.ResourceTest',
                sys.executable, str(script)], capture_output=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            pid = int(Path(str(script) + '.pid').read_text())
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)

    def test_android_uniform_admission_and_fixed_circuit_pin(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            manager = base / 'ActivityManager.java'
            manager.write_text('''package android.app;
public class ActivityManager {
    public long available; public boolean low;
    public static class MemoryInfo { public long availMem; public boolean lowMemory; }
    public void getMemoryInfo(MemoryInfo info) { info.availMem=available; info.lowMemory=low; }
}''')
            context = base / 'Context.java'
            context.write_text('''package android.content;
public class Context {
    public static final String ACTIVITY_SERVICE="activity";
    public android.app.ActivityManager manager = new android.app.ActivityManager();
    public java.io.File cache;
    public Object getSystemService(String name) { return manager; }
    public java.io.File getCacheDir() { return cache; }
    public java.io.File getFilesDir() { return cache; }
}''')
            harness = base / 'AdmissionTest.java'
            harness.write_text('''package com.inceptless.traceweave.edge;
public class AdmissionTest {
    public static void main(String[] args) throws Exception {
        android.content.Context context = new android.content.Context();
        context.cache = new java.io.File(args[0]);
        java.io.File circuit = new java.io.File(context.cache,"bad-circuit.txt");
        java.nio.file.Files.write(circuit.toPath(),new byte[]{1});
        String bits = new String(new char[6224]).replace('\\0','0');
        byte[] approved = new byte[192];
        for (int block=0;block<3;block++) {
            for (int i=0;i<31;i++) approved[block*64+i]=(byte)(i+block);
            approved[block*64+32]=(byte)(255-block);
        }
        String complete=UniformPrivateAlignment.approvedInputs(bits,approved);
        if (complete.length()!=6992 || !complete.startsWith(bits)) throw new AssertionError("approved input shape");
        for (int block=0;block<3;block++) for (int i=0;i<32;i++) for (int bit=0;bit<8;bit++) {
            int value=approved[block*64+(i==31 ? 32 : i)]&255;
            if (complete.charAt(6224+block*256+i*8+bit)!=('0'+((value>>>bit)&1)))
                throw new AssertionError("approved binding byte order");
        }
        if (approved[32]!=(byte)255) throw new AssertionError("caller input mutated");
        byte[] inputs = new byte[224];
        String[] command = {"never-execute","1","23817","unused","","","","",
            new String(new char[64]).replace('\\0','0')};
        for (int test=0;test<2;test++) {
            context.manager.available = (test==0 ? 928L : 1200L)*1024*1024;
            context.manager.low = test==1;
            try { UniformPrivateAlignment.run(context,command,circuit,bits,inputs); }
            catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("memory admission rejected")) throw expected;
                continue;
            }
            throw new AssertionError("insufficient memory accepted");
        }
        // A spent approved session is rejected before low-memory admission.
        java.security.KeyPairGenerator keys=java.security.KeyPairGenerator.getInstance("EC");keys.initialize(256);
        java.security.KeyPair[] pairs={keys.generateKeyPair(),keys.generateKeyPair()};
        java.security.PublicKey[] owners={pairs[0].getPublic(),pairs[1].getPublic()};
        byte[] replayBindings=new byte[192];
        java.nio.file.Files.write(new java.io.File(context.cache,"bindings.bin").toPath(),replayBindings);
        String approvedPin="ba00463c74f7899a069305ab7edf8041234dea76ae6fb5a430182191c924dca2";
        for(int owner=0;owner<2;owner++) {
            java.security.Signature signer=java.security.Signature.getInstance("SHA256withECDSA");
            signer.initSign(pairs[owner].getPrivate());
            signer.update("traceform.gw.compute-approval.v2\\0".getBytes("UTF-8"));signer.update((byte)owner);
            signer.update(approvedPin.getBytes("US-ASCII"));signer.update(replayBindings);
            java.nio.file.Files.write(new java.io.File(context.cache,"owner"+owner+".sig").toPath(),signer.sign());
        }
        context.manager.available=0;context.manager.low=true;
        boolean resourceRejected=false;
        try { UniformPrivateAlignment.computeApproved(context,command,circuit,context.cache,owners,bits); }
        catch(IllegalStateException expected) { resourceRejected=expected.getMessage().equals("private alignment memory admission rejected"); }
        if(!resourceRejected || new java.io.File(context.cache,"computation_sessions.bin").exists())
            throw new AssertionError("preflight consumed unlaunched session");
        try(AlignmentQueryLease lease=AlignmentQueryLease.acquire(context.cache)) {
            ComputationSessions.reserve(context.cache,new byte[32],lease);
        }
        context.manager.available=0;context.manager.low=true;
        java.io.File abandoned = new java.io.File(context.cache,"private-circuit-approved-123.txt");
        java.nio.file.Files.write(abandoned.toPath(),new byte[]{1});
        try { UniformPrivateAlignment.computeApproved(context,command,circuit,context.cache,owners,bits); }
        catch(SecurityException expected) {
            if(!expected.getMessage().equals("replayed computation session")) throw expected;
            if(abandoned.exists()) throw new AssertionError("abandoned approved snapshot retained after replay recovery");
            if(new java.io.File(context.cache,"computation_sessions.bin").length()!=32)
                throw new AssertionError("replay changed ledger");
            for(java.io.File file:context.cache.listFiles())
                if(file.getName().startsWith("private-circuit-")) throw new AssertionError("replay created snapshot");
            context.manager.available=929L*1024*1024;context.manager.low=false;
        }
        if(context.manager.low) throw new AssertionError("replay not rejected before resource admission");
        context.manager.available=929L*1024*1024;context.manager.low=false;
        boolean shapeRejected = false;
        try { UniformPrivateAlignment.run(context,command,circuit,"101",inputs); }
        catch (IllegalArgumentException expected) { shapeRejected = true; }
        if (!shapeRejected) throw new AssertionError("wrong private input shape accepted");
        try { UniformPrivateAlignment.run(context,command,circuit,bits,inputs); }
        catch (SecurityException expected) {
            if (!expected.getMessage().equals("untrusted circuit")) throw expected;
            for (java.io.File file:context.cache.listFiles())
                if (file.getName().startsWith("private-circuit-")) throw new AssertionError("snapshot retained");
            return;
        }
        throw new AssertionError("wrong circuit launched");
    }
}''')
            sources = ['PrivateAlignmentOutput', 'PrivateParticipant', 'UniformPrivateAlignment', 'OwnerAuthentication', 'AlignmentQueryLease', 'ComputationSessions']
            compiled = subprocess.run(['javac', '--release', '8', '-d', directory,
                *[str(root / 'android/src/com/inceptless/traceweave/edge' / (name + '.java')) for name in sources],
                str(manager), str(context), str(harness)], capture_output=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr.decode())
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.AdmissionTest',
                directory], capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
