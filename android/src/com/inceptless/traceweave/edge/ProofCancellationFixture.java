package com.inceptless.traceweave.edge;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

final class ProofCancellationFixture {
    static JSONObject verify(Context context) throws Exception {
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "proof-cancel-").toFile();
        File fifo = new File(root, "verifying_key.bin");
        File ledger = new File(context.getFilesDir(), "zk_sessions.bin");
        byte[] before = ledger.exists() ? OwnerAuthentication.readBounded(ledger, 131072) : new byte[0];
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean preserved = new AtomicBoolean();
        android.system.Os.mkfifo(fifo.getAbsolutePath(), 0600);
        Thread worker = new Thread(() -> {
            try (AlignmentQueryLease lease = AlignmentQueryLease.acquire(context.getFilesDir())) {
                java.lang.reflect.Method method = ProofVerifier.class.getDeclaredMethod("verifySnapshot", Context.class, File.class, String.class);
                method.setAccessible(true);
                try { method.invoke(null, context, root, new String(new char[64]).replace('\0', '0')); }
                catch (java.lang.reflect.InvocationTargetException error) {
                    failure.set(error.getCause()); preserved.set(Thread.currentThread().isInterrupted());
                }
            } catch (Throwable error) { failure.set(error); }
        }, "traceform-proof-cancel-fixture");
        try {
            worker.start();
            int child = 0;
            long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
            while (child == 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
                File[] processes = new File("/proc").listFiles();
                if (processes != null) for (File process : processes) {
                    if (!process.getName().matches("[0-9]+")) continue;
                    try {
                        if (android.system.Os.stat(process.getAbsolutePath()).st_uid != android.os.Process.myUid()) continue;
                        String command = new String(Files.readAllBytes(new File(process, "cmdline").toPath()), "UTF-8");
                        if (command.contains("libtraceweave_zk_verify.so") && command.contains(root.getAbsolutePath()))
                            child = Integer.parseInt(process.getName());
                    } catch (java.io.IOException | android.system.ErrnoException unavailable) { }
                }
                if (child == 0) Thread.sleep(10);
            }
            if (child == 0 || !worker.isAlive()) throw new IllegalStateException("live verifier child not observed");
            worker.interrupt(); worker.join(3000);
            if (worker.isAlive() || !(failure.get() instanceof InterruptedException) || !preserved.get())
                throw new IllegalStateException("proof cancellation failed", failure.get());
            if (new File("/proc/" + child).exists()) throw new IllegalStateException("verifier child retained");
            byte[] after = ledger.exists() ? OwnerAuthentication.readBounded(ledger, 131072) : new byte[0];
            if (!java.util.Arrays.equals(before, after)) throw new IllegalStateException("cancellation modified ledger");
            try (AlignmentQueryLease resumed = AlignmentQueryLease.acquire(context.getFilesDir())) {
                return new JSONObject().put("passed", true).put("observed_child_pid", child)
                    .put("ledger_unchanged", true).put("query_lease_reacquired", true)
                    .put("scope", "packaged_android_verifier_cancelled_while_waiting_for_synthetic_key_fifo_not_proof_cpu_phase");
            }
        } finally {
            worker.interrupt(); worker.join(3000);
            Files.deleteIfExists(fifo.toPath()); Files.deleteIfExists(root.toPath());
        }
    }
}
