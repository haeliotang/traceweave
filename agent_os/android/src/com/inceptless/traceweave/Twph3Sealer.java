// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * TWPH3 sealed handoff: Argon2id (m=19456 KiB, t=2, p=1) + AES-256-GCM, header as AAD.
 * Byte layout matches the host parser and agent_os/tools/java/Twph3Vector.java.
 */
final class Twph3Sealer {
    private Twph3Sealer() {}

    static byte[] seal(byte[] canonical, byte[] spki, byte[] signature, long signedAt, String passphrase)
            throws Exception {
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(nonce);
        ByteBuffer headerBuffer = ByteBuffer.allocate(
                5 + 8 + 4 + spki.length + 4 + signature.length + 2 + salt.length + 2 + nonce.length);
        headerBuffer
                .put("TWPH3".getBytes(StandardCharsets.UTF_8))
                .putLong(signedAt)
                .putInt(spki.length).put(spki)
                .putInt(signature.length).put(signature)
                .putShort((short) salt.length).put(salt)
                .putShort((short) nonce.length).put(nonce);
        byte[] header = headerBuffer.array();

        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(2)
                .withMemoryAsKB(19_456)
                .withParallelism(1)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] key = new byte[32];
        generator.generateBytes(passphrase.getBytes(StandardCharsets.UTF_8), key);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header);
        byte[] ciphertext = cipher.doFinal(canonical);
        byte[] envelope = ByteBuffer.allocate(header.length + 4 + ciphertext.length)
                .put(header)
                .putInt(ciphertext.length)
                .put(ciphertext)
                .array();
        java.util.Arrays.fill(key, (byte) 0);
        return envelope;
    }
}
