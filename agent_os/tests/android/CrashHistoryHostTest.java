import com.inceptless.traceweave.CrashHistory;
import java.util.ArrayList;
import java.util.List;

/** Host check for the crash history format, run by test_crash_history.py. */
public final class CrashHistoryHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("FAIL " + message);
        checks++;
    }

    public static void main(String[] args) {
        List<CrashHistory.Entry> entries = new ArrayList<>();
        entries.add(new CrashHistory.Entry(1000, "java.lang.IllegalStateException", "bad\tstate\nagain"));
        entries.add(new CrashHistory.Entry(2000, "java.lang.RuntimeException", ""));
        String text = CrashHistory.format(entries);
        check(text.split("\n").length == 2, "one line per crash");
        check(!text.contains("\t\t"), "tabs inside the message are flattened");

        ArrayList<CrashHistory.Entry> parsed = CrashHistory.parse(text);
        check(parsed.size() == 2, "both entries round-trip");
        check(parsed.get(0).occurredAt == 1000, "timestamp survives");
        check(parsed.get(0).type.equals("java.lang.IllegalStateException"), "type survives");
        check(parsed.get(1).label().equals("RuntimeException"), "empty message shows the type only");
        check(parsed.get(0).label().equals("IllegalStateException: bad state again"), "label drops the package");

        check(CrashHistory.parse("").isEmpty(), "empty text has no entries");
        check(CrashHistory.parse("garbage\n").isEmpty(), "damaged lines are skipped");
        check(CrashHistory.parse("not-a-number\ttype\tmessage").isEmpty(), "a bad timestamp is skipped");

        ArrayList<CrashHistory.Entry> kept = CrashHistory.prepend(entries,
            new CrashHistory.Entry(3000, "java.lang.Exception", "newest"), 2);
        check(kept.size() == 2, "history is capped");
        check(kept.get(0).occurredAt == 3000, "newest entry comes first");
        System.out.println("PASS crash_history checks=" + checks);
    }
}
