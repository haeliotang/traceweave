package org.traceform.edge;

/** Bounded topology-coordinate diagnostic; a warning does not authorize actuation. */
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
    private final double[][] coordinates = new double[3][300];
    private final double[] variances = new double[300], correlations = new double[300];
    private int size, next, baselineSize, baselineNext, dwell;
    private String token;

    Result update(String current, double u, double v, boolean continuous) {
        double projection = Math.sqrt(u * u + v * v);
        if (current == null || current.isEmpty() || !Double.isFinite(u) || !Double.isFinite(v)
                || !Double.isFinite(projection)) throw new IllegalArgumentException("finite coordinates and token required");
        if (!continuous) reset();
        dwell = current.equals(token) ? Math.min(Integer.MAX_VALUE - 1, dwell) + 1 : 1;
        token = current;
        coordinates[0][next] = u; coordinates[1][next] = v; coordinates[2][next] = projection;
        next = (next + 1) % 300; size = Math.min(300, size + 1);
        if (size < 20) return new Result(dwell, false, 0, 0, 0);
        double[] du = residuals(coordinates[0]), dv = residuals(coordinates[1]), dp = residuals(coordinates[2]);
        double variance = squareMean(du) + squareMean(dv), covariance = 0;
        for (int index = 0; index < size - 1; index++) covariance += dp[index] * dp[index + 1];
        double lag1 = Math.max(-1, Math.min(1, covariance / (size - 1) / Math.max(1e-6, squareMean(dp))));
        variances[baselineNext] = variance; correlations[baselineNext] = lag1;
        baselineNext = (baselineNext + 1) % 300; baselineSize = Math.min(300, baselineSize + 1);
        boolean ready = baselineSize >= 10;
        double score = ready ? standardized(variance, variances) + standardized(lag1, correlations) : 0;
        return new Result(dwell, ready, variance, lag1, score);
    }
    private double[] residuals(double[] values) {
        double mean = 0, meanX = (size - 1) / 2.0, xx = 0, xy = 0;
        double[] result = new double[size];
        int first = (next - size + 300) % 300;
        for (int index = 0; index < size; index++) { result[index] = values[(first + index) % 300]; mean += result[index]; }
        mean /= size;
        for (int index = 0; index < size; index++) {
            double x = index - meanX;
            xx += x * x; xy += x * (result[index] - mean);
        }
        double slope = xy / xx, intercept = mean - slope * meanX;
        for (int index = 0; index < size; index++) result[index] -= slope * index + intercept;
        return result;
    }
    private static double squareMean(double[] values) {
        double total = 0;
        for (double value : values) total += value * value;
        return total / values.length;
    }
    private double standardized(double value, double[] history) {
        double mean = 0, squared = 0;
        for (int index = 0; index < baselineSize; index++) mean += history[index];
        mean /= baselineSize;
        for (int index = 0; index < baselineSize; index++) squared += (history[index] - mean) * (history[index] - mean);
        return (value - mean) / Math.max(1e-4, Math.sqrt(squared / (baselineSize - 1)));
    }
    void reset() { size = next = baselineSize = baselineNext = dwell = 0; token = null; }
}
