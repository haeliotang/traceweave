package org.traceform.edge;

import java.util.Map;

/**
 * Supplies the parity-tested reference features to the service's FeatureReadings: builds the
 * observation from what the native sources provide, runs the ported extractor and records each
 * result with its provenance kind. The values are formulas over those inputs, not new sensors.
 */
final class ReferenceFeatureSupplier {
    static final String SOURCE = "python_reference_formula_port_over_native_inputs_not_measurement_validation";
    /**
     * The Python reference chain handles one frame about every 30 seconds, and its formulas assume
     * that cadence (gaps of 2-3600 s gate velocities and derivatives, windows count frames). The
     * service samples every second, so the extractor runs once per period and its results are held,
     * with their true timestamps, until the next run.
     */
    static final long PERIOD_NANOS = 30000000000L;
    /** Held values stay readable a little longer than one period, then expire if sampling stops. */
    private static final long MAX_AGE_NANOS = 35000000000L;
    private static final long NEVER = Long.MIN_VALUE;
    private final ReferenceFeatureEngine engine = new ReferenceFeatureEngine();
    private Map<String, Object> lastObservation;
    private long lastRunNanos = NEVER;
    private boolean ran;

    /** Forget all history, like a freshly started reference runtime. */
    void reset() {
        engine.reset();
        lastObservation = null;
        lastRunNanos = NEVER;
        ran = false;
    }

    /** True if the latest supply call ran the extractor (false: held values were left in place). */
    boolean lastRan() { return ran; }

    /** The observation fed to the extractor for the latest sample (for diagnostics and tests). */
    Map<String, Object> lastObservation() { return lastObservation; }

    /** Number of outputs recorded by the latest call; 0 when it was not yet due. */
    int supply(FeatureReadings readings, ReferenceObservation.Inputs inputs) {
        boolean due = lastRunNanos == NEVER || inputs.nanos < lastRunNanos || inputs.nanos - lastRunNanos >= PERIOD_NANOS;
        ran = due;
        if (!due) return 0;
        lastRunNanos = inputs.nanos;
        lastObservation = ReferenceObservation.build(readings, inputs);
        int supplied = 0;
        for (Map.Entry<String, Double> entry : engine.extract(lastObservation).entrySet()) {
            double value = entry.getValue();
            if (!Double.isFinite(value)) continue;
            readings.put(entry.getKey(), value, ReferenceFeatureKinds.kind(entry.getKey()), SOURCE,
                inputs.nanos, MAX_AGE_NANOS);
            supplied++;
        }
        return supplied;
    }
}
