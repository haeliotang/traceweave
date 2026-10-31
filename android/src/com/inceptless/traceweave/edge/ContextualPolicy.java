package com.inceptless.traceweave.edge;

/** Logged exploration probabilities; selection alone never dispatches an actuator. */
final class ContextualPolicy {
    static final String[] ACTIONS = {"pulse", "notification", "volume_duck", "color_temperature"};
    static final class Decision {
        final String action;
        final double propensity;
        final double[] probabilities;
        Decision(int index, double[] probabilities) {
            action = index == 0 ? "none" : ACTIONS[index - 1];
            propensity = probabilities[index]; this.probabilities = probabilities;
        }
    }
    static Decision select(long[] counts, long[] rewards, double draw) {
        if (counts == null || rewards == null || counts.length != ACTIONS.length || rewards.length != counts.length
                || !Double.isFinite(draw) || draw < 0 || draw >= 1)
            throw new IllegalArgumentException("bounded policy inputs required");
        int best = 0;
        double bestScore = -1;
        double[] probabilities = new double[ACTIONS.length + 1];
        probabilities[0] = 0.25;
        for (int index = 0; index < counts.length; index++) {
            if (counts[index] < 0 || rewards[index] < 0 || rewards[index] > counts[index])
                throw new IllegalArgumentException("valid outcome counts required");
            double score = (rewards[index] + 1.0) / (counts[index] + 2.0);
            if (score > bestScore) { best = index; bestScore = score; }
            probabilities[index + 1] = 0.15 / ACTIONS.length;
        }
        probabilities[best + 1] += 0.6;
        double cumulative = 0;
        for (int index = 0; index < probabilities.length; index++) {
            cumulative += probabilities[index];
            if (draw < cumulative) return new Decision(index, probabilities);
        }
        return new Decision(probabilities.length - 1, probabilities);
    }
    static long cooldownMillis(int consecutiveFailures) {
        if (consecutiveFailures < 0) throw new IllegalArgumentException("nonnegative fatigue count required");
        return 900000L << Math.min(2, consecutiveFailures);
    }
}
