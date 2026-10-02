package org.traceform.edge;

/** Bounded grid or normalized-vector diagnostic; a warning does not authorize actuation. */
final class CriticalSlowingDetector {
    static final class Result {
        final int dwellSteps;
        final boolean ready, tipping;
        final double variance, lag1, score;
        Result(int dwellSteps, boolean ready, double variance, double lag1, double score) {
            this.dwellSteps = dwellSteps; this.ready = ready; this.variance = variance;
            this.lag1 = lag1; this.score = score; this.tipping = ready && score >= 2.5;
        }
    }
    private double[][] coordinates = new double[3][300];
    private boolean continuousInput;
    private final double[] projectionResiduals = new double[300], axisResiduals = new double[300];
    private final double[] origins = new double[128], sums = new double[128], squares = new double[128], weightedSums = new double[128];
    private final double[] variances = new double[300], correlations = new double[300];
    private int size, next, baselineSize, baselineNext, dwell, statisticsAxis;
    private String token;

    Result updateContinuous(String current, double[] vector, boolean continuous) {
        return updateDiagnostic(current, vector, continuous, true);
    }
    Result update(String current, double u, double v, boolean continuous) {
        return updateDiagnostic(current, new double[]{u, v}, continuous, false);
    }
    private Result updateDiagnostic(String current, double[] vector, boolean continuous, boolean normalized) {
        if (current == null || current.isEmpty() || vector == null || vector.length < 1 || vector.length > 128)
            throw new IllegalArgumentException("bounded coordinates and token required");
        double square = 0;
        for (double value : vector) {
            if (!Double.isFinite(value) || (normalized && Math.abs(value) > 10))
                throw new IllegalArgumentException("finite bounded normalized coordinates required");
            square += value * value;
        }
        double projection = Math.sqrt(square / (normalized ? vector.length : 1));
        if (!Double.isFinite(projection)) throw new IllegalArgumentException("finite projection required");
        boolean changed = coordinates.length != vector.length + 1 || continuousInput != normalized;
        if (changed) coordinates = new double[vector.length + 1][300];
        if (!continuous || changed) reset();
        continuousInput = normalized;
        dwell = current.equals(token) ? Math.min(Integer.MAX_VALUE - 1, dwell) + 1 : 1;
        token = current;
        for (int axis = 0; axis < vector.length; axis++) {
            if (normalized) {
                if (size == 0) origins[axis] = vector[axis];
                double value = vector[axis] - origins[axis];
                if (size == 300) {
                    double old = coordinates[axis][next] - origins[axis];
                    weightedSums[axis] -= sums[axis] - old;
                    sums[axis] -= old; squares[axis] -= old * old;
                }
                weightedSums[axis] += Math.min(size, 299) * value;
                sums[axis] += value; squares[axis] += value * value;
            }
            coordinates[axis][next] = vector[axis];
        }
        coordinates[vector.length][next] = projection;
        next = (next + 1) % 300; size = Math.min(300, size + 1);
        if (normalized && size == 300) {
            // Refresh one axis per frame to bound roundoff during indefinite sliding.
            int axis = statisticsAxis;
            origins[axis] = coordinates[axis][next];
            sums[axis] = squares[axis] = weightedSums[axis] = 0;
            for (int index = 0; index < size; index++) {
                double value = coordinates[axis][(next + index) % 300] - origins[axis];
                sums[axis] += value; squares[axis] += value * value; weightedSums[axis] += index * value;
            }
            statisticsAxis = (statisticsAxis + 1) % vector.length;
        }
        if (size < 20) return new Result(dwell, false, 0, 0, 0);
        double xx = 0, meanX = (size - 1) / 2.0;
        for (int index = 0; index < size; index++) xx += (index - meanX) * (index - meanX);
        double[] dp = projectionResiduals;
        residuals(coordinates[vector.length], dp, xx);
        double variance = 0, covariance = 0;
        for (int axis = 0; axis < vector.length; axis++) {
            if (normalized) {
                // Centered sufficient statistics give the same linear-detrended variance.
                double xy = weightedSums[axis] - meanX * sums[axis];
                variance += Math.max(0, squares[axis] - sums[axis] * sums[axis] / size - xy * xy / xx) / size;
            } else {
                residuals(coordinates[axis], axisResiduals, xx);
                variance += squareMean(axisResiduals, size);
            }
        }
        if (normalized) variance /= vector.length;
        for (int index = 0; index < size - 1; index++) covariance += dp[index] * dp[index + 1];
        double lag1 = Math.max(-1, Math.min(1, covariance / (size - 1) / Math.max(1e-6, squareMean(dp, size))));
        variances[baselineNext] = variance; correlations[baselineNext] = lag1;
        baselineNext = (baselineNext + 1) % 300; baselineSize = Math.min(300, baselineSize + 1);
        boolean ready = baselineSize >= 10;
        double score = ready ? standardized(variance, variances) + standardized(lag1, correlations) : 0;
        return new Result(dwell, ready, variance, lag1, score);
    }
    private void residuals(double[] values, double[] result, double xx) {
        double mean = 0, meanX = (size - 1) / 2.0, xy = 0;
        int first = (next - size + 300) % 300;
        for (int index = 0; index < size; index++) { result[index] = values[(first + index) % 300]; mean += result[index]; }
        mean /= size;
        for (int index = 0; index < size; index++) {
            double x = index - meanX;
            xy += x * (result[index] - mean);
        }
        double slope = xy / xx, intercept = mean - slope * meanX;
        for (int index = 0; index < size; index++) result[index] -= slope * index + intercept;
    }
    private static double squareMean(double[] values, int count) {
        double total = 0;
        for (int index = 0; index < count; index++) total += values[index] * values[index];
        return total / count;
    }
    private double standardized(double value, double[] history) {
        double mean = 0, squared = 0;
        for (int index = 0; index < baselineSize; index++) mean += history[index];
        mean /= baselineSize;
        for (int index = 0; index < baselineSize; index++) squared += (history[index] - mean) * (history[index] - mean);
        return (value - mean) / Math.max(1e-4, Math.sqrt(squared / (baselineSize - 1)));
    }
    void reset() {
        size = next = baselineSize = baselineNext = dwell = statisticsAxis = 0; token = null;
        java.util.Arrays.fill(sums, 0); java.util.Arrays.fill(squares, 0); java.util.Arrays.fill(weightedSums, 0);
    }
}
