package org.traceform.edge;

import java.util.Map;

/**
 * Supplies the parity-tested reference features to the service's FeatureReadings: builds the
 * observation from what the native sources provide, runs the ported extractor and records each
 * result with its provenance kind. The values are formulas over those inputs, not new sensors.
 */
final class ReferenceFeatureSupplier {
    static final String SOURCE = "python_reference_formula_port_over_native_inputs_not_measurement_validation";
    private static final long MAX_AGE_NANOS = 5000000000L;
    private final ReferenceFeatureEngine engine = new ReferenceFeatureEngine();
    private Map<String, Object> lastObservation;

    /** Forget all history, like a freshly started reference runtime. */
    void reset() {
        engine.reset();
        lastObservation = null;
    }

    /** The observation fed to the extractor for the latest sample (for diagnostics and tests). */
    Map<String, Object> lastObservation() { return lastObservation; }

    /** Number of outputs recorded by the latest call. */
    int supply(FeatureReadings readings, ReferenceObservation.Inputs inputs) {
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
