package org.traceform.edge;

import android.app.job.JobService;
import android.app.job.JobParameters;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import org.json.JSONObject;

/** One synthetic participant inside the isolated app; the peer runs on Mac. */
public final class SyntheticPrivateAlignmentJob extends JobService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile Thread worker;
    @Override public boolean onStartJob(JobParameters parameters) {
        if (worker != null) return false;
        Thread task = new Thread(() -> {
            long started = System.nanoTime();
            JSONObject report = new JSONObject();
            try {
                File root = new File(getFilesDir(), "private_uniform16");
                JSONObject fixture = new JSONObject(new String(OwnerAuthentication.readBounded(new File(root, "fixture.json"), 256), "UTF-8"));
                boolean expectedValid = fixture.getBoolean("expected_valid");
                report.put("case", fixture.getString("case")).put("expected_valid", expectedValid);
                boolean approvedComputation = fixture.optBoolean("approved_computation", false);
                java.nio.file.Files.deleteIfExists(new File(root, "discovered_output.bits").toPath());
                java.nio.file.Files.deleteIfExists(new File(root, "discovered_statement.bin").toPath());
                byte[] inputs;
                if (approvedComputation) {
                    inputs = new byte[224];
                    byte[] bindings = OwnerAuthentication.readBounded(new File(root, "bindings.bin"), 192);
                    if (bindings.length != 192) throw new SecurityException("fixture bindings length rejected");
                    System.arraycopy(bindings, 0, inputs, 32, 192);
                } else inputs = OwnerAuthentication.readBounded(new File(root, "public_inputs.bin"), 224);
                String bits = new String(OwnerAuthentication.readBounded(new File(root, "own.bits"), 6224), "US-ASCII");
                String pin = new String(OwnerAuthentication.readBounded(new File(root, "2.pin"), 64), "US-ASCII");
                StringBuilder session = new StringBuilder();
                for (int value = 0; value < 32; value++) session.append(String.format("%02x", inputs[value < 31 ? 32 + value : 64] & 255));
                File circuit = new File(root, "circuit.txt");
                String[] command = {new File(getApplicationInfo().nativeLibraryDir, "libtraceform_private_participant.so").getAbsolutePath(),
                    "1", "24885", circuit.getAbsolutePath(), new File(root, "1.pem").getAbsolutePath(),
                    new File(root, "1.key").getAbsolutePath(), new File(root, "2.pem").getAbsolutePath(), pin, session.toString()};
                android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
                ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
                report.put("available_memory_before_entry_bytes", memory.availMem).put("low_memory_before_entry", memory.lowMemory);
                try {
                    String output;
                    if (approvedComputation) {
                        java.security.PublicKey[] owners = new java.security.PublicKey[2];
                        for (int owner = 0; owner < 2; owner++) owners[owner] = java.security.KeyFactory.getInstance("EC").generatePublic(
                            new java.security.spec.X509EncodedKeySpec(OwnerAuthentication.readBounded(new File(root, "owner" + owner + ".der"), 256)));
                        String lifecycle = fixture.optString("lifecycle", "");
                        if (!lifecycle.isEmpty()) {
                            report.put("lifecycle", SyntheticGuardedLifecycle.verify(this, command, circuit, root, owners, bits, lifecycle));
                            report.put("passed", true);
                            output = null;
                        } else {
                        output = UniformPrivateAlignment.computeApproved(this, command, circuit, root, owners, bits);
                        boolean replayRejected = false;
                        try { UniformPrivateAlignment.computeApproved(this, command, circuit, root, owners, bits); }
                        catch (SecurityException error) { replayRejected = "replayed computation session".equals(error.getMessage()); }
                        if (!replayRejected) throw new IllegalStateException("approved computation replay not rejected");
                        report.put("replay_rejected", true).put("request_excludes_objective", true);
                        }
                    } else output = UniformPrivateAlignment.run(this, command, circuit, bits, inputs);
                    if (output != null) {
                    if (!expectedValid) throw new IllegalStateException("invalid fixture accepted");
                    java.nio.file.Files.write(new File(root, "discovered_output.bits").toPath(), output.getBytes("US-ASCII"));
                    java.nio.file.Files.write(new File(root, "discovered_statement.bin").toPath(), PrivateAlignmentOutput.encodePublicStatement(output));
                    report.put("passed", true).put("output", output);
                    }
                } catch (SecurityException error) {
                    if (expectedValid || !"private alignment witness rejected".equals(error.getMessage())) throw error;
                    report.put("passed", true).put("witness_rejected", true);
                }
            } catch (Exception error) {
                try { report.put("passed", false).put("error", error.toString()); } catch (Exception ignored) {}
            }
            synchronized (this) {
            if (worker == Thread.currentThread() && !Thread.currentThread().isInterrupted()) try {
                report.put("nonce", parameters.getExtras().getString("nonce")).put("job_id", parameters.getJobId())
                    .put("elapsed_ms", (System.nanoTime()-started)/1e6).put("sdk_int", android.os.Build.VERSION.SDK_INT)
                    .put("completed_epoch_ms", System.currentTimeMillis())
                    .put("scope", "synthetic_one_app_private_participant_with_separate_mac_peer_no_collection_no_owner_enrollment");
                NativeVerificationJob.write(new File(getFilesDir(), "private_uniform16.json"), report);
            } catch (Exception error) { android.util.Log.e("TraceformProbe", "private receipt failed", error); }
            }
            Thread completed = Thread.currentThread();
            handler.post(() -> {
                if (worker == completed) { worker = null; jobFinished(parameters, false); }
            });
        }, "synthetic-private-participant");
        worker = task; task.start(); return true;
    }
    @Override public synchronized boolean onStopJob(JobParameters parameters) {
        if (worker != null) { worker.interrupt(); worker = null; }
        return false;
    }
    @Override public synchronized void onDestroy() {
        if (worker != null) { worker.interrupt(); worker = null; }
        super.onDestroy();
    }
}
