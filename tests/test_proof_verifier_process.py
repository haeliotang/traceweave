from pathlib import Path
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest


class TestProofVerifierProcess(unittest.TestCase):
    def test_cancelled_verifier_is_reaped_and_interrupt_preserved(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / 'Context.java').write_text('''package android.content;
public class Context {
    private final java.io.File root;
    public Context(java.io.File root) { this.root = root; }
    public java.io.File getFilesDir() { return root; }
    public java.io.File getCacheDir() { return root; }
    public android.content.pm.ApplicationInfo getApplicationInfo() {
        android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo();
        info.nativeLibraryDir = root.getAbsolutePath(); return info;
    }
}''')
            (base / 'ApplicationInfo.java').write_text('package android.content.pm; public class ApplicationInfo { public String nativeLibraryDir; }')
            (base / 'JSONObject.java').write_text('package org.json; public class JSONObject { public JSONObject put(String key, Object value) { return this; } }')
            executable = base / 'libtraceform_zk_verify.so'
            executable.write_text(f'#!{sys.executable}\nimport os,sys,time\nfrom pathlib import Path\nPath(sys.argv[1], "verifier.pid").write_text(str(os.getpid()))\ntime.sleep(30)\n')
            executable.chmod(0o755)
            harness = base / 'ProofProcessTest.java'
            harness.write_text('''package com.inceptless.traceweave.edge;
import java.io.File;
public final class ProofProcessTest {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        java.lang.reflect.Method method = ProofVerifier.class.getDeclaredMethod("verifySnapshot", android.content.Context.class, File.class, String.class);
        method.setAccessible(true);
        Thread caller = Thread.currentThread();
        Thread cancel = new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + 5000;
                while (!new File(root, "verifier.pid").exists() && System.currentTimeMillis() < deadline) Thread.sleep(10);
                caller.interrupt();
            } catch (InterruptedException error) { return; }
        });
        cancel.start();
        try {
            method.invoke(null, new android.content.Context(root), root, new String(new char[64]).replace('\\0', '0'));
            throw new AssertionError("cancelled verification returned");
        } catch (java.lang.reflect.InvocationTargetException error) {
            if (!(error.getCause() instanceof InterruptedException)) throw error;
            if (!Thread.currentThread().isInterrupted()) throw new AssertionError("verifier cancellation status lost");
        } finally { Thread.interrupted(); cancel.join(); }
    }
}''')
            sources = ['ProofVerifier', 'PrivateAlignmentOutput', 'PrivateParticipant', 'OwnerAuthentication', 'ApprovedPrivateAlignment', 'AlignmentQueryLease']
            subprocess.run(['javac', '--release', '8', '-d', directory,
                *(str(root / f'android/src/com/inceptless/traceweave/edge/{name}.java') for name in sources),
                *(str(base / name) for name in ['Context.java', 'ApplicationInfo.java', 'JSONObject.java']), str(harness)],
                check=True, capture_output=True)
            pid = None
            try:
                result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.ProofProcessTest', directory],
                    capture_output=True, timeout=15)
                pid = int((base / 'verifier.pid').read_text())
                try:
                    os.kill(pid, 0)
                    alive = True
                except ProcessLookupError:
                    alive = False
                self.assertEqual(result.returncode, 0, result.stderr.decode() + f' child_alive={alive}')
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
            finally:
                if pid is None and (base / 'verifier.pid').exists():
                    pid = int((base / 'verifier.pid').read_text())
                if pid is not None:
                    try: os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError: pass
