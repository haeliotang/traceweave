package org.traceform.agentos;

public final class CrashLogHostTest {
    public static void main(String[] args) {
        String text = CrashLog.format(new IllegalStateException("boom"));
        if (!text.contains("IllegalStateException") || !text.contains("boom"))
            throw new AssertionError("stack trace missing");
        if (!CrashLog.format(null).isEmpty()) throw new AssertionError("null should be empty");
        System.out.println("CRASH_LOG_OK");
    }
}
