package org.traceform.agentos;

/** Pure scheduling policy so the rules can be tested on the host. */
final class AutoSyncPolicy {
    static final long MIN_INTERVAL_MS = 15 * 60 * 1000L;
    static final long DEFAULT_INTERVAL_MS = 60 * 60 * 1000L;

    private AutoSyncPolicy() {}

    static long clampInterval(long intervalMs) {
        return Math.max(MIN_INTERVAL_MS, intervalMs <= 0 ? DEFAULT_INTERVAL_MS : intervalMs);
    }
}
