package org.traceform.edge;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.security.PublicKey;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class ProofVerifier {
    public static JSONObject computeAndVerify(Context context, File publicPackage, String trustedKeyHash,
                                               PublicKey[] trustedOwners, String[] command, File circuit,
                                               String trustedCircuitDigest, String privateBits) throws Exception {
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquire(context.getFilesDir())) {
            String output = ApprovedPrivateAlignment.run(command, circuit, context.getCacheDir(), trustedCircuitDigest,
                publicPackage, trustedKeyHash, trustedOwners, privateBits, new File(context.getFilesDir(), "zk_sessions.bin"));
            return verifyPrivateAlignment(context, publicPackage, trustedKeyHash, trustedOwners, output);
        }
    }

    public static JSONObject verify(Context context, File publicPackage, String trustedKeyHash, PublicKey[] trustedOwners) throws Exception {
        return verifyPackage(context, publicPackage, trustedKeyHash, trustedOwners, null);
    }

    public static JSONObject verifyPrivateAlignment(Context context, File publicPackage, String trustedKeyHash, PublicKey[] trustedOwners, String outputBits) throws Exception {
        if (outputBits == null) throw new SecurityException("private alignment output required");
        return verifyPackage(context, publicPackage, trustedKeyHash, trustedOwners, outputBits);
    }

    private static JSONObject verifyPackage(Context context, File publicPackage, String trustedKeyHash, PublicKey[] trustedOwners, String outputBits) throws Exception {
        File snapshot = new File(context.getCacheDir(), "zk-" + java.util.UUID.randomUUID());
        if (!snapshot.mkdir()) throw new IllegalStateException("public snapshot unavailable");
        try {
            for (String name : new String[]{"verifying_key.bin", "proof.bin", "public_inputs.bin", "owner0.sig", "owner1.sig"}) {
                int maximum = name.equals("verifying_key.bin") ? 4096 : name.equals("proof.bin") ? 192 : name.equals("public_inputs.bin") ? 224 : 128;
                Files.write(new File(snapshot, name).toPath(), OwnerAuthentication.readBounded(new File(publicPackage, name), maximum));
            }
            OwnerAuthentication.verify(snapshot, trustedKeyHash, trustedOwners);
            if (outputBits != null) PrivateAlignmentOutput.verify(outputBits,
                OwnerAuthentication.readBounded(new File(snapshot, "public_inputs.bin"), 224));
            return verifySnapshot(context, snapshot, trustedKeyHash);
        } finally {
            File[] files = snapshot.listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(snapshot.toPath());
        }
    }

    private static JSONObject verifySnapshot(Context context, File publicPackage, String trustedKeyHash) throws Exception {
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libtraceform_zk_verify.so");
        File ledger = new File(context.getFilesDir(), "zk_sessions.bin");
        Process process = new ProcessBuilder(executable.getAbsolutePath(), publicPackage.getAbsolutePath(),
            trustedKeyHash, ledger.getAbsolutePath()).redirectErrorStream(true).start();
        boolean interrupted = false;
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS))
                throw new IllegalStateException("proof verification timed out");
            byte[] bytes = new byte[4096];
            int count = process.getInputStream().read(bytes);
            String output = count < 0 ? "" : new String(bytes, 0, count, "UTF-8");
            return new JSONObject().put("verified", process.exitValue() == 0)
                .put("exit_code", process.exitValue()).put("receipt", output.trim());
        } catch (InterruptedException error) {
            interrupted = true;
            throw error;
        } finally {
            process.destroyForcibly();
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS))
                    throw new IllegalStateException("proof verifier cleanup timed out");
            } catch (InterruptedException error) {
                interrupted = true;
                throw error;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
