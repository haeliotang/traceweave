package org.traceform.edge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three parity-tested ports of the Python reference extractor behind one call. Each group keeps
 * its own history, so the engine must be fed every sample in order and reset whenever the
 * observation stream is discontinuous.
 */
final class ReferenceFeatureEngine {
    private final ReferenceUsageFeatures usage = new ReferenceUsageFeatures();
    private final ReferenceSignalFeatures signal = new ReferenceSignalFeatures();
    private final ReferenceDynamicsFeatures dynamics = new ReferenceDynamicsFeatures();

    static final String[] NAMES = concat(ReferenceUsageFeatures.NAMES, ReferenceSignalFeatures.NAMES,
        ReferenceDynamicsFeatures.NAMES);

    private static String[] concat(String[]... groups) {
        int total = 0;
        for (String[] group : groups) total += group.length;
        String[] all = new String[total];
        int at = 0;
        for (String[] group : groups) { System.arraycopy(group, 0, all, at, group.length); at += group.length; }
        return all;
    }

    void reset() {
        usage.reset();
        signal.reset();
        dynamics.reset();
    }

    /** Raw (unscaled) values of every ported output for one observation, in NAMES order. */
    Map<String, Double> extract(Map<String, Object> observation) {
        Map<String, Double> result = new LinkedHashMap<>();
        put(result, ReferenceUsageFeatures.NAMES, usage.extract(observation));
        put(result, ReferenceSignalFeatures.NAMES, signal.extract(observation));
        put(result, ReferenceDynamicsFeatures.NAMES, dynamics.extract(observation));
        return result;
    }

    private static void put(Map<String, Double> into, String[] names, double[] values) {
        for (int i = 0; i < names.length; i++) into.put(names[i], values[i]);
    }
}
