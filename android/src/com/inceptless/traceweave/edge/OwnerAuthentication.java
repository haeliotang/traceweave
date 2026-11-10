// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.io.File;
import java.io.FileInputStream;
import java.security.PublicKey;
import java.security.Signature;

public final class OwnerAuthentication {
    static byte[] readBounded(File file, int maximum) throws Exception {
        byte[] buffer = new byte[maximum + 1];
        int count = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (count < buffer.length) {
                int read = input.read(buffer, count, buffer.length - count);
                if (read < 0) break;
                count += read;
            }
        }
        if (count > maximum) throw new SecurityException("public material exceeds limit");
        return java.util.Arrays.copyOf(buffer, count);
    }

    public static void verify(File publicPackage, String trustedKeyHash, PublicKey[] trustedOwners) throws Exception {
        if (trustedOwners.length != 2 || !trustedKeyHash.matches("[0-9a-f]{64}"))
            throw new SecurityException("two trusted owners and a verifying-key pin required");
        if (java.util.Arrays.equals(trustedOwners[0].getEncoded(), trustedOwners[1].getEncoded()))
            throw new SecurityException("distinct trusted owner keys required");
        byte[] inputs = readBounded(new File(publicPackage, "public_inputs.bin"), 224);
        byte[] proof = readBounded(new File(publicPackage, "proof.bin"), 192);
        if (inputs.length != 224 || proof.length != 192) throw new SecurityException("invalid public material");
        for (int owner = 0; owner < 2; owner++) {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(trustedOwners[owner]);
            verifier.update("traceform.gw.owner-approval.v1\0".getBytes("UTF-8"));
            verifier.update((byte) owner);
            verifier.update(trustedKeyHash.getBytes("UTF-8"));
            verifier.update(inputs);
            verifier.update(proof);
            if (!verifier.verify(readBounded(new File(publicPackage, "owner" + owner + ".sig"), 128)))
                throw new SecurityException("owner signature rejected");
        }
    }

    // A computation request authorizes bindings before the objective is known.
    // It does not authorize replay or certify the resulting public proof.
    static byte[] verifyComputation(File request, String trustedCircuitHash, PublicKey[] trustedOwners) throws Exception {
        if (trustedOwners == null || trustedOwners.length != 2 || trustedOwners[0] == null || trustedOwners[1] == null
            || trustedCircuitHash == null || !trustedCircuitHash.matches("[0-9a-f]{64}"))
            throw new SecurityException("two trusted owners and a circuit pin required");
        if (java.util.Arrays.equals(trustedOwners[0].getEncoded(), trustedOwners[1].getEncoded()))
            throw new SecurityException("distinct trusted owner keys required");
        byte[] bindings = readBounded(new File(request, "bindings.bin"), 192);
        if (bindings.length != 192) throw new SecurityException("exact computation bindings required");
        for (int binding = 0; binding < 3; binding++)
            for (int offset = 31; offset < 64; offset++)
                if (offset != 32 && bindings[binding * 64 + offset] != 0)
                    throw new SecurityException("noncanonical computation bindings");
        for (int owner = 0; owner < 2; owner++) {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(trustedOwners[owner]);
            verifier.update("traceform.gw.compute-approval.v2\0".getBytes("UTF-8"));
            verifier.update((byte) owner);
            verifier.update(trustedCircuitHash.getBytes("US-ASCII"));
            verifier.update(bindings);
            if (!verifier.verify(readBounded(new File(request, "owner" + owner + ".sig"), 128)))
                throw new SecurityException("computation owner signature rejected");
        }
        return bindings;
    }

}
