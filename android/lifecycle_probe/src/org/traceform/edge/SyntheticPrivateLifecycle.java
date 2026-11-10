// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Packaged participant cleanup while waiting for a synthetic TLS peer. */
final class SyntheticPrivateLifecycle {
    static JSONObject verify(Context context, File root) throws Exception {
        byte[] inputs = OwnerAuthentication.readBounded(new File(root, "public_inputs.bin"), 224);
        if (inputs.length != 224) throw new SecurityException("fixture statement length rejected");
        StringBuilder session = new StringBuilder();
        for (int index = 0; index < 32; index++) session.append(String.format("%02x", inputs[index < 31 ? 32 + index : 64] & 255));
        File circuit = new File(root, "circuit.txt");
        String[] command = {new File(context.getApplicationInfo().nativeLibraryDir, "libtraceform_private_participant.so").getAbsolutePath(),
            "1", "24886", circuit.getAbsolutePath(), new File(root, "1.pem").getAbsolutePath(),
            new File(root, "1.key").getAbsolutePath(), new File(root, "2.pem").getAbsolutePath(),
            new String(OwnerAuthentication.readBounded(new File(root, "2.pin"), 64), "US-ASCII"), session.toString()};
        String pin = new String(OwnerAuthentication.readBounded(new File(root, "circuit.pin"), 64), "US-ASCII");
        String bits = new String(OwnerAuthentication.readBounded(new File(root, "own.bits"), 638), "US-ASCII");
        JSONObject report = new JSONObject();
        for (boolean resources : new boolean[]{false, true}) {
            AtomicBoolean withdrawn = new AtomicBoolean(), preserved = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    PrivateParticipant.runPinnedCircuit(command, circuit, context.getCacheDir(), pin, bits, inputs,
                        64L * 1024 * 1024, () -> {
                            if (withdrawn.get()) throw new IllegalStateException("synthetic resource permission withdrawn");
                        });
                    failure.set(new AssertionError("waiting participant returned"));
                } catch (Throwable error) { failure.set(error); preserved.set(Thread.currentThread().isInterrupted()); }
            }, "synthetic-private-lifecycle");
            int child = 0;
            try {
                worker.start();
                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while (child == 0 && worker.isAlive() && android.os.SystemClock.elapsedRealtime() < deadline) {
                    File[] processes = new File("/proc").listFiles();
                    if (processes != null) for (File process : processes) {
                        if (!process.getName().matches("[0-9]+")) continue;
                        try {
                            if (android.system.Os.stat(process.getAbsolutePath()).st_uid != android.os.Process.myUid()) continue;
                            String args = new String(Files.readAllBytes(new File(process, "cmdline").toPath()), "UTF-8");
                            if (args.contains("libtraceform_private_participant.so") && args.contains("24886")
                                    && args.contains("private-circuit-")) child = Integer.parseInt(process.getName());
                        } catch (java.io.IOException | android.system.ErrnoException unavailable) { }
                    }
                    if (child == 0) Thread.sleep(10);
                }
                if (child == 0 || !worker.isAlive()) throw new IllegalStateException("live participant not observed", failure.get());
                if (resources) withdrawn.set(true); else worker.interrupt();
                worker.join(3000);
                Throwable error = failure.get();
                boolean expected = resources ? error instanceof IllegalStateException
                    && "synthetic resource permission withdrawn".equals(error.getMessage())
                    : error instanceof InterruptedException && preserved.get();
                if (worker.isAlive() || !expected || new File("/proc/" + child).exists())
                    throw new IllegalStateException("participant cleanup failed", error);
                File[] snapshots = context.getCacheDir().listFiles();
                if (snapshots != null) for (File file : snapshots)
                    if (file.getName().startsWith("private-circuit-")) throw new IllegalStateException("snapshot retained");
                report.put(resources ? "resource_withdrawal" : "cancellation", new JSONObject()
                    .put("passed", true).put("observed_child_pid", child).put("child_reaped", true).put("snapshot_removed", true));
            } finally { worker.interrupt(); worker.join(3000); }
        }
        return report.put("passed", true).put("scope", "packaged_two_node_participant_waiting_for_TLS_peer_not_sixteen_node_CPU_or_real_low_memory_phase");
    }
}
