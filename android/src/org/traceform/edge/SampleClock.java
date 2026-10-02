package org.traceform.edge;

final class SampleClock {
    private long deadline;
    long skipped;
    SampleClock(long firstMillis) { deadline = firstMillis; }
    long next(long nowMillis) {
        deadline += 1000;
        if (deadline <= nowMillis) {
            long missed = (nowMillis - deadline) / 1000 + 1;
            skipped += missed; deadline += missed * 1000;
        }
        return deadline;
    }
}
