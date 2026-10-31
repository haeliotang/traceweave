package com.inceptless.traceweave.edge;

import java.math.BigInteger;
import java.security.MessageDigest;

public final class PrivateAlignmentOutput {
    public static void verify(String bits, byte[] publicInputs) {
        byte[] encoded = encodePublicStatement(bits);
        if (publicInputs == null || publicInputs.length != 224)
            throw new SecurityException("exact public statement encoding required");
        if (!MessageDigest.isEqual(encoded, publicInputs))
            throw new SecurityException("private alignment output does not match public statement");
    }

    // Internal primitive for a separately authorized unknown-objective computation.
    // It checks bindings, not owner signatures, replay policy or a public proof.
    static byte[] verifyBindings(String bits, byte[] approvedBindings) {
        byte[] encoded = encodePublicStatement(bits);
        if (approvedBindings == null || approvedBindings.length != 192)
            throw new SecurityException("exact approved binding encoding required");
        if (!MessageDigest.isEqual(java.util.Arrays.copyOfRange(encoded, 32, 224), approvedBindings.clone()))
            throw new SecurityException("private alignment output does not match approved bindings");
        return encoded;
    }

    // Canonical discovered statement for subsequent owner approval/proof binding.
    // Encoding alone does not authenticate an MPC result or verify a proof.
    // Each call returns a fresh 224-byte array containing only public output.
    public static byte[] encodePublicStatement(String bits) {
        if (bits == null || bits.length() != 849 || !bits.matches("[01]{849}"))
            throw new SecurityException("exact binary private alignment output required");
        if (bits.charAt(0) != '1') throw new SecurityException("private alignment witness rejected");
        BigInteger numerator = new BigInteger(new StringBuilder(bits.substring(1, 81)).reverse().toString(), 2);
        if (numerator.compareTo(new BigInteger("1000000000000000000000000")) > 0)
            throw new SecurityException("private alignment objective exceeds bound");
        byte[] encoded = new byte[224];
        for (int bit = 0; bit < 80; bit++)
            if (bits.charAt(1 + bit) == '1') encoded[bit / 8] |= 1 << (bit % 8);
        for (int binding = 0; binding < 3; binding++)
            for (int value = 0; value < 32; value++) {
                int offset = 32 + binding * 64 + (value < 31 ? value : 32);
                for (int bit = 0; bit < 8; bit++)
                    if (bits.charAt(81 + binding * 256 + value * 8 + bit) == '1') encoded[offset] |= 1 << bit;
            }
        return encoded;
    }
}
