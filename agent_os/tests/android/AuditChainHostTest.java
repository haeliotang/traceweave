import com.inceptless.traceweave.AuditChain;
import java.util.ArrayList;
import java.util.List;

/** Host check for the audit hash chain, run by test_audit_chain.py. */
public final class AuditChainHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("FAIL " + message);
        checks++;
    }

    private static AuditChain.Row row(String previous, String id, long when, String event) {
        return new AuditChain.Row(id, "task-1", when, event, previous,
            AuditChain.digest(previous, id, "task-1", when, event));
    }

    public static void main(String[] args) {
        String first = AuditChain.digest(AuditChain.GENESIS, "a", "task-1", 1000, "task_created");
        List<AuditChain.Row> good = new ArrayList<>();
        good.add(new AuditChain.Row("a", "task-1", 1000, "task_created", AuditChain.GENESIS, first));
        good.add(row(first, "b", 2000, "status_active"));
        check(AuditChain.firstBrokenIndex(good) == -1, "intact chain verifies");

        List<AuditChain.Row> edited = new ArrayList<>(good);
        edited.set(1, new AuditChain.Row("b", "task-1", 2000, "status_completed", first,
            good.get(1).hash));
        check(AuditChain.firstBrokenIndex(edited) == 1, "edited event breaks at its row");

        List<AuditChain.Row> removed = new ArrayList<>();
        removed.add(good.get(0));
        check(AuditChain.firstBrokenIndex(removed) == -1, "a shortened chain still verifies on its own");
        removed.add(good.get(1));
        removed.remove(1);
        check(removed.size() == 1, "removal leaves a verifiable prefix, documented limitation");

        List<AuditChain.Row> resealed = new ArrayList<>();
        String forged = AuditChain.digest(first, "b", "task-1", 2000, "status_completed");
        resealed.add(good.get(0));
        resealed.add(new AuditChain.Row("b", "task-1", 2000, "status_completed", first, forged));
        check(AuditChain.firstBrokenIndex(resealed) == -1,
            "a fully re-sealed chain verifies; tamper-evident is not tamper-proof");

        check(!AuditChain.digest("", "a", "task-1", 1000, "x")
            .equals(AuditChain.digest("", "a", "task-1", 1001, "x")), "timestamp is covered by the hash");
        System.out.println("PASS audit_chain checks=" + checks);
    }
}
