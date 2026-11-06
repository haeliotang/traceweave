import com.inceptless.traceweave.AuditChain;
import java.util.ArrayList;
import java.util.List;

/** Host check for digest versioning, run by test_audit_chain.py. */
public final class AuditChainHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("FAIL " + label);
        checks++;
    }

    public static void main(String[] args) {
        // The legacy ambiguity the auditor found: two different (taskId, occurredAt, event)
        // tuples joined with '|' produce the same preimage.
        String legacy = AuditChain.digestV1("", "id", "x|1000", 2000, "e");
        String shifted = AuditChain.digestV1("", "id", "x", 1000, "2000|e");
        check(legacy.equals(shifted), "v1 is ambiguous by construction");

        String v2 = AuditChain.digestV2("", "id", "x|1000", 2000, "e");
        String v2shifted = AuditChain.digestV2("", "id", "x", 1000, "2000|e");
        check(!v2.equals(v2shifted), "v2 separates the fields");

        // Existing v1 rows must still verify after the upgrade.
        List<AuditChain.Row> rows = new ArrayList<>();
        String firstHash = AuditChain.digestV1("", "a", "task", 1, "task_created");
        rows.add(new AuditChain.Row("a", "task", 1, "task_created", "", firstHash));
        String secondHash = AuditChain.digest(firstHash, "b", "task", 2, "task_succeeded");
        rows.add(new AuditChain.Row("b", "task", 2, "task_succeeded", firstHash, secondHash,
            AuditChain.CURRENT_DIGEST_VERSION));
        check(AuditChain.firstBrokenIndex(rows) == -1, "mixed v1/v2 chain verifies");

        // A rewritten v1 row is still detected because the hash is committed.
        List<AuditChain.Row> tampered = new ArrayList<>(rows);
        tampered.set(0, new AuditChain.Row("a", "other", 1, "task_created", "", firstHash));
        check(AuditChain.firstBrokenIndex(tampered) == 0, "tampered row detected");

        // Empty chain is intact.
        check(AuditChain.firstBrokenIndex(new ArrayList<>()) == -1, "empty chain");

        System.out.println("PASS audit_chain checks=" + checks);
    }
}
