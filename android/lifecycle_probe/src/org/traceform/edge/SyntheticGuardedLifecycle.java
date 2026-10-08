package org.traceform.edge;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.security.PublicKey;
import org.json.JSONObject;

/** Host signals withdrawal only after observing authenticated MPC and CPU work. */
final class SyntheticGuardedLifecycle {
    static JSONObject verify(Context context, String[] command, File circuit, File root,
                             PublicKey[] owners, String bits, String mode) throws Exception {
        if (!mode.equals("cancel") && !mode.equals("withdraw") && !mode.equals("pressure")) throw new IllegalArgumentException("unknown lifecycle case");
        String pin = "ba00463c74f7899a069305ab7edf8041234dea76ae6fb5a430182191c924dca2";
        File signal = new File(root, "withdraw.signal");
        int[] child = {0};
        boolean interrupted = false;
        org.json.JSONArray memorySamples = new org.json.JSONArray();
        Process[] pressure = {null};
        java.io.BufferedReader[] pressureOutput = {null};
        java.io.OutputStreamWriter[] pressureInput = {null};
        boolean[] pressureEnded = {false};
        JSONObject[] withdrawalMemory = {null};
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquire(context.getFilesDir())) {
            byte[] bindings = OwnerAuthentication.verifyComputation(root, pin, owners);
            byte[] session = java.util.Arrays.copyOfRange(bindings, 0, 32); session[31] = bindings[32];
            ComputationSessions.checkAvailable(context.getFilesDir(), session, lease);
            Runnable resources = UniformPrivateAlignment.admit(context, bits);
            try {
                PrivateParticipant.runPinnedComputation(command, circuit, context.getCacheDir(), pin,
                    UniformPrivateAlignment.approvedInputs(bits, bindings), bindings, 128L * 1024 * 1024, () -> {
                        if (!mode.equals("pressure")) resources.run();
                        if (mode.equals("pressure") && new File(root, "pressure.abort").exists())
                            throw new IllegalStateException("pressure aborted by collector health monitor");
                        if (signal.exists()) {
                            File[] processes = new File("/proc").listFiles();
                            if (processes != null) for (File process : processes) try {
                                if (!process.getName().matches("[0-9]+")
                                    || android.system.Os.stat(process.getAbsolutePath()).st_uid != android.os.Process.myUid()) continue;
                                String args = new String(Files.readAllBytes(new File(process, "cmdline").toPath()), "UTF-8");
                                if (args.contains("libtraceform_private_participant.so") && args.contains("private-circuit-"))
                                    child[0] = Integer.parseInt(process.getName());
                            } catch (java.io.IOException | android.system.ErrnoException unavailable) { }
                            if (child[0] == 0) throw new IllegalStateException("running child not observed");
                            if (mode.equals("withdraw")) throw new IllegalStateException("synthetic resource permission withdrawn");
                            if (mode.equals("cancel")) Thread.currentThread().interrupt();
                            if (mode.equals("pressure") && pressure[0] == null) try {
                                pressure[0] = new ProcessBuilder(new File(context.getApplicationInfo().nativeLibraryDir,
                                    "libtraceform_memory_pressure.so").getAbsolutePath(), "running").start();
                                pressureOutput[0] = new java.io.BufferedReader(new java.io.InputStreamReader(pressure[0].getInputStream()));
                                pressureInput[0] = new java.io.OutputStreamWriter(pressure[0].getOutputStream());
                            } catch (java.io.IOException error) { throw new IllegalStateException("pressure launch failed", error); }
                        }
                        if (mode.equals("pressure") && pressure[0] != null && !pressureEnded[0]) try {
                            String line = pressureOutput[0].readLine();
                            if (line == null) pressureEnded[0] = true;
                            else {
                                JSONObject sample = new JSONObject(line);
                                android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
                                context.getSystemService(android.app.ActivityManager.class).getMemoryInfo(memory);
                                sample.put("available_bytes", memory.availMem).put("threshold_bytes", memory.threshold)
                                    .put("low_memory", memory.lowMemory).put("timestamp_epoch_ms", System.currentTimeMillis());
                                memorySamples.put(sample);
                                try { resources.run(); }
                                catch (IllegalStateException error) { withdrawalMemory[0] = sample; throw error; }
                                pressureInput[0].write("GROW\n"); pressureInput[0].flush();
                                return;
                            }
                        } catch (java.io.IOException | org.json.JSONException error) {
                            throw new IllegalStateException("pressure sampling failed", error);
                        }
                        if (mode.equals("pressure")) resources.run();
                    }, () -> { ComputationSessions.reserve(context.getFilesDir(), session, lease); return null; });
                throw new IllegalStateException("withdrawal did not abort computation");
            } catch (InterruptedException error) {
                if (!mode.equals("cancel") || !Thread.currentThread().isInterrupted()) throw error;
                interrupted = true;
                // The fixture must publish its cleanup receipt; preserve the evidence first.
                Thread.interrupted();
            } catch (IllegalStateException error) {
                if (mode.equals("pressure")) {
                    if (!error.getMessage().equals("private alignment memory reserve exhausted")) throw error;
                    if (withdrawalMemory[0] == null && memorySamples.length() > 0)
                        withdrawalMemory[0] = memorySamples.getJSONObject(memorySamples.length() - 1);
                    if (pressure[0] == null || memorySamples.length() == 0)
                        throw new IllegalStateException("withdrawal before real pressure observation", error);
                } else if (!mode.equals("withdraw") || !error.getMessage().equals("synthetic resource permission withdrawn")) throw error;
            } finally {
                if (pressure[0] != null) {
                    pressure[0].destroyForcibly();
                    if (!pressure[0].waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("pressure child retained");
                }
                if (mode.equals("pressure")) NativeVerificationJob.write(new File(root, "running_pressure_attempt.json"),
                    new JSONObject().put("memory_samples", memorySamples).put("withdrawal_memory", withdrawalMemory[0])
                        .put("observed_child_pid", child[0]).put("pressure_child_started", pressure[0] != null)
                        .put("pressure_child_ended_early", pressureEnded[0]));
            }
        }
        if (child[0] == 0 || new File("/proc/" + child[0]).exists()) throw new IllegalStateException("child retained");
        for (File file : context.getCacheDir().listFiles())
            if (file.getName().startsWith("private-circuit-")) throw new IllegalStateException("snapshot retained");
        boolean replay = false;
        try { UniformPrivateAlignment.computeApproved(context, command, circuit, root, owners, bits); }
        catch (SecurityException error) { replay = error.getMessage().equals("replayed computation session"); }
        if (!replay) throw new IllegalStateException("aborted session reusable");
        return new JSONObject().put("passed", true).put("mode", mode).put("observed_child_pid", child[0])
            .put("child_reaped", true).put("snapshot_removed", true).put("interrupt_preserved_at_cleanup", interrupted)
            .put("replay_rejected", true).put("memory_samples", memorySamples)
            .put("withdrawal_memory", withdrawalMemory[0]).put("pressure_child_reaped", pressure[0] != null)
            .put("withdrawal_reason", mode.equals("pressure") ? "private alignment memory reserve exhausted" : "synthetic_permission_or_cancel")
            .put("scope", mode.equals("pressure") ? "real_allocation_production_running_resource_withdrawal_not_system_LMK"
                : "synthetic_permission_withdrawal_not_system_OOM");
    }
}
