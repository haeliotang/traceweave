package org.traceform.edge;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

/** Aggregate event counts only; package names and individual events are not retained. */
final class NativeUsageSource {
    static final class Counts {
        final double resumes, interactiveChecks;
        final long timestampNanos;
        Counts(double resumes, double interactiveChecks) { this(resumes, interactiveChecks, 0); }
        Counts(double resumes, double interactiveChecks, long timestampNanos) {
            this.resumes = resumes; this.interactiveChecks = interactiveChecks; this.timestampNanos = timestampNanos;
        }
    }
    private Counts cached;
    private long cachedWallMillis;
    void reset() { cached = null; cachedWallMillis = 0; }
    Counts sample(Context context, long wallMillis, long nanos) {
        if (!allowed(context)) {
            reset();
            return new Counts(Double.NaN, Double.NaN, nanos);
        }
        if (cached != null && wallMillis >= cachedWallMillis && nanos >= cached.timestampNanos
                && Math.abs((wallMillis - cachedWallMillis) / 1000.0 - (nanos - cached.timestampNanos) / 1e9) <= 1
                && nanos - cached.timestampNanos < 60000000000L) return cached;
        Counts result = read(context, wallMillis);
        cached = new Counts(result.resumes, result.interactiveChecks, nanos); cachedWallMillis = wallMillis;
        return cached;
    }
    private static boolean allowed(Context context) {
        try {
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (SecurityException unavailable) { return false; }
    }
    static final class Window {
        private final int[] types = new int[120];
        private int next, size, resumes, checks;
        void add(int type) {
            if (size == types.length) subtract(types[next]); else size++;
            types[next] = type; next = (next + 1) % types.length;
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) resumes++;
            if (type == UsageEvents.Event.SCREEN_INTERACTIVE || type == UsageEvents.Event.KEYGUARD_HIDDEN) checks++;
        }
        private void subtract(int type) {
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) resumes--;
            if (type == UsageEvents.Event.SCREEN_INTERACTIVE || type == UsageEvents.Event.KEYGUARD_HIDDEN) checks--;
        }
        Counts counts() { return new Counts(Math.min(50, resumes), Math.min(20, checks)); }
    }
    static Counts read(Context context, long nowMillis) {
        Counts missing = new Counts(Double.NaN, Double.NaN);
        if (nowMillis < 86400000L) return missing;
        try {
            if (!allowed(context)) return missing;
            UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
            if (manager == null) return missing;
            long begin = nowMillis - 86400000L, previous = begin;
            UsageEvents events = manager.queryEvents(begin, nowMillis);
            if (events == null) return missing;
            Window window = new Window();
            UsageEvents.Event event = new UsageEvents.Event();
            int scanned = 0;
            while (events.hasNextEvent()) {
                if (++scanned > 50000 || !events.getNextEvent(event)) return missing;
                long timestamp = event.getTimeStamp();
                if (timestamp < previous || timestamp < begin || timestamp >= nowMillis) return missing;
                previous = timestamp;
                window.add(event.getEventType());
            }
            return window.counts();
        } catch (SecurityException unavailable) { return missing; }
    }
}
