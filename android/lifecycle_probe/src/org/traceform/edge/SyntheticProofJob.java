package org.traceform.edge;

import android.app.job.JobService;
import android.app.job.JobParameters;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.security.PublicKey;
import org.json.JSONObject;

/** Synthetic signed public proof inside the isolated application UID. */
public final class SyntheticProofJob extends JobService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile Thread worker;
    @Override public boolean onStartJob(JobParameters parameters) {
        if (worker != null) return false;
        Thread task = new Thread(() -> {
            long started = System.nanoTime();
            JSONObject report = new JSONObject();
            try {
                File root = new File(getFilesDir(), "proof_v2");
                JSONObject fixture = new JSONObject(new String(OwnerAuthentication.readBounded(new File(root, "fixture.json"), 256), "UTF-8"));
                String label = fixture.getString("case");
                String pin = fixture.getString("trusted_key_hash");
                PublicKey[] owners = new PublicKey[2];
                for (int owner = 0; owner < 2; owner++) owners[owner] = java.security.KeyFactory.getInstance("EC").generatePublic(
                    new java.security.spec.X509EncodedKeySpec(OwnerAuthentication.readBounded(new File(root, "owner" + owner + ".der"), 256)));
                report.put("case", label);
                try {
                    JSONObject result = ProofVerifier.verify(this, root, pin, owners);
                    if ("bad_owner".equals(label)) throw new IllegalStateException("invalid owner accepted");
                    boolean valid = result.getBoolean("verified");
                    if (valid != "valid".equals(label)) throw new IllegalStateException("unexpected proof result");
                    String receipt = result.getString("receipt");
                    if ("bad_proof".equals(label) && !receipt.contains("proof rejected"))
                        throw new IllegalStateException("unexpected proof rejection");
                    if ("replay".equals(label) && !receipt.contains("replayed session"))
                        throw new IllegalStateException("unexpected replay rejection");
                    report.put("verifier", result);
                } catch (SecurityException error) {
                    if (!"bad_owner".equals(label) || !"owner signature rejected".equals(error.getMessage())) throw error;
                    report.put("signature_rejected", true);
                }
                long bytes = new File(getFilesDir(), "zk_sessions.bin").length();
                long expectedBytes = "valid".equals(label) || "replay".equals(label) ? 32 : 0;
                if (bytes != expectedBytes) throw new IllegalStateException("unexpected session consumption");
                report.put("ledger_bytes", bytes).put("passed", true);
            } catch (Exception error) {
                try { report.put("passed", false).put("error", error.toString()); } catch (Exception ignored) {}
            }
            synchronized (this) {
                if (worker == Thread.currentThread() && !Thread.currentThread().isInterrupted()) try {
                    report.put("nonce", parameters.getExtras().getString("nonce")).put("job_id", parameters.getJobId())
                        .put("elapsed_ms", (System.nanoTime()-started)/1e6).put("sdk_int", android.os.Build.VERSION.SDK_INT)
                        .put("completed_epoch_ms", System.currentTimeMillis())
                        .put("scope", "synthetic_two_node_signed_packed_v2_public_proof_in_app_UID_no_collection_no_owner_enrollment");
                    NativeVerificationJob.write(new File(getFilesDir(), "proof_v2.json"), report);
                } catch (Exception error) { android.util.Log.e("TraceformProbe", "proof receipt failed", error); }
            }
            Thread completed = Thread.currentThread();
            handler.post(() -> {
                if (worker == completed) { worker = null; jobFinished(parameters, false); }
            });
        }, "synthetic-proof-verifier");
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
