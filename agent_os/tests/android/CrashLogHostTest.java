// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

public final class CrashLogHostTest {
    public static void main(String[] args) {
        String text = CrashLog.format(new IllegalStateException("boom"));
        if (!text.contains("IllegalStateException") || !text.contains("boom"))
            throw new AssertionError("stack trace missing");
        if (!CrashLog.format(null).isEmpty()) throw new AssertionError("null should be empty");
        String raw = "1793447000000\njava.lang.IllegalStateException: boom\n\tat a.b.C(C.java:1)\n\tat d.e.F(F.java:2)\n\tat g.h.I(I.java:3)\n\tat j.k.L(L.java:4)\n\tat m.n.O(O.java:5)\n\tat p.q.R(R.java:6)\n";
        String summary = CrashLog.summarize(raw);
        if (!summary.startsWith("java.lang.IllegalStateException: boom")) throw new AssertionError("summary header");
        int frames = summary.split("\n").length - 1;
        if (frames != 5) throw new AssertionError("expected five frames, got " + frames);
        if (CrashLog.summarize("").isEmpty() == false) throw new AssertionError("empty summary");
        System.out.println("CRASH_LOG_OK");
    }
}
