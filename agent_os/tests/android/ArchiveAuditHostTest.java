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

        List<ArchiveAudit.Row> edited = new ArrayList<>(rows);
        edited.set(1, new ArchiveAudit.Row("b", "task-1", 2000, "status_completed"));
        check(!ArchiveAudit.matches(head, edited), "edited audit row is rejected");

        List<ArchiveAudit.Row> extended = new ArrayList<>(rows);
        extended.add(new ArchiveAudit.Row("c", "task-1", 3000, "task_succeeded"));
        check(!ArchiveAudit.matches(head, extended), "an extra row is rejected");

        check("".equals(ArchiveAudit.head(new ArrayList<>())), "no rows means no head");
        check(ArchiveAudit.matches("", new ArrayList<>()), "empty archive without a head is fine");
        System.out.println("PASS archive_audit checks=" + checks);
    }
}
