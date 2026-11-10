// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

import com.inceptless.traceweave.TransportBinding;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

/** Host check for the P-256 channel binding, run by test_transport_binding.py. */
public final class TransportBindingHostTest {
    private static int checks = 0;
    private static final String LOCAL = "aa".repeat(32);
    private static final String PEER = "bb".repeat(32);

    private static void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("FAIL " + label);
        checks++;
    }

    private static void expectReject(String reason, Runnable action, String label) {
        try {
            action.run();
            throw new IllegalStateException("FAIL accepted: " + label);
        } catch (IllegalStateException error) {
            check(reason.equals(error.getMessage()), label + " -> " + reason + " (got " + error.getMessage() + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        byte[] spki = pair.getPublic().getEncoded();
        String fingerprint = TransportBinding.fingerprint(spki);
        long now = System.currentTimeMillis();
        TransportBinding.Signer signer = message -> {
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withECDSA");
            signature.initSign(pair.getPrivate());
            signature.update(message);
            return signature.sign();
        };

        byte[] body = "{\"records\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] challenge = TransportBinding.freshChallenge();
        byte[] request = TransportBinding.request(fingerprint, PEER, spki, body, TransportBinding.ZERO, signer);
        TransportBinding.NonceCache cache = new TransportBinding.NonceCache();

        // Bootstrap request (no challenge known yet) verifies, then the response ack must match.
        TransportBinding.Opened opened = TransportBinding.verifyRequest(request, fingerprint, PEER,
            TransportBinding.ZERO, cache, now);
        check(Arrays.equals(body, opened.body), "bootstrap request verifies");
        byte[] response = TransportBinding.response(fingerprint, PEER, spki, body, challenge, opened.nonce, signer);
        TransportBinding.Opened reply = TransportBinding.verifyResponse(response, fingerprint, PEER,
            opened.nonce, cache, now);
        check(Arrays.equals(challenge, reply.challenge), "response carries the next challenge");

        // The next request must echo that challenge.
        byte[] chained = TransportBinding.request(fingerprint, PEER, spki, body, reply.challenge, signer);
        TransportBinding.Opened chainedOpened = TransportBinding.verifyRequest(chained, fingerprint, PEER,
            reply.challenge, cache, now);
        check(chainedOpened != null, "chained request verifies");

        // A fresh bootstrap request no longer matches the expected challenge (replay of the first request).
        expectReject("challenge_mismatch",
            () -> TransportBinding.verifyRequest(request, fingerprint, PEER, reply.challenge,
                new TransportBinding.NonceCache(), now), "old request replay");

        // A response can only answer the request it acknowledges.
        expectReject("ack_mismatch",
            () -> TransportBinding.verifyResponse(response, fingerprint, PEER, chainedOpened.nonce,
                new TransportBinding.NonceCache(), now), "response replayed against another request");

        // Wrong expected fingerprint.
        expectReject("fingerprint_mismatch",
            () -> TransportBinding.verifyRequest(request, "cc".repeat(32), PEER,
                TransportBinding.ZERO, new TransportBinding.NonceCache(), now), "wrong peer fingerprint");

        // Tampered body.
        byte[] tampered = request.clone();
        tampered[tampered.length - 3] ^= 0x01;
        expectReject("signature_invalid",
            () -> TransportBinding.verifyRequest(tampered, fingerprint, PEER, TransportBinding.ZERO,
                new TransportBinding.NonceCache(), now), "tampered body");

        // Stale and future timestamps are rejected even with a valid signature, so a captured
        // payload cannot be replayed after the nonce cache expired or the process restarted.
        // Evaluate the same payload as if two minutes had passed, and as if our clock were behind.
        long stale = now + TransportBinding.MAX_AGE_MS + 5_000;
        expectReject("stale_timestamp",
            () -> TransportBinding.verifyRequest(request, fingerprint, PEER, TransportBinding.ZERO,
                new TransportBinding.NonceCache(), stale), "stale timestamp");
        long future = now - TransportBinding.MAX_SKEW_MS - 5_000;
        expectReject("future_timestamp",
            () -> TransportBinding.verifyRequest(request, fingerprint, PEER, TransportBinding.ZERO,
                new TransportBinding.NonceCache(), future), "future timestamp");

        // Same nonce twice.
        TransportBinding.NonceCache replay = new TransportBinding.NonceCache();
        TransportBinding.verifyRequest(request, fingerprint, PEER, TransportBinding.ZERO, replay, now);
        expectReject("nonce_replayed",
            () -> TransportBinding.verifyRequest(request, fingerprint, PEER, TransportBinding.ZERO, replay, now),
            "replayed nonce");

        // Legacy payloads and truncation.
        expectReject("not_bound",
            () -> TransportBinding.verifyRequest(body, fingerprint, PEER, TransportBinding.ZERO,
                new TransportBinding.NonceCache(), now), "legacy payload");
        expectReject("bad_envelope",
            () -> TransportBinding.verifyRequest(new byte[]{'T', 'W', 'X', '3', 0}, fingerprint, PEER,
                TransportBinding.ZERO, new TransportBinding.NonceCache(), now), "truncated envelope");

        System.out.println("PASS transport_binding checks=" + checks);
    }
}
