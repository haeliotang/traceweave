from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest


class TestNativePrivateParticipant(unittest.TestCase):
    def test_process_input_output_and_deadline_boundaries(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory, 'participant.py')
            script.write_text('''import sys,time,os
assert sys.stdin.read() == '101\\n'
assert '101' not in sys.argv
bits = '1' + '0' * 848
case = sys.argv[1]
if case == 'pinned':
 from pathlib import Path
 assert Path(sys.argv[2]).read_text() == 'circuit-v1'
 Path(sys.argv[3]).write_text('changed-source')
 assert Path(sys.argv[2]).read_text() == 'circuit-v1'
if case == 'sleep':
 with open(sys.argv[0] + '.pid', 'w') as receipt: receipt.write(str(os.getpid()))
 time.sleep(30)
if case == 'error': sys.exit(1)
if case == 'large': print('x' * 20000)
else:
 print('PUBLIC_OUTPUT=' + ('0' * 849 if case == 'wrong' else bits))
 if case == 'duplicate': print('PUBLIC_OUTPUT=' + bits)
''')
            harness = Path(directory, 'ParticipantTest.java')
            harness.write_text('''package com.inceptless.traceweave.edge;
public final class ParticipantTest {
    public static void main(String[] args) throws Exception {
        byte[] inputs = new byte[224];
        String[] command = new String[]{args[0], args[1], "valid"};
        String bits = PrivateParticipant.run(command, "101", inputs, 3000);
        if (!bits.equals("1" + new String(new char[848]).replace('\\0', '0')))
            throw new AssertionError("valid output changed");
        for (String test : new String[]{"wrong", "duplicate", "large", "error", "sleep"}) {
            command[2] = test;
            boolean rejected = false;
            try { PrivateParticipant.run(command, "101", inputs, test.equals("sleep") ? 100 : 3000); }
            catch (Exception expected) { rejected = true; }
            if (!rejected) throw new AssertionError(test + " accepted");
        }
        command[2] = "valid";
        for (String invalid : new String[]{null, "", "102"}) {
            try { PrivateParticipant.run(command, invalid, inputs, 3000); }
            catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError("invalid input accepted");
        }
        command[2] = "sleep";
        Thread caller = Thread.currentThread();
        Thread cancel = new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException error) { return; }
            caller.interrupt();
        });
        cancel.start();
        boolean cancelled = false;
        try { PrivateParticipant.run(command, "101", inputs, 3000); }
        catch (InterruptedException expected) {
            cancelled = true;
            if (!Thread.currentThread().isInterrupted())
                throw new AssertionError("cancellation status lost");
        } finally { Thread.interrupted(); cancel.join(); }
        if (!cancelled) throw new AssertionError("cancellation ignored");
        Thread.sleep(100);
        for (Thread thread : Thread.getAllStackTraces().keySet())
            if (thread.isAlive() && thread.getName().startsWith("pool-"))
                throw new AssertionError("participant worker remains active");
        java.io.File circuit = new java.io.File(args[1] + ".circuit");
        java.nio.file.Files.write(circuit.toPath(), "circuit-v1".getBytes("US-ASCII"));
        StringBuilder hash = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest("circuit-v1".getBytes("US-ASCII")))
            hash.append(String.format("%02x", value & 255));
        String[] pinned = new String[]{args[0], args[1], "pinned", "unused", circuit.getAbsolutePath(), "", "", "", ""};
        pinned[8] = new String(new char[64]).replace('\\0', '1');
        boolean sessionRejected = false;
        try { PrivateParticipant.runPinnedCircuit(pinned, circuit, circuit.getParentFile(), hash.toString(), "101", inputs); }
        catch (SecurityException expected) { sessionRejected = expected.getMessage().contains("public session configuration rejected"); }
        if (!sessionRejected) throw new AssertionError("different public session accepted before launch");
        if (!new String(java.nio.file.Files.readAllBytes(circuit.toPath()), "US-ASCII").equals("circuit-v1"))
            throw new AssertionError("mismatching session launched child");
        pinned[8] = new String(new char[64]).replace('\\0', '0');
        for (int padding : new int[]{63, 65, 95}) {
            inputs[padding] = 1;
            boolean paddingRejected = false;
            try { PrivateParticipant.runPinnedCircuit(pinned, circuit, circuit.getParentFile(), hash.toString(), "101", inputs); }
            catch (SecurityException expected) { paddingRejected = true; }
            if (!paddingRejected) throw new AssertionError("session padding accepted");
            inputs[padding] = 0;
        }
        PrivateParticipant.runPinnedCircuit(pinned, circuit, circuit.getParentFile(), hash.toString(), "101", inputs);
        if (!new String(java.nio.file.Files.readAllBytes(circuit.toPath()), "US-ASCII").equals("changed-source"))
            throw new AssertionError("source mutation fixture did not execute");
        try { PrivateParticipant.runPinnedCircuit(pinned, circuit, circuit.getParentFile(), hash.toString(), "101", inputs); }
        catch (SecurityException expected) {
            for (java.io.File file : circuit.getParentFile().listFiles())
                if (file.getName().startsWith("private-circuit-")) throw new AssertionError("snapshot retained");
            return;
        }
        throw new AssertionError("changed circuit accepted");
    }
}''')
            subprocess.run(['javac', '--release', '8', '-d', directory,
                str(root / 'android/src/com/inceptless/traceweave/edge/PrivateAlignmentOutput.java'),
                str(root / 'android/src/com/inceptless/traceweave/edge/PrivateParticipant.java'), str(harness)],
                check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.ParticipantTest',
                sys.executable, str(script)], capture_output=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            pid = int(Path(str(script) + '.pid').read_text())
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)
