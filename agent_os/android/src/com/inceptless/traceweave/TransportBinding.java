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
 * that seed (root, forensic image, restored backup) used to be enough to impersonate a paired
 * device. Every payload now carries the sender's P-256 public key (the exact key whose SHA-256
 * fingerprint is stored at pairing time), a fresh nonce and a signature over the payload; the
 * receiver rejects anything that does not prove possession of the Keystore private key.
 *
 * <p>Wire format (binary, inside the encrypted Noise channel):
 * {@code "TWX2" | u16 spkiLen | spki | nonce[32] | u16 sigLen | sig | u32 bodyLen | body}.
 */
public final class TransportBinding {
    public static final int VERSION = 2;
    private static final byte[] MAGIC = {'T', 'W', 'X', '2'};
    private static final byte[] DOMAIN = "traceweave-sync-binding-v2\0".getBytes(StandardCharsets.UTF_8);
    public static final int NONCE_BYTES = 32;
    private static final int MAX_SPKI_BYTES = 512;
    private static final int MAX_SIGNATURE_BYTES = 512;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final long NONCE_WINDOW_MS = 5 * 60 * 1000L;

    private TransportBinding() {}

    public interface Signer {
        /** Signs with the device Keystore key; must be SHA256withECDSA. */
        byte[] sign(byte[] message) throws Exception;
    }

    /** A verified or to-be-verified envelope. */
    public static final class Envelope {
        public final byte[] spki;
        public final byte[] nonce;
        public final byte[] signature;
        public final byte[] body;

        Envelope(byte[] spki, byte[] nonce, byte[] signature, byte[] body) {
            this.spki = spki;
            this.nonce = nonce;
            this.signature = signature;
            this.body = body;
        }
    }

    /** The exact bytes that are signed. The body is committed by its SHA-256. */
    public static byte[] message(String senderFingerprint, String receiverFingerprint, byte[] nonce, byte[] body) {
        if (senderFingerprint == null || receiverFingerprint == null) throw new IllegalArgumentException("Fingerprints are required");
        if (nonce == null || nonce.length != NONCE_BYTES) throw new IllegalArgumentException("A 32-byte nonce is required");
        byte[] sender = senderFingerprint.getBytes(StandardCharsets.UTF_8);
        byte[] receiver = receiverFingerprint.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(DOMAIN, 0, DOMAIN.length);
        writeShort(out, sender.length);
        out.write(sender, 0, sender.length);
        writeShort(out, receiver.length);
        out.write(receiver, 0, receiver.length);
        out.write(nonce, 0, nonce.length);
        byte[] digest = sha256(body == null ? new byte[0] : body);
        out.write(digest, 0, digest.length);
        return out.toByteArray();
    }

    public static String fingerprint(byte[] spki) {
        return hex(sha256(spki));
    }

    /** Builds an envelope signed by this device. */
    public static byte[] wrap(String localFingerprint, String peerFingerprint, byte[] spki, byte[] body, Signer signer) {
        if (signer == null) throw new IllegalArgumentException("A signer is required");
        byte[] nonce = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);
        byte[] signature;
        try {
            signature = signer.sign(message(localFingerprint, peerFingerprint, nonce, body));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to sign the sync payload", error);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC, 0, MAGIC.length);
        writeShort(out, spki.length);
        out.write(spki, 0, spki.length);
        out.write(nonce, 0, nonce.length);
        writeShort(out, signature.length);
        out.write(signature, 0, signature.length);
        writeInt(out, body.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    /** Parses and structurally validates an envelope. Throws with a short reason code. */
    public static Envelope parse(byte[] envelope) {
        if (envelope == null || envelope.length < MAGIC.length) throw reject("not_bound");
        for (int index = 0; index < MAGIC.length; index++)
            if (envelope[index] != MAGIC[index]) throw reject("not_bound");
        if (envelope.length < MAGIC.length + 2) throw reject("bad_envelope");
        int offset = MAGIC.length;
        int spkiLength = readShort(envelope, offset);
        offset += 2;
        if (spkiLength <= 0 || spkiLength > MAX_SPKI_BYTES || offset + spkiLength > envelope.length)
            throw reject("bad_envelope");
        byte[] spki = Arrays.copyOfRange(envelope, offset, offset + spkiLength);
        offset += spkiLength;
        if (offset + NONCE_BYTES > envelope.length) throw reject("bad_envelope");
        byte[] nonce = Arrays.copyOfRange(envelope, offset, offset + NONCE_BYTES);
        offset += NONCE_BYTES;
        if (offset + 2 > envelope.length) throw reject("bad_envelope");
        int signatureLength = readShort(envelope, offset);
        offset += 2;
        if (signatureLength <= 0 || signatureLength > MAX_SIGNATURE_BYTES || offset + signatureLength > envelope.length)
            throw reject("bad_envelope");
        byte[] signature = Arrays.copyOfRange(envelope, offset, offset + signatureLength);
        offset += signatureLength;
        if (offset + 4 > envelope.length) throw reject("bad_envelope");
        int bodyLength = readInt(envelope, offset);
        offset += 4;
        if (bodyLength < 0 || bodyLength > MAX_BODY_BYTES || offset + bodyLength != envelope.length)
            throw reject("bad_envelope");
        byte[] body = Arrays.copyOfRange(envelope, offset, offset + bodyLength);
        return new Envelope(spki, nonce, signature, body);
    }

    /**
     * Verifies an envelope belongs to the paired device and returns its body.
     * Throws IllegalStateException with a short reason code on any failure.
     */
    public static byte[] open(byte[] envelope, String expectedPeerFingerprint, String localFingerprint,
                              NonceCache nonces, long nowMillis) {
        Envelope parsed = parse(envelope);
        String actual = fingerprint(parsed.spki);
        if (!constantTimeEquals(actual, expectedPeerFingerprint)) throw reject("fingerprint_mismatch");
        byte[] signed = message(expectedPeerFingerprint, localFingerprint, parsed.nonce, parsed.body);
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
        return parsed.body;
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
