// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Aggregate event counts only; package names and individual events are not retained. */
final class NativeUsageSource {
    static final String[] SCREEN_STATES = {"ON", "DOZING", "OFF", "UNKNOWN"};
    static final class Counts {
        final double resumes, interactiveChecks;
        final long timestampNanos;
        /** digital_context per screen state, computed once from the event window; no package names kept. */
        private final Map<String, Map<String, Object>> digital;
        Counts(double resumes, double interactiveChecks) { this(resumes, interactiveChecks, 0); }
        Counts(double resumes, double interactiveChecks, long timestampNanos) {
            this(resumes, interactiveChecks, timestampNanos, null);
        }
        Counts(double resumes, double interactiveChecks, long timestampNanos, Map<String, Map<String, Object>> digital) {
            this.resumes = resumes; this.interactiveChecks = interactiveChecks; this.timestampNanos = timestampNanos;
            this.digital = digital;
        }
        /** The Python probe's digital_context for the given screen state, or null when usage stats are unavailable. */
        Map<String, Object> digital(String screenState) {
            return digital == null ? null : digital.get(digital.containsKey(screenState) ? screenState : "UNKNOWN");
        }
        Counts at(long nanos) { return new Counts(resumes, interactiveChecks, nanos, digital); }
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
        cached = result.at(nanos); cachedWallMillis = wallMillis;
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
        private final ArrayDeque<String[]> recent = new ArrayDeque<>();
        private int next, size, resumes, checks;
        /** Adds an event; the package name is only held for the digital_context entropy and dropped with the window. */
        void add(int type, String packageName) {
            recent.addLast(new String[]{eventName(type), packageName == null || packageName.trim().isEmpty() ? null : packageName});
            if (recent.size() > 120) recent.removeFirst();
            add(type);
        }
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
        /** Counts plus digital_context for every screen state, derived from the retained 120 events. */
        Counts countsWithDigital() {
            List<String[]> events = new ArrayList<>(recent);
            Map<String, Map<String, Object>> byScreen = new HashMap<>();
            for (String state : SCREEN_STATES) {
                byScreen.put(state, Collections.unmodifiableMap(ReferenceDigitalProbe.synthesize(state, events, 0, false, 0.0)));
            }
            return new Counts(Math.min(50, resumes), Math.min(20, checks), 0, Collections.unmodifiableMap(byScreen));
        }
        private static String eventName(int type) {
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) return "ACTIVITY_RESUMED";
            if (type == UsageEvents.Event.SCREEN_INTERACTIVE) return "SCREEN_INTERACTIVE";
            if (type == UsageEvents.Event.KEYGUARD_HIDDEN) return "KEYGUARD_HIDDEN";
            return "OTHER";
        }
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
                window.add(event.getEventType(), event.getPackageName());
            }
            return window.countsWithDigital();
        } catch (SecurityException unavailable) { return missing; }
    }
}
