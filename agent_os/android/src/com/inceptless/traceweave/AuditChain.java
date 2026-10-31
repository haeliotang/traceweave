package com.inceptless.traceweave;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Hash chain over the audit rows. Every row commits to the previous row's hash, so
 * editing, deleting or reordering a historical event breaks the chain at that point.
 * This is tamper-evident, not tamper-proof: whoever owns the file can still rewrite
 * the whole chain.
 */
public final class AuditChain {
    public static final String GENESIS = "";

    private AuditChain() {}

    public static String digest(String previousHash, String id, String taskId, long occurredAt, String event) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String payload = (previousHash == null ? "" : previousHash) + "|" + id + "|" + taskId
                + "|" + occurredAt + "|" + event;
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
            if (!digest(previous, row.id, row.taskId, row.occurredAt, row.event).equals(row.hash)) return index;
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

        public Row(String id, String taskId, long occurredAt, String event, String prevHash, String hash) {
            this.id = id;
            this.taskId = taskId;
            this.occurredAt = occurredAt;
            this.event = event;
            this.prevHash = prevHash == null ? "" : prevHash;
            this.hash = hash == null ? "" : hash;
        }
    }
}
