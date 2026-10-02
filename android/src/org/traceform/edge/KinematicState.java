package org.traceform.edge;

import java.util.Arrays;

final class KinematicState {
    static final class Result {
        final double[] velocity;
        final double dt;
        final boolean continuous;
        Result(double[] velocity, double dt, boolean continuous) {
            this.velocity = velocity; this.dt = dt; this.continuous = continuous;
        }
    }
    private double[] previous, previousVelocity;
    private boolean[] previousCoverage;
    private long previousNanos, previousWallMillis;
    Result update(double[] vector, boolean[] coverage, long nanos, double alpha) {
        return update(vector, coverage, nanos, alpha, nanos / 1000000);
    }
    Result update(double[] vector, boolean[] coverage, long nanos, double alpha, long wallMillis) {
        double dt = previous == null ? 0 : (nanos - previousNanos) / 1e9;
        boolean continuous = previous != null && dt > 0 && dt <= 300
            && Arrays.equals(coverage, previousCoverage) && wallMillis > previousWallMillis
            && Math.abs((wallMillis - previousWallMillis) / 1000.0 - dt) <= 1;
        double[] velocity = new double[vector.length];
        if (continuous) for (int index = 0; index < vector.length; index++) {
            double derivative = (vector[index] - previous[index]) / dt;
            velocity[index] = previousVelocity == null ? derivative : alpha * derivative + (1 - alpha) * previousVelocity[index];
        }
        previous = vector.clone(); previousCoverage = coverage.clone(); previousNanos = nanos;
        previousWallMillis = wallMillis;
        previousVelocity = continuous ? velocity.clone() : null;
        return new Result(velocity, dt, continuous);
    }
    void reset() { previous = null; previousVelocity = null; previousCoverage = null; previousNanos = 0; }
}
