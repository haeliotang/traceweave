package com.inceptless.traceweave;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P-256 channel binding for sync payloads.
 *
 * <p>The sync transport authenticates with an Ed25519 seed that lives in app storage. Copying
 * that seed was enough to impersonate a paired device, so every payload proves possession of
 * the Keystore P-256 key whose fingerprint was recorded at pairing time.
 *
 * <p>A captured signed payload must not be replayable after a process restart or after the
 * in-memory nonce cache expires, so the signature also covers:
 * <ul>
 *   <li>a millisecond timestamp (accepted within a two-minute window),</li>
 *   <li>a rolling challenge: the request must echo the challenge the responder issued last,
 *       and the response carries a new one the requester stores for its next request,</li>
 *   <li>an acknowledgement: the response echoes the request nonce, so an old response cannot
 *       be replayed against a different request.</li>
 * </ul>
 * The challenge and acknowledgement state is persisted per peer, so a restart does not reset it.
 *
 * <p>Wire format (binary, inside the encrypted Noise channel):
 * {@code "TWX3" | u16 spkiLen | spki | nonce[32] | u64 ts | challenge[32] | ack[32]
 * | u16 sigLen | sig | u32 bodyLen | body}.
 */
public final class TransportBinding {
    public static final int VERSION = 3;
    private static final byte[] MAGIC = {'T', 'W', 'X', '3'};
    private static final byte[] DOMAIN = "traceweave-sync-binding-v3\0".getBytes(StandardCharsets.UTF_8);
    public static final int NONCE_BYTES = 32;
    public static final int CHALLENGE_BYTES = 32;
    /** Past this window a signed payload is stale. */
    public static final long MAX_AGE_MS = 120_000L;
    /** Small allowance for a peer whose clock runs slightly ahead. */
    public static final long MAX_SKEW_MS = 60_000L;
    private static final int MAX_SPKI_BYTES = 512;
    private static final int MAX_SIGNATURE_BYTES = 512;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final long NONCE_WINDOW_MS = 5 * 60 * 1000L;
    public static final byte[] ZERO = new byte[CHALLENGE_BYTES];

    private TransportBinding() {}

    public interface Signer {
        /** Signs with the device Keystore key; must be SHA256withECDSA. */
        byte[] sign(byte[] message) throws Exception;
    }

    /** A verified envelope. */
    public static final class Opened {
        public final byte[] body;
        public final byte[] nonce;
        public final byte[] challenge;
        public final byte[] ack;
        public final long timestamp;

        Opened(byte[] body, byte[] nonce, byte[] challenge, byte[] ack, long timestamp) {
            this.body = body;
            this.nonce = nonce;
            this.challenge = challenge;
            this.ack = ack;
            this.timestamp = timestamp;
        }
    }

    public static byte[] freshChallenge() {
        byte[] challenge = new byte[CHALLENGE_BYTES];
        new SecureRandom().nextBytes(challenge);
        return challenge;
    }

