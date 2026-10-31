package com.inceptless.traceweave;

/** Turns audit identifiers into short, readable lines. */
final class AuditText {
    private AuditText() {}

    static String describe(String event, boolean en) {
        if (event == null || event.isEmpty()) return "";
        String name = event;
        String argument = "";
        int split = event.indexOf(':');
        if (split > 0) {
            name = event.substring(0, split);
            argument = event.substring(split + 1);
        }
        String plain = "audit.event." + name;
        if (T.has(plain)) return T.s(plain, en);
        String withArgument = plain + ".arg";
        if (T.has(withArgument)) return T.format(withArgument, en, argument);
        if (name.startsWith("memory_")) return T.format("audit.event.memory_other", en, name);
        if (name.startsWith("sync_")) return T.format("audit.event.sync_other", en, name);
        if (name.startsWith("task_") || name.startsWith("executor_")) return T.format("audit.event.task_other", en, name);
        return event;
    }
}
