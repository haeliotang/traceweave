package com.inceptless.traceweave;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Hash chain over the audit rows. Every row commits to the previous row's hash, so
 * editing, deleting or reordering a historical event breaks the chain at that point.
 * This is tamper-evident, not tamper-proof: whoever owns the file can still rewrite
 * the whole chain.
 *
 * <p>Version 1 concatenated fields with {@code |}, so field boundaries were ambiguous:
 * {@code taskId="x|1000", occurredAt=2000} and {@code taskId="x", occurredAt=1000} hash
 * the same string when the event starts with {@code 2000|}. Rows written from schema 14 on
 * use version 2, which length-prefixes every field. Old rows keep version 1 so existing
 * chains (and the anchors over them) stay valid.
 */
public final class AuditChain {
    public static final String GENESIS = "";
    public static final int DIGEST_VERSION_LEGACY = 1;
    public static final int DIGEST_VERSION_LENGTH_PREFIXED = 2;
    public static final int CURRENT_DIGEST_VERSION = DIGEST_VERSION_LENGTH_PREFIXED;

    private AuditChain() {}

    /** Legacy digest: fields joined by '|'. Kept to verify rows written before schema 14. */
    public static String digestV1(String previousHash, String id, String taskId, long occurredAt, String event) {
        String payload = (previousHash == null ? "" : previousHash) + "|" + id + "|" + taskId
            + "|" + occurredAt + "|" + event;
        return sha256Hex(payload);
    }

    /** Length-prefixed digest: no field can impersonate another field's content. */
    public static String digestV2(String previousHash, String id, String taskId, long occurredAt, String event) {
        StringBuilder payload = new StringBuilder();
        appendField(payload, previousHash);
        appendField(payload, id);
        appendField(payload, taskId);
        appendField(payload, Long.toString(occurredAt));
        appendField(payload, event);
        return sha256Hex(payload.toString());
    }

    /** Current write path. */
    public static String digest(String previousHash, String id, String taskId, long occurredAt, String event) {
        return digestV2(previousHash, id, taskId, occurredAt, event);
    }

    private static void appendField(StringBuilder payload, String value) {
        String text = value == null ? "" : value;
        payload.append(text.length()).append(':').append(text);
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hashed = sha.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) hex.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** Rows arrive in insertion order; returns the first index that does not verify, or -1. */
    public static int firstBrokenIndex(List<Row> rows) {
        String previous = GENESIS;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (!previous.equals(row.prevHash)) return index;
            String expected = row.digestVersion >= DIGEST_VERSION_LENGTH_PREFIXED
                ? digestV2(previous, row.id, row.taskId, row.occurredAt, row.event)
                : digestV1(previous, row.id, row.taskId, row.occurredAt, row.event);
            if (!expected.equals(row.hash)) return index;
            previous = row.hash;
        }
        return -1;
    }

    public static final class Row {
        public final String id;
        public final String taskId;
        public final long occurredAt;
        public final String event;
        public final String prevHash;
        public final String hash;
        public final int digestVersion;

        public Row(String id, String taskId, long occurredAt, String event, String prevHash, String hash) {
            this(id, taskId, occurredAt, event, prevHash, hash, DIGEST_VERSION_LEGACY);
        }

        public Row(String id, String taskId, long occurredAt, String event, String prevHash, String hash,
                   int digestVersion) {
            this.id = id;
            this.taskId = taskId;
            this.occurredAt = occurredAt;
            this.event = event;
            this.prevHash = prevHash == null ? "" : prevHash;
            this.hash = hash == null ? "" : hash;
            this.digestVersion = digestVersion;
        }
    }
}