    /** The exact bytes that are signed. */
    public static byte[] message(String senderFingerprint, String receiverFingerprint, byte[] nonce,
                                 long timestamp, byte[] challenge, byte[] ack, byte[] body) {
        if (senderFingerprint == null || receiverFingerprint == null) throw new IllegalArgumentException("Fingerprints are required");
        if (nonce == null || nonce.length != NONCE_BYTES) throw new IllegalArgumentException("A 32-byte nonce is required");
        if (challenge == null || challenge.length != CHALLENGE_BYTES) throw new IllegalArgumentException("A 32-byte challenge is required");
        if (ack == null || ack.length != CHALLENGE_BYTES) throw new IllegalArgumentException("A 32-byte acknowledgement is required");
        byte[] sender = senderFingerprint.getBytes(StandardCharsets.UTF_8);
        byte[] receiver = receiverFingerprint.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(DOMAIN, 0, DOMAIN.length);
        writeShort(out, sender.length);
        out.write(sender, 0, sender.length);
        writeShort(out, receiver.length);
        out.write(receiver, 0, receiver.length);
        out.write(nonce, 0, nonce.length);
        for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (timestamp >>> shift) & 0xFF);
        out.write(challenge, 0, challenge.length);
        out.write(ack, 0, ack.length);
        byte[] digest = sha256(body == null ? new byte[0] : body);
        out.write(digest, 0, digest.length);
        return out.toByteArray();
    }

    public static String fingerprint(byte[] spki) {
        return hex(sha256(spki));
    }

    /** Builds a signed request envelope (challenge echoes the peer's last challenge). */
    public static byte[] request(String localFingerprint, String peerFingerprint, byte[] spki, byte[] body,
                                 byte[] challenge, Signer signer) {
        return build(localFingerprint, peerFingerprint, spki, body, challenge, ZERO, signer);
    }

    /** Builds a signed response envelope (new challenge for the peer, ack = request nonce). */
    public static byte[] response(String localFingerprint, String peerFingerprint, byte[] spki, byte[] body,
                                  byte[] challenge, byte[] ack, Signer signer) {
        if (ack == null || ack.length != NONCE_BYTES) throw new IllegalArgumentException("A 32-byte request nonce is required");
        return build(localFingerprint, peerFingerprint, spki, body, challenge, ack, signer);
    }

    private static byte[] build(String localFingerprint, String peerFingerprint, byte[] spki, byte[] body,
                                byte[] challenge, byte[] ack, Signer signer) {
        if (signer == null) throw new IllegalArgumentException("A signer is required");
        byte[] nonce = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);
        long timestamp = System.currentTimeMillis();
        byte[] signature;
        try {
            signature = signer.sign(message(localFingerprint, peerFingerprint, nonce, timestamp,
                challenge, ack, body));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to sign the sync payload", error);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC, 0, MAGIC.length);
        writeShort(out, spki.length);
        out.write(spki, 0, spki.length);
        out.write(nonce, 0, nonce.length);
        for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (timestamp >>> shift) & 0xFF);
        out.write(challenge, 0, challenge.length);
        out.write(ack, 0, ack.length);
        writeShort(out, signature.length);
        out.write(signature, 0, signature.length);
        writeInt(out, body.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    /** Verifies a request: challenge must match the value this device issued last. */
    public static Opened verifyRequest(byte[] envelope, String expectedPeerFingerprint, String localFingerprint,
                                       byte[] expectedChallenge, NonceCache nonces, long nowMillis) {
        Opened opened = verify(envelope, expectedPeerFingerprint, localFingerprint, nonces, nowMillis);
        byte[] expected = expectedChallenge == null ? ZERO : expectedChallenge;
        if (!MessageDigest.isEqual(expected, opened.challenge)) throw reject("challenge_mismatch");
        if (!MessageDigest.isEqual(ZERO, opened.ack)) throw reject("unexpected_ack");
        return opened;
    }

    /** Verifies a response: ack must echo the request nonce we sent. */
    public static Opened verifyResponse(byte[] envelope, String expectedPeerFingerprint, String localFingerprint,
                                        byte[] requestNonce, NonceCache nonces, long nowMillis) {
        Opened opened = verify(envelope, expectedPeerFingerprint, localFingerprint, nonces, nowMillis);
        if (!MessageDigest.isEqual(requestNonce, opened.ack)) throw reject("ack_mismatch");
        return opened;
    }

    private static Opened verify(byte[] envelope, String expectedPeerFingerprint, String localFingerprint,
                                 NonceCache nonces, long nowMillis) {
        Parsed parsed = parse(envelope);
        String actual = fingerprint(parsed.spki);
        if (!constantTimeEquals(actual, expectedPeerFingerprint)) throw reject("fingerprint_mismatch");
        if (nowMillis - parsed.timestamp > MAX_AGE_MS) throw reject("stale_timestamp");
        if (parsed.timestamp - nowMillis > MAX_SKEW_MS) throw reject("future_timestamp");
        byte[] signed = message(expectedPeerFingerprint, localFingerprint, parsed.nonce, parsed.timestamp,
            parsed.challenge, parsed.ack, parsed.body);
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(parsed.spki)));
            verifier.update(signed);
            if (!verifier.verify(parsed.signature)) throw reject("signature_invalid");
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw reject("signature_invalid");
        }
        if (nonces != null && !nonces.accept(parsed.nonce, nowMillis)) throw reject("nonce_replayed");
        return new Opened(parsed.body, parsed.nonce, parsed.challenge, parsed.ack, parsed.timestamp);
    }

    static final class Parsed {
        byte[] spki;
        byte[] nonce;
        long timestamp;
        byte[] challenge;
        byte[] ack;
        byte[] signature;
        byte[] body;
    }

    /** Parses and structurally validates an envelope. */
    static Parsed parse(byte[] envelope) {
        if (envelope == null || envelope.length < MAGIC.length) throw reject("not_bound");
        for (int index = 0; index < MAGIC.length; index++)
            if (envelope[index] != MAGIC[index]) throw reject("not_bound");
        if (envelope.length < MAGIC.length + 2) throw reject("bad_envelope");
        int offset = MAGIC.length;
        int spkiLength = readShort(envelope, offset);
        offset += 2;
        if (spkiLength <= 0 || spkiLength > MAX_SPKI_BYTES || offset + spkiLength > envelope.length)
            throw reject("bad_envelope");
        Parsed parsed = new Parsed();
        parsed.spki = Arrays.copyOfRange(envelope, offset, offset + spkiLength);
        offset += spkiLength;
        if (offset + NONCE_BYTES + 8 + 2 * CHALLENGE_BYTES > envelope.length) throw reject("bad_envelope");
        parsed.nonce = Arrays.copyOfRange(envelope, offset, offset + NONCE_BYTES);
        offset += NONCE_BYTES;
        long timestamp = 0;
        for (int index = 0; index < 8; index++) timestamp = (timestamp << 8) | (envelope[offset + index] & 0xFFL);
        parsed.timestamp = timestamp;
        offset += 8;
        parsed.challenge = Arrays.copyOfRange(envelope, offset, offset + CHALLENGE_BYTES);
        offset += CHALLENGE_BYTES;
        parsed.ack = Arrays.copyOfRange(envelope, offset, offset + CHALLENGE_BYTES);
        offset += CHALLENGE_BYTES;
        if (offset + 2 > envelope.length) throw reject("bad_envelope");
        int signatureLength = readShort(envelope, offset);
        offset += 2;
        if (signatureLength <= 0 || signatureLength > MAX_SIGNATURE_BYTES || offset + signatureLength > envelope.length)
            throw reject("bad_envelope");
        parsed.signature = Arrays.copyOfRange(envelope, offset, offset + signatureLength);
        offset += signatureLength;
        if (offset + 4 > envelope.length) throw reject("bad_envelope");
        int bodyLength = readInt(envelope, offset);
        offset += 4;
        if (bodyLength < 0 || bodyLength > MAX_BODY_BYTES || offset + bodyLength != envelope.length)
            throw reject("bad_envelope");
        parsed.body = Arrays.copyOfRange(envelope, offset, offset + bodyLength);
        return parsed;
    }

    /** Bounded replay cache: a nonce is accepted once inside a five-minute window. */
    public static final class NonceCache {
        private final Map<String, Long> seen = new LinkedHashMap<>();
        private final int limit;

        public NonceCache() { this(512); }

        public NonceCache(int limit) { this.limit = Math.max(16, limit); }

        public synchronized boolean accept(byte[] nonce, long nowMillis) {
            prune(nowMillis);
            String key = hex(nonce);
            if (seen.containsKey(key)) return false;
            seen.put(key, nowMillis);
            while (seen.size() > limit) {
                String oldest = seen.keySet().iterator().next();
                seen.remove(oldest);
            }
            return true;
        }

        private void prune(long nowMillis) {
            java.util.Iterator<Map.Entry<String, Long>> iterator = seen.entrySet().iterator();
            while (iterator.hasNext()) {
                if (nowMillis - iterator.next().getValue() > NONCE_WINDOW_MS) iterator.remove();
            }
        }
    }

    private static IllegalStateException reject(String reason) {
        return new IllegalStateException(reason);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
            | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }
}
