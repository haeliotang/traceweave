import com.inceptless.traceweave.ArchiveCompat;

/** Host check for the pre-rename archive rules, run by test_archive_compat.py. */
public final class ArchiveCompatHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("FAIL " + message);
        checks++;
    }

    public static void main(String[] args) {
        check(ArchiveCompat.isFormatAccepted(ArchiveCompat.FORMAT), "current format accepted");
        check(ArchiveCompat.isFormatAccepted("org.traceform.agentos.memory"), "legacy format accepted");
        check(!ArchiveCompat.isFormatAccepted("com.example.other"), "unknown format rejected");
        check("traceweave".equals(ArchiveCompat.normalizeSource("traceform")), "legacy source mapped");
        check("user".equals(ArchiveCompat.normalizeSource("user")), "other sources unchanged");
        check("traceweave.local_history.read".equals(ArchiveCompat.normalizeCapabilityId("traceform.local_history.read")),
            "capability id rewritten");
        check("system.notification".equals(ArchiveCompat.normalizeCapabilityId("system.notification")),
            "unrelated capability untouched");
        check("{\"capability\":\"traceweave.local_history.read\"}".equals(
                ArchiveCompat.normalizePlanJson("{\"capability\":\"traceform.local_history.read\"}")),
            "plan json rewritten");
        System.out.println("PASS archive_compat checks=" + checks);
    }
}
