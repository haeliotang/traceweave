package com.inceptless.traceweave.edge;

/**
 * Whole-service cost bookkeeping for the sampling loop: how long each one-second tick took and how
 * much memory the service process held. Android-free so it can be tested on the host. Durations go
 * into a fixed histogram, so a percentile is the upper edge of its bucket (an upper bound), except
 * the 100th percentile, which is the observed maximum.
 */
final class ServiceMetrics {
    /** Upper bucket edges in milliseconds; the last bucket is everything above 1000. */
    static final double[] EDGES_MILLIS = {5, 10, 20, 50, 100, 250, 500, 1000};
    /** A tick longer than the sampling period delays the next one. */
    static final double PERIOD_MILLIS = 1000;

    /** Where a tick spends its time, in order of execution. */
    static final String[] PHASES = {"refresh_model", "native_sources", "reference_supply", "engine_and_csd",
        "database_transaction", "post_write_and_status"};

    private final long[] histogram = new long[EDGES_MILLIS.length + 1];
    private final double[] phaseSum = new double[PHASES.length], phaseMax = new double[PHASES.length];
    private final long[] phaseCount = new long[PHASES.length];
    private long ticks, over;
    private double sum, max;
    private long memorySamples;
    private long pssFirst = -1, pssLast = -1, pssMax = -1, javaMax = -1, nativeMax = -1, javaLast = -1, nativeLast = -1;

    synchronized void recordTick(double millis) {
        if (!(millis >= 0) || Double.isInfinite(millis)) return;
        int bucket = 0;
        while (bucket < EDGES_MILLIS.length && millis > EDGES_MILLIS[bucket]) bucket++;
        histogram[bucket]++;
        ticks++; sum += millis; max = Math.max(max, millis);
        if (millis > PERIOD_MILLIS) over++;
    }

    synchronized void recordPhase(int phase, double millis) {
        if (phase < 0 || phase >= PHASES.length || !(millis >= 0) || Double.isInfinite(millis)) return;
        phaseSum[phase] += millis; phaseCount[phase]++; phaseMax[phase] = Math.max(phaseMax[phase], millis);
    }

    synchronized double phaseMeanMillis(int phase) {
        return phase < 0 || phase >= PHASES.length || phaseCount[phase] == 0 ? Double.NaN : phaseSum[phase] / phaseCount[phase];
    }

    synchronized double phaseMaxMillis(int phase) {
        return phase < 0 || phase >= PHASES.length || phaseCount[phase] == 0 ? Double.NaN : phaseMax[phase];
    }

    synchronized void recordMemory(long pssKb, long javaHeapKb, long nativeHeapKb) {
        if (pssKb < 0 || javaHeapKb < 0 || nativeHeapKb < 0) return;
        if (memorySamples++ == 0) pssFirst = pssKb;
        pssLast = pssKb; javaLast = javaHeapKb; nativeLast = nativeHeapKb;
        pssMax = Math.max(pssMax, pssKb); javaMax = Math.max(javaMax, javaHeapKb); nativeMax = Math.max(nativeMax, nativeHeapKb);
    }

    synchronized long ticks() { return ticks; }
    synchronized long ticksOverBudget() { return over; }
    synchronized double meanMillis() { return ticks == 0 ? Double.NaN : sum / ticks; }
    synchronized double maxMillis() { return ticks == 0 ? Double.NaN : max; }
    synchronized long[] histogram() { return histogram.clone(); }

    synchronized double percentileMillis(double fraction) {
        if (ticks == 0) return Double.NaN;
        if (fraction >= 1.0) return max;
        long target = (long) Math.ceil(fraction * ticks), seen = 0;
        for (int bucket = 0; bucket < histogram.length; bucket++) {
            seen += histogram[bucket];
            if (seen >= target) return bucket < EDGES_MILLIS.length ? EDGES_MILLIS[bucket] : max;
        }
        return max;
    }

    synchronized long memorySamples() { return memorySamples; }
    synchronized long pssKbFirst() { return pssFirst; }
    synchronized long pssKbLast() { return pssLast; }
    synchronized long pssKbMax() { return pssMax; }
    synchronized long javaHeapKbLast() { return javaLast; }
    synchronized long javaHeapKbMax() { return javaMax; }
    synchronized long nativeHeapKbLast() { return nativeLast; }
    synchronized long nativeHeapKbMax() { return nativeMax; }
}
