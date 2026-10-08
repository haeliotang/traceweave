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
        if (!mode.equals("cancel") && !mode.equals("withdraw")) throw new IllegalArgumentException("unknown lifecycle case");
        String pin = "ba00463c74f7899a069305ab7edf8041234dea76ae6fb5a430182191c924dca2";
        File signal = new File(root, "withdraw.signal");
        int[] child = {0};
        boolean interrupted = false;
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquire(context.getFilesDir())) {
            byte[] bindings = OwnerAuthentication.verifyComputation(root, pin, owners);
            byte[] session = java.util.Arrays.copyOfRange(bindings, 0, 32); session[31] = bindings[32];
            ComputationSessions.checkAvailable(context.getFilesDir(), session, lease);
            Runnable resources = UniformPrivateAlignment.admit(context, bits);
            try {
                PrivateParticipant.runPinnedComputation(command, circuit, context.getCacheDir(), pin,
                    UniformPrivateAlignment.approvedInputs(bits, bindings), bindings, 128L * 1024 * 1024, () -> {
                        resources.run();
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
                            Thread.currentThread().interrupt();
                        }
                    }, () -> { ComputationSessions.reserve(context.getFilesDir(), session, lease); return null; });
                throw new IllegalStateException("withdrawal did not abort computation");
            } catch (InterruptedException error) {
                if (!mode.equals("cancel") || !Thread.currentThread().isInterrupted()) throw error;
                interrupted = true;
                // The fixture must publish its cleanup receipt; preserve the evidence first.
                Thread.interrupted();
            } catch (IllegalStateException error) {
                if (!mode.equals("withdraw") || !error.getMessage().equals("synthetic resource permission withdrawn")) throw error;
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
            .put("replay_rejected", true).put("scope", "synthetic_permission_withdrawal_not_system_OOM");
    }
}
