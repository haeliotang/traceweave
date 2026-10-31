package org.traceform.agentos;

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

    private CrashLog() {}

    static String format(Throwable error) {
        if (error == null) return "";
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        String text = buffer.toString();
        return text.length() > MAX_BYTES ? text.substring(0, MAX_BYTES) : text;
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
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), FILE_NAME))) {
            output.write((System.currentTimeMillis() + "\n" + format(error)).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
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
