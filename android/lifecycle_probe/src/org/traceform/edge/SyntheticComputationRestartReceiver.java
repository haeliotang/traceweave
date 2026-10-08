package org.traceform.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.nio.file.Files;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import org.json.JSONObject;

/** Synthetic interrupted reservation; no MPC child, score or private model. */
public final class SyntheticComputationRestartReceiver extends BroadcastReceiver {
    private static final String PIN = "ba00463c74f7899a069305ab7edf8041234dea76ae6fb5a430182191c924dca2";
    @Override public void onReceive(Context context, Intent intent) {
        JSONObject report = new JSONObject();
        try {
            File request = new File(context.getFilesDir(), "restart_computation_request");
            String phase = intent.getStringExtra("phase");
            File ledger = new File(context.getFilesDir(), "computation_sessions.bin");
            if ("reserve".equals(phase)) {
                if (request.exists() || ledger.exists()) throw new IllegalStateException("fresh isolated reservation required");
                if (!request.mkdir()) throw new IllegalStateException("request directory unavailable");
                byte[] bindings = new byte[192];
                byte[] session = new byte[32];new SecureRandom().nextBytes(session);
                System.arraycopy(session, 0, bindings, 0, 31);bindings[32] = session[31];
                Files.write(new File(request, "bindings.bin").toPath(), bindings);
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");generator.initialize(256);
                for (int owner = 0; owner < 2; owner++) {
                    KeyPair keys = generator.generateKeyPair();
                    Files.write(new File(request, "owner" + owner + ".der").toPath(), keys.getPublic().getEncoded());
                    Signature signer = Signature.getInstance("SHA256withECDSA");signer.initSign(keys.getPrivate());
                    signer.update("traceform.gw.compute-approval.v2\0".getBytes("UTF-8"));signer.update((byte) owner);
                    signer.update(PIN.getBytes("US-ASCII"));signer.update(bindings);
                    Files.write(new File(request, "owner" + owner + ".sig").toPath(), signer.sign());
                }
                try (AlignmentQueryLease lease = AlignmentQueryLease.acquire(context.getFilesDir())) {
                    OwnerAuthentication.verifyComputation(request, PIN, owners(request));
                    ComputationSessions.reserve(context.getFilesDir(), session, lease);
                }
                report.put("reservation_bytes", ledger.length()).put("passed", ledger.length() == 32);
            } else if ("replay".equals(phase)) {
                byte[] before = Files.readAllBytes(ledger.toPath());
                boolean rejected = false;
                try {
                    // Unusable input and command establish that replay rejection
                    // happens before admission, circuit access or child creation.
                    UniformPrivateAlignment.computeApproved(context, null, null, request, owners(request), null);
                } catch (SecurityException error) {
                    rejected = "replayed computation session".equals(error.getMessage());
                }
                boolean unchanged = java.util.Arrays.equals(before, Files.readAllBytes(ledger.toPath()));
                boolean noSnapshot = true;
                for (File file : context.getCacheDir().listFiles()) if (file.getName().startsWith("private-circuit-")) noSnapshot = false;
                report.put("replay_rejected", rejected).put("ledger_unchanged", unchanged)
                    .put("no_circuit_snapshot", noSnapshot).put("passed", rejected && unchanged && noSnapshot);
            } else throw new IllegalArgumentException("reserve or replay phase required");
            report.put("phase", phase).put("pid", android.os.Process.myPid());
        } catch (Exception error) {
            try { report.put("passed", false).put("error", error.toString()); } catch (Exception ignored) {}
        }
        try {
            report.put("nonce", intent.getStringExtra("nonce"))
                .put("scope", "synthetic_interrupted_unknown_score_reservation_not_completed_MPC_or_private_proof")
                .put("actuator_calls", 0).put("collection_started", false);
            NativeVerificationJob.write(new File(context.getFilesDir(), "computation_restart.json"), report);
        } catch (Exception error) { android.util.Log.e("TraceformProbe", "computation restart receipt failed", error); }
    }
    private static PublicKey[] owners(File request) throws Exception {
        PublicKey[] result = new PublicKey[2];
        for (int owner = 0; owner < 2; owner++) result[owner] = KeyFactory.getInstance("EC").generatePublic(
            new X509EncodedKeySpec(OwnerAuthentication.readBounded(new File(request, "owner" + owner + ".der"), 256)));
        return result;
    }
}
