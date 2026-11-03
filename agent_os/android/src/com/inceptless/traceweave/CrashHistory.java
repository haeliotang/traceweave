package com.inceptless.traceweave;

import java.util.ArrayList;
import java.util.List;

/**
 * Crash history lines are one per crash: timestamp, type, message, tab separated.
 * Parsing lives here so the format can be tested without an Android context.
 */
public final class CrashHistory {
    private CrashHistory() {}

    public static String format(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            out.append(entry.occurredAt).append('\t')
                .append(entry.type == null ? "" : entry.type).append('\t')
                .append((entry.message == null ? "" : entry.message).replace('\t', ' ').replace('\n', ' '))
                .append('\n');
        }
        return out.toString();
    }

    public static ArrayList<Entry> parse(String text) {
        ArrayList<Entry> entries = new ArrayList<>();
        if (text == null || text.isEmpty()) return entries;
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            try {
                entries.add(new Entry(Long.parseLong(parts[0]), parts[1].trim(), parts[2].trim()));
            } catch (NumberFormatException ignored) {
                // A damaged line is skipped instead of hiding the rest of the history.
            }
        }
        return entries;
    }

    /** Newest first, capped so the file stays small on disk. */
    public static ArrayList<Entry> prepend(List<Entry> existing, Entry entry, int limit) {
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(entry);
        for (Entry each : existing) {
            if (entries.size() >= limit) break;
            entries.add(each);
        }
        return entries;
    }

    public static final class Entry {
        public final long occurredAt;
        public final String type;
        public final String message;

        public Entry(long occurredAt, String type, String message) {
            this.occurredAt = occurredAt;
            this.type = type == null ? "" : type;
            this.message = message == null ? "" : message;
        }

        /** "IllegalStateException: bad state" without the package prefix. */
        public String label() {
            int dot = type.lastIndexOf('.');
            String shortType = dot >= 0 ? type.substring(dot + 1) : type;
            return message.isEmpty() ? shortType : shortType + ": " + message;
        }
    }
}
