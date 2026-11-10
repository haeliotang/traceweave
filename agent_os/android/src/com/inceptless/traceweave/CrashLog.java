// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Keeps the last crash on disk so the user can export it on purpose. Never uploads. */
final class CrashLog {
    private static final int MAX_BYTES = 64 * 1024;
    private static final String FILE_NAME = "last_crash.txt";
    private static final String HISTORY_NAME = "crash_history.txt";
    private static final int HISTORY_LIMIT = 20;

    private CrashLog() {}

    static String format(Throwable error) {
        if (error == null) return "";
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        String text = buffer.toString();
        return text.length() > MAX_BYTES ? text.substring(0, MAX_BYTES) : text;
    }

    /**
     * Strips the parts of a stack trace that most often carry user content: the exception
     * message (which can quote parsed JSON, file names or record text) and absolute paths.
     * The summary keeps the exception type and the top frame, which is what actually helps.
     */
    static String sanitize(String summary) {
        if (summary == null || summary.isEmpty()) return "";
        String[] lines = summary.split("\n");
        StringBuilder clean = new StringBuilder();
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty()) continue;
            int colon = text.indexOf(':');
            // "java.lang.IllegalStateException: <user content>" -> keep the type only.
            if (colon > 0 && text.substring(0, colon).matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
                text = text.substring(0, colon);
            }
            // Absolute paths can name the user's directories or files.
            text = text.replaceAll("(/[A-Za-z0-9._-]+){2,}", "<path>");
            clean.append(text).append('\n');
        }
        return clean.toString().trim();
    }

    /** Short, human-readable summary: exception type, message and the first few frames. */
    static String summarize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] lines = raw.split("\n");
        String first = "";
        StringBuilder frames = new StringBuilder();
        int taken = 0;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (index == 0) {
                // The first line may be the timestamp written by record().
                if (line.matches("\\d+")) continue;
            }
            if (first.isEmpty() && !line.isEmpty()) {
                first = line;
                continue;
            }
            if (line.startsWith("at ") && taken < 5) {
                if (frames.length() > 0) frames.append("\n");
                frames.append(line);
                taken++;
            }
            if (taken >= 5) break;
        }
        if (first.isEmpty()) return "";
        return frames.length() == 0 ? first : first + "\n" + frames;
    }

    static void record(Context context, Throwable error) {
        String raw = format(error);
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), FILE_NAME))) {
            output.write((System.currentTimeMillis() + "\n" + raw).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        appendHistory(context, error, raw);
    }

    /** One line per crash so the diagnostics page can show a trend instead of a single event. */
    private static void appendHistory(Context context, Throwable error, String raw) {
        String type = error == null ? "unknown" : error.getClass().getName();
        String message = error == null || error.getMessage() == null ? "" : error.getMessage();
        if (message.length() > 200) message = message.substring(0, 200);
        java.util.ArrayList<CrashHistory.Entry> existing = new java.util.ArrayList<>();
        for (CrashLog.Entry entry : history(context))
            existing.add(new CrashHistory.Entry(entry.occurredAt, entry.type, entry.message));
        java.util.ArrayList<CrashHistory.Entry> entries = CrashHistory.prepend(existing,
            new CrashHistory.Entry(System.currentTimeMillis(), type, message), HISTORY_LIMIT);
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), HISTORY_NAME))) {
            output.write(CrashHistory.format(entries).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    static java.util.ArrayList<CrashLog.Entry> history(Context context) {
        java.util.ArrayList<CrashLog.Entry> entries = new java.util.ArrayList<>();
        File file = new File(context.getFilesDir(), HISTORY_NAME);
        if (!file.exists()) return entries;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            for (CrashHistory.Entry entry : CrashHistory.parse(text)) {
                entries.add(new Entry(entry.occurredAt, entry.type, entry.message, ""));
            }
        } catch (Exception ignored) {
        }
        return entries;
    }

    static void clearHistory(Context context) {
        File file = new File(context.getFilesDir(), HISTORY_NAME);
        if (file.exists()) file.delete();
    }

    static final class Entry {
        final long occurredAt;
        final String type;
        final String message;
        final String summary;

        Entry(long occurredAt, String type, String message, String summary) {
            this.occurredAt = occurredAt;
            this.type = type;
            this.message = message == null ? "" : message;
            this.summary = summary == null ? "" : summary;
        }

        /** "java.lang.IllegalStateException: bad state" without the package prefix. */
        String label() {
            int dot = type.lastIndexOf('.');
            String shortType = dot >= 0 ? type.substring(dot + 1) : type;
            return message.isEmpty() ? shortType : shortType + ": " + message;
        }
    }

    static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return "";
        try {
            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
            String text = new String(data, StandardCharsets.UTF_8);
            return text.length() > MAX_BYTES ? text.substring(0, MAX_BYTES) : text;
        } catch (Exception error) {
            return "";
        }
    }

    static void clear(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.exists()) file.delete();
    }

    /** Installs a handler that records the crash once and then delegates to the previous one. */
    static void install(Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            record(context, error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
