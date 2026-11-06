import com.inceptless.traceweave.AnchorMac;
import com.inceptless.traceweave.AuditAnchorVerifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Host check for the anchor rules, run by test_audit_anchor_verifier.py. */
public final class AuditAnchorVerifierHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("FAIL " + message);
        checks++;
    }

    private static List<String> hashes(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static List<AnchorMac.Entry> anchors(String... chain) {
        List<AnchorMac.Entry> entries = new ArrayList<>();
        for (int index = 0; index < chain.length; index++)
            entries.add(new AnchorMac.Entry(index, chain[index], 1000 + index, "mac"));
        return entries;
    }

    public static void main(String[] args) {
        List<String> chain = hashes("a", "b", "c");

        check(AuditAnchorVerifier.check(3, chain, anchors("a", "b", "c"), null, null) == null,
            "no state file is accepted when the anchors match");

        check(AuditAnchorVerifier.check(3, chain, anchors("a", "b", "c"), 3, "c") == null,
            "complete anchored chain verifies");

        check("head_mismatch".equals(AuditAnchorVerifier.check(3, hashes("a", "b", "x"),
            anchors("a", "b", "x"), 3, "c")), "a rewritten head is rejected");

        check("chain_truncated".equals(AuditAnchorVerifier.check(2, hashes("a", "b"),
            anchors("a", "b"), 3, "c")), "removing anchored rows is rejected");

        check("anchor_missing_1".equals(AuditAnchorVerifier.check(3, chain,
            anchors("a", "c").subList(0, 1), 3, "c")), "a deleted anchor inside the range is rejected");
        // rebuild an anchors list that skips index 1 explicitly
        List<AnchorMac.Entry> gap = new ArrayList<>();
        gap.add(new AnchorMac.Entry(0, "a", 1000, "mac"));
        gap.add(new AnchorMac.Entry(2, "c", 1002, "mac"));
        check("anchor_missing_1".equals(AuditAnchorVerifier.check(3, chain, gap, 3, "c")),
            "skipping an index is a missing anchor");

        List<AnchorMac.Entry> wrong = new ArrayList<>();
        wrong.add(new AnchorMac.Entry(0, "a", 1000, "mac"));
        wrong.add(new AnchorMac.Entry(1, "zzz", 1001, "mac"));
        wrong.add(new AnchorMac.Entry(2, "c", 1002, "mac"));
        check("anchor_hash_mismatch_1".equals(AuditAnchorVerifier.check(3, chain, wrong, 3, "c")),
            "an anchor that does not match its row is rejected");

        List<AnchorMac.Entry> extra = new ArrayList<>(anchors("a", "b", "c"));
        extra.add(new AnchorMac.Entry(7, "d", 1007, "mac"));
        check("anchor_out_of_range_7".equals(AuditAnchorVerifier.check(3, chain, extra, 3, "c")),
            "an anchor past the chain is rejected");

        check(AuditAnchorVerifier.check(0, new ArrayList<>(), new ArrayList<>(), null, null) == null,
            "an empty chain with no state is fine");
        check(AuditAnchorVerifier.check(1, hashes("a"), anchors("a"), 0, "") == null,
            "a zero count state means the baseline has no rows yet");
        System.out.println("PASS audit_anchor_verifier checks=" + checks);
    }
}
