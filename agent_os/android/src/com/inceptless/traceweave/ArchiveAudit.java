package com.inceptless.traceweave;

import java.util.List;

/**
 * An archive declares the chain head of the audit rows it carries. Recomputing the
 * chain over those rows detects an edit that did not also update the declared head.
 */
public final class ArchiveAudit {
    private ArchiveAudit() {}

    /** Returns the head the given rows produce, or an empty string when there are none. */
    public static String head(List<Row> rows) {
        String previous = AuditChain.GENESIS;
        for (Row row : rows) previous = AuditChain.digest(previous, row.id, row.taskId, row.occurredAt, row.event);
        return rows.isEmpty() ? "" : previous;
    }

    /** True when the declared head matches the rows, or when none was declared. */
    public static boolean matches(String declaredHead, List<Row> rows) {
        if (declaredHead == null || declaredHead.isEmpty()) return true;
        return declaredHead.equals(head(rows));
    }

    public static final class Row {
        public final String id;
        public final String taskId;
        public final long occurredAt;
        public final String event;

        public Row(String id, String taskId, long occurredAt, String event) {
            this.id = id;
            this.taskId = taskId;
            this.occurredAt = occurredAt;
            this.event = event;
        }
    }
}
