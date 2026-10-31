package com.inceptless.traceweave.edge;

import android.content.Context;
import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class PrivateParticipantFixture {
    static JSONObject verify(Context context, Runnable started, boolean approved) throws Exception {
        File root = new File(context.getFilesDir(), approved ? "approved_private_fixture" : "private_participant_fixture");
        File circuit = new File(root, "circuit.txt");
        String trustedDigest = "1fc4dc076b5ebd24c036c9f10259e22449d8ccaa73c0747f3c3bc5f93dc4690b";
        File publicPackage = new File(root, "public_package");
        byte[] inputs = OwnerAuthentication.readBounded(new File(approved ? publicPackage : root, "public_inputs.bin"), 224);
        if (inputs.length != 224) throw new SecurityException("invalid fixture statement");
        StringBuilder sessionBytes = new StringBuilder();
        for (int value = 0; value < 32; value++) sessionBytes.append(String.format("%02x", inputs[value < 31 ? 32 + value : 64] & 255));
        String session = sessionBytes.toString();
        String keyPin = approved ? new String(OwnerAuthentication.readBounded(new File(root, "key.pin"), 64), "US-ASCII") : null;
        java.security.PublicKey[] owners = new java.security.PublicKey[2];
        if (approved) for (int owner = 0; owner < 2; owner++) owners[owner] = java.security.KeyFactory.getInstance("EC").generatePublic(
            new java.security.spec.X509EncodedKeySpec(OwnerAuthentication.readBounded(new File(root, "owner" + owner + ".der"), 256)));
        String executable = new File(context.getApplicationInfo().nativeLibraryDir,
            "libtraceweave_private_participant.so").getAbsolutePath();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<String> first = null, second = null;
        try {
            String[][] commands = new String[2][];
            String[] bits = new String[2];
            for (int role = 1; role <= 2; role++) {
                int peer = 3 - role;
                String pin = new String(OwnerAuthentication.readBounded(new File(root, peer + ".pin"), 64), "US-ASCII");
                bits[role - 1] = new String(OwnerAuthentication.readBounded(new File(root, role + ".bits"), 11600), "US-ASCII");
                commands[role - 1] = new String[]{executable, String.valueOf(role), "23817", circuit.getAbsolutePath(),
                    new File(root, role + ".pem").getAbsolutePath(), new File(root, role + ".key").getAbsolutePath(),
                    new File(root, peer + ".pem").getAbsolutePath(), pin, session};
            }
            first = workers.submit(() -> approved
                ? ProofVerifier.computeAndVerify(context, publicPackage, keyPin, owners, commands[0], circuit, trustedDigest, bits[0]).toString()
                : PrivateParticipant.runPinnedCircuit(commands[0], circuit, context.getCacheDir(), trustedDigest, bits[0], inputs));
            second = workers.submit(() -> approved
                ? ApprovedPrivateAlignment.run(commands[1], circuit, context.getCacheDir(), trustedDigest, publicPackage, keyPin, owners, bits[1], new File(context.getFilesDir(), "zk_sessions.bin"))
                : PrivateParticipant.runPinnedCircuit(commands[1], circuit, context.getCacheDir(), trustedDigest, bits[1], inputs));
            if (started != null) started.run();
            String a = first.get(150, TimeUnit.SECONDS), b = second.get(125, TimeUnit.SECONDS);
            if (approved) {
                JSONObject verification = new JSONObject(a);
                JSONObject repeated = ProofVerifier.verifyPrivateAlignment(context, publicPackage, keyPin, owners, b);
                boolean passed = verification.getBoolean("verified") && !repeated.getBoolean("verified")
                    && repeated.getString("receipt").contains("replayed session");
                return new JSONObject().put("passed", passed).put("first", verification).put("repeated", repeated)
                    .put("scope", "synthetic_signed_mpc_and_groth16_same_app_uid_not_distributed_prover");
            }
            if (!a.equals(b)) throw new SecurityException("fixture participant outputs differ");
            return new JSONObject().put("passed", true).put("parties", 2)
                .put("scope", "synthetic_two_participants_same_app_uid_not_cross_owner_production");
        } finally {
            if (first != null) first.cancel(true);
            if (second != null) second.cancel(true);
            workers.shutdownNow();
            try {
                if (!workers.awaitTermination(3, TimeUnit.SECONDS))
                    throw new IllegalStateException("fixture cleanup timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            }
        }
    }
}
