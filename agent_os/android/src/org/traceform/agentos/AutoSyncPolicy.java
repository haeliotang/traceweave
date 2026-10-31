package org.traceform.agentos;

/** Pure scheduling policy so the rules can be tested on the host. */
final class AutoSyncPolicy {
    static final long MIN_INTERVAL_MS = 15 * 60 * 1000L;
    static final long DEFAULT_INTERVAL_MS = 60 * 60 * 1000L;

    private AutoSyncPolicy() {}

    /** Picks the device whose last sync is newest; ties fall back to list order. */
    static int pickTarget(int count, long[] lastSyncedAt) {
        if (count <= 0) return -1;
        int best = 0;
        for (int index = 1; index < count; index++) {
            if (lastSyncedAt[index] > lastSyncedAt[best]) best = index;
        }
        return best;
    }

    static long clampInterval(long intervalMs) {
        return Math.max(MIN_INTERVAL_MS, intervalMs <= 0 ? DEFAULT_INTERVAL_MS : intervalMs);
    }
}
