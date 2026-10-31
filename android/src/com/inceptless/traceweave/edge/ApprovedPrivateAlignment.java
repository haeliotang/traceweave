package com.inceptless.traceweave.edge;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.PublicKey;

public final class ApprovedPrivateAlignment {
    public static String run(String[] command, File circuit, File privateCache, String trustedCircuitDigest,
                             File publicPackage, String trustedKeyHash, PublicKey[] trustedOwners,
                             String privateBits) throws Exception {
        return run(command, circuit, privateCache, trustedCircuitDigest, publicPackage, trustedKeyHash,
            trustedOwners, privateBits, null);
    }

    public static String run(String[] command, File circuit, File privateCache, String trustedCircuitDigest,
                             File publicPackage, String trustedKeyHash, PublicKey[] trustedOwners,
                             String privateBits, File usedSessions) throws Exception {
        File snapshot = Files.createTempDirectory(privateCache.toPath(), "approved-alignment-").toFile();
        try {
            for (String name : new String[]{"verifying_key.bin", "proof.bin", "public_inputs.bin", "owner0.sig", "owner1.sig"}) {
                int maximum = name.equals("verifying_key.bin") ? 4096 : name.equals("proof.bin") ? 192 : name.equals("public_inputs.bin") ? 224 : 128;
                Files.write(new File(snapshot, name).toPath(), OwnerAuthentication.readBounded(new File(publicPackage, name), maximum));
            }
            byte[] key = OwnerAuthentication.readBounded(new File(snapshot, "verifying_key.bin"), 4096);
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(key)) hash.append(String.format("%02x", value & 255));
            if (!hash.toString().equals(trustedKeyHash)) throw new SecurityException("untrusted verifying key");
            OwnerAuthentication.verify(snapshot, trustedKeyHash, trustedOwners);
            byte[] inputs = OwnerAuthentication.readBounded(new File(snapshot, "public_inputs.bin"), 224);
            if (usedSessions != null && usedSessions.exists()) {
                byte[] prior = OwnerAuthentication.readBounded(usedSessions, 131072);
                if (prior.length % 32 != 0) throw new SecurityException("invalid session ledger");
                byte[] session = java.util.Arrays.copyOfRange(inputs, 32, 64);
                session[31] = inputs[64];
                byte[] tag = MessageDigest.getInstance("SHA-256").digest(session);
                for (int offset = 0; offset < prior.length; offset += 32)
                    if (MessageDigest.isEqual(tag, java.util.Arrays.copyOfRange(prior, offset, offset + 32)))
                        throw new SecurityException("replayed session");
                if (prior.length == 131072) throw new SecurityException("session ledger capacity reached");
            }
            return PrivateParticipant.runPinnedCircuit(command, circuit, privateCache, trustedCircuitDigest, privateBits,
                inputs);
        } finally {
            File[] files = snapshot.listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(snapshot.toPath());
        }
    }
}
