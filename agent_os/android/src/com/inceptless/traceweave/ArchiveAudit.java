package com.inceptless.traceweave;

import java.util.List;

/**
 * An archive declares the chain head of the audit rows it carries. Recomputing the
 * chain over those rows detects an edit that did not also update the declared head.
 *
 * <p>Older archives carry no per-row hashes, so the recomputation is the only check and it
 * has to assume the current digest version; archives written from schema 14 on carry
 * {@code hash}/{@code prevHash}/{@code digestVersion} and are verified exactly.
 */
public final class ArchiveAudit {
    private ArchiveAudit() {}

    /** Result of checking one archive's audit section. */
    public static final class Verdict {
        public final boolean verified;
        public final String reason;

        Verdict(boolean verified, String reason) {
            this.verified = verified;
            this.reason = reason;
        }
    }

    /** Returns the head the given rows produce under the legacy digest, or "" when empty. */
    public static String head(List<Row> rows) {
        String previous = AuditChain.GENESIS;
        for (Row row : rows) previous = AuditChain.digestV1(previous, row.id, row.taskId, row.occurredAt, row.event);
        return rows.isEmpty() ? "" : previous;
    }

    /** True when the declared head matches the rows, or when none was declared. */
    public static boolean matches(String declaredHead, List<Row> rows) {
        if (declaredHead == null || declaredHead.isEmpty()) return true;
        return declaredHead.equals(head(rows));
    }

    /**
     * Verifies an archive section. Rows that carry their own hash are checked link by link;
     * rows without hashes fall back to the declared head.
     */
    public static Verdict verify(String declaredHead, int declaredCount, List<Row> rows) {
        boolean hashes = !rows.isEmpty();
        for (Row row : rows) hashes &= row.hash != null && !row.hash.isEmpty();
        if (hashes) {
            String previous = AuditChain.GENESIS;
            for (Row row : rows) {
                if (!previous.equals(row.prevHash == null ? "" : row.prevHash))
                    return new Verdict(false, "archive_chain_broken");
                String expected = row.digestVersion >= AuditChain.DIGEST_VERSION_LENGTH_PREFIXED
                    ? AuditChain.digestV2(previous, row.id, row.taskId, row.occurredAt, row.event)
                    : AuditChain.digestV1(previous, row.id, row.taskId, row.occurredAt, row.event);
                if (!expected.equals(row.hash)) return new Verdict(false, "archive_hash_mismatch");
                previous = row.hash;
            }
            if (declaredHead != null && !declaredHead.isEmpty() && !declaredHead.equals(previous))
                return new Verdict(false, "archive_head_mismatch");
            if (declaredCount > 0 && declaredCount != rows.size())
                return new Verdict(false, "archive_count_mismatch");
            return new Verdict(true, rows.isEmpty() ? "empty" : "per_row_hashes");
        }
        if (declaredHead == null || declaredHead.isEmpty()) return new Verdict(false, "no_head");
        return new Verdict(matches(declaredHead, rows), "declared_head_only");
    }

    public static final class Row {
        public final String id;
        public final String taskId;
        public final long occurredAt;
        public final String event;
        public final String prevHash;
        public final String hash;
        public final int digestVersion;

        public Row(String id, String taskId, long occurredAt, String event) {
            this(id, taskId, occurredAt, event, "", "", AuditChain.DIGEST_VERSION_LEGACY);
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
