// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.app.job.JobService;
import android.app.job.JobParameters;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.security.PublicKey;
import org.json.JSONObject;

/** Synthetic signed proof, optionally bound to MPC with a separate Mac peer. */
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
                if ("lifecycle".equals(label)) report.put("lifecycle", SyntheticPrivateLifecycle.verify(this, root));
                else try {
                    boolean compute = fixture.optBoolean("compute_alignment", false);
                    JSONObject result;
                    if (fixture.optBoolean("bind_discovered", false)) {
                        report.put("discovered_statement_bound", true);
                        String output = new String(OwnerAuthentication.readBounded(
                            new File(getFilesDir(), "private_uniform16/discovered_output.bits"), 849), "US-ASCII");
                        result = ProofVerifier.verifyPrivateAlignment(this, root, pin, owners, output);
                        report.put("mpc_output_bound_to_signed_proof", result.getBoolean("verified"))
                            .put("computation_scope", "synthetic_sixteen_node_discovered_statement_centralized_prover");
                    } else if (compute) {
                        byte[] inputs = OwnerAuthentication.readBounded(new File(root, "public_inputs.bin"), 224);
                        if (inputs.length != 224) throw new SecurityException("fixture statement length rejected");
                        StringBuilder session = new StringBuilder();
                        for (int value = 0; value < 32; value++) session.append(String.format("%02x", inputs[value < 31 ? 32 + value : 64] & 255));
                        File circuit = new File(root, "circuit.txt");
                        String peerPin = new String(OwnerAuthentication.readBounded(new File(root, "2.pin"), 64), "US-ASCII");
                        String circuitPin = new String(OwnerAuthentication.readBounded(new File(root, "circuit.pin"), 64), "US-ASCII");
                        String bits = new String(OwnerAuthentication.readBounded(new File(root, "own.bits"), 638), "US-ASCII");
                        String[] command = {new File(getApplicationInfo().nativeLibraryDir, "libtraceform_private_participant.so").getAbsolutePath(),
                            "1", "24885", circuit.getAbsolutePath(), new File(root, "1.pem").getAbsolutePath(),
                            new File(root, "1.key").getAbsolutePath(), new File(root, "2.pem").getAbsolutePath(), peerPin, session.toString()};
                        result = ProofVerifier.computeAndVerify(this, root, pin, owners, command, circuit, circuitPin, bits);
                        report.put("mpc_output_bound_to_signed_proof", result.getBoolean("verified"))
                            .put("computation_scope", "synthetic_two_node_known_statement_separate_mac_peer");
                    } else result = ProofVerifier.verify(this, root, pin, owners);
                    if ("bad_owner".equals(label) || "bad_binding".equals(label)) throw new IllegalStateException("invalid package accepted");
                    boolean valid = result.getBoolean("verified");
                    if (valid != "valid".equals(label)) throw new IllegalStateException("unexpected proof result");
                    String receipt = result.getString("receipt");
                    if ("bad_proof".equals(label) && !receipt.contains("proof rejected"))
                        throw new IllegalStateException("unexpected proof rejection");
                    if ("replay".equals(label) && !receipt.contains("replayed session"))
                        throw new IllegalStateException("unexpected replay rejection");
                    report.put("verifier", result);
                } catch (SecurityException error) {
                    if ("bad_owner".equals(label) && "owner signature rejected".equals(error.getMessage()))
                        report.put("signature_rejected", true);
                    else if ("bad_binding".equals(label)
                             && "private alignment output does not match public statement".equals(error.getMessage()))
                        report.put("binding_rejected", true);
                    else if (fixture.optBoolean("compute_alignment", false) && "replay".equals(label)
                             && "replayed session".equals(error.getMessage()))
                        report.put("mpc_replay_rejected_before_launch", true);
                    else throw error;
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
                        .put("scope", "lifecycle".equals(report.optString("case"))
                            ? "synthetic_packaged_private_participant_cleanup_no_proof_verification_or_collection"
                            : report.optBoolean("discovered_statement_bound", false)
                                ? "synthetic_sixteen_node_discovered_packed_v2_signed_proof_centralized_prover_no_collection"
                                : "synthetic_two_node_signed_packed_v2_public_proof_in_app_UID_no_collection_no_owner_enrollment");
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
