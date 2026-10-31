package com.inceptless.traceweave;

import java.util.ArrayDeque;
import org.json.JSONObject;

/** Bounded in-memory sensor window. Outputs a coarse rule estimate, not an activity label. */
final class MotionWindow {
    private static final long WINDOW_MS = 3000;
    private static final long MIN_SPAN_MS = 1200;
    private static final long MAX_SAMPLE_AGE_MS = 2000;
    private static final int MAX_SAMPLES = 128;
    private static final double GRAVITY = 9.81;

    private static final class Sample {
        final long at;
        final boolean active;
        Sample(long at, boolean active) { this.at = at; this.active = active; }
    }
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private String lastStable = "Unknown";
    private JSONObject lastChange;

    void clear() { samples.clear(); lastStable = "Unknown"; lastChange = null; }

    void add(long at, long wallTime, double acceleration, double rotation) throws Exception {
        if (at <= 0 || wallTime <= 0 || Double.isNaN(acceleration) || Double.isInfinite(acceleration)
                || Double.isNaN(rotation) || Double.isInfinite(rotation) || acceleration < 0 || rotation < 0) return;
        if (!samples.isEmpty() && at <= samples.peekLast().at) return;
        if (!samples.isEmpty() && at - samples.peekLast().at > MAX_SAMPLE_AGE_MS) {
            samples.clear();
            lastStable = "Unknown";
        }
        samples.addLast(new Sample(at, Math.abs(acceleration - GRAVITY) >= .65 || rotation >= .15));
        prune(at);
        String next = state(at);
        if (!"Unknown".equals(next)) {
            if (!"Unknown".equals(lastStable) && !lastStable.equals(next))
                lastChange = new JSONObject().put("kind", "motion_change").put("from", lastStable)
                    .put("to", next).put("observedAt", wallTime).put("source", "accelerometer+gyroscope")
                    .put("method", "three_second_rule_window");
            lastStable = next;
        }
    }

    private void prune(long now) {
        if (!samples.isEmpty() && now >= samples.peekLast().at && now - samples.peekLast().at > MAX_SAMPLE_AGE_MS) {
            samples.clear();
            lastStable = "Unknown";
        }
        while (!samples.isEmpty() && (now - samples.peekFirst().at > WINDOW_MS || samples.size() > MAX_SAMPLES)) samples.removeFirst();
    }

    String state(long now) {
        prune(now);
        if (samples.size() < 3 || now < samples.peekLast().at || now - samples.peekLast().at > MAX_SAMPLE_AGE_MS
                || samples.peekLast().at - samples.peekFirst().at < MIN_SPAN_MS) return "Unknown";
        int active = 0;
        for (Sample sample : samples) if (sample.active) active++;
        double fraction = (double) active / samples.size();
        if (fraction >= .60 && active >= 2) return "MotionDetected";
        if (fraction <= .15) return "LowMotion";
        return "Unknown";
    }

    int sampleCount(long now) { prune(now); return samples.size(); }
    JSONObject lastChange() throws Exception { return lastChange == null ? null : new JSONObject(lastChange.toString()); }
}
