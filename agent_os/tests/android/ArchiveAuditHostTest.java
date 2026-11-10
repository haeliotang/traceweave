// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

import com.inceptless.traceweave.ArchiveAudit;
import com.inceptless.traceweave.AuditChain;
import java.util.ArrayList;
import java.util.List;

/** Host check for the archive audit head, run by test_archive_audit.py. */
public final class ArchiveAuditHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("FAIL " + message);
        checks++;
    }

    public static void main(String[] args) {
        List<ArchiveAudit.Row> rows = new ArrayList<>();
        rows.add(new ArchiveAudit.Row("a", "task-1", 1000, "task_created"));
        rows.add(new ArchiveAudit.Row("b", "task-1", 2000, "status_active"));
        String head = ArchiveAudit.head(rows);
        check(head.length() == 64, "head is a sha256 hex string");
        check(ArchiveAudit.matches(head, rows), "declared head matches its rows");
        check(ArchiveAudit.matches("", rows), "an archive without a head is accepted for compatibility");
        ArchiveAudit.Verdict noHead = ArchiveAudit.verify("", rows.size(), rows);
        check(!noHead.verified && "no_head".equals(noHead.reason), "no head means unverified");
        ArchiveAudit.Verdict legacy = ArchiveAudit.verify(head, rows.size(), rows);
        check(legacy.verified && "declared_head_only".equals(legacy.reason), "legacy head verifies");

        List<ArchiveAudit.Row> edited = new ArrayList<>(rows);
        edited.set(1, new ArchiveAudit.Row("b", "task-1", 2000, "status_completed"));
        check(!ArchiveAudit.matches(head, edited), "edited audit row is rejected");

        List<ArchiveAudit.Row> extended = new ArrayList<>(rows);
        extended.add(new ArchiveAudit.Row("c", "task-1", 3000, "task_succeeded"));
        check(!ArchiveAudit.matches(head, extended), "an extra row is rejected");

        // Schema-14 archives carry per-row hashes and are verified link by link.
        List<ArchiveAudit.Row> exact = new ArrayList<>();
        String previous = AuditChain.GENESIS;
        for (String[] event : new String[][]{{"a", "task-1", "1000", "task_created"},
                                             {"b", "task-1", "2000", "status_active"}}) {
            String hash = AuditChain.digestV2(previous, event[0], event[1], Long.parseLong(event[2]), event[3]);
            exact.add(new ArchiveAudit.Row(event[0], event[1], Long.parseLong(event[2]), event[3], previous, hash,
                AuditChain.CURRENT_DIGEST_VERSION));
            previous = hash;
        }
        ArchiveAudit.Verdict perRow = ArchiveAudit.verify(previous, exact.size(), exact);
        check(perRow.verified && "per_row_hashes".equals(perRow.reason), "per-row hashes verify");
        ArchiveAudit.Verdict count = ArchiveAudit.verify(previous, exact.size() + 1, exact);
        check(!count.verified && "archive_count_mismatch".equals(count.reason), "count mismatch rejected");
        List<ArchiveAudit.Row> forged = new ArrayList<>(exact);
        forged.set(1, new ArchiveAudit.Row("b", "task-1", 2000, "status_completed", exact.get(1).prevHash,
            exact.get(1).hash, exact.get(1).digestVersion));
        ArchiveAudit.Verdict forgedVerdict = ArchiveAudit.verify(previous, forged.size(), forged);
        check(!forgedVerdict.verified, "forged row rejected even with hashes present");

        check("".equals(ArchiveAudit.head(new ArrayList<>())), "no rows means no head");
        check(ArchiveAudit.matches("", new ArrayList<>()), "empty archive without a head is fine");
        System.out.println("PASS archive_audit checks=" + checks);
    }
}
