// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

/** Bounded Gaussian drift exposure and complete-graph conservative projection. */
final class EmpiricalGraphPotential {
    static final class Result {
        final double[][] centers, drift;
        final double[] energy;
        final double residualRms, conservativeFraction;
        final int pairs;
        Result(double[][] centers, double[][] drift, double[] energy, double residualRms, double fraction, int pairs) {
            this.centers = centers; this.drift = drift; this.energy = energy;
            this.residualRms = residualRms; this.conservativeFraction = fraction; this.pairs = pairs;
        }
    }
    private final double[][] centers, weightedDelta;
    private final double[] peak, exposure;
    private final double variance, mobility;
    private final int dimension;
    private int pairs;
    EmpiricalGraphPotential(double[][] centers, double bandwidth, double mobility) {
        if (centers.length < 2 || centers.length > 16 || centers[0].length < 1 || centers[0].length > 128
                || !Double.isFinite(bandwidth) || bandwidth <= 0 || bandwidth * bandwidth == 0
                || !Double.isFinite(2 * bandwidth * bandwidth) || !Double.isFinite(mobility) || mobility <= 0)
            throw new IllegalArgumentException("bounded centers and positive representable parameters required");
        dimension = centers[0].length; variance = bandwidth * bandwidth; this.mobility = mobility;
        this.centers = new double[centers.length][];
        for (int node = 0; node < centers.length; node++) {
            finiteVector(centers[node]); this.centers[node] = centers[node].clone();
        }
        weightedDelta = new double[centers.length][dimension];
        peak = new double[centers.length]; java.util.Arrays.fill(peak, Double.NEGATIVE_INFINITY);
        exposure = new double[centers.length];
    }
    void add(double[] start, double[] end, double seconds) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("potential fitting cancelled");
        finiteVector(start); finiteVector(end);
        if (pairs >= 172799 || !Double.isFinite(seconds) || seconds <= 0 || seconds > 300)
            throw new IllegalArgumentException("bounded continuous pair exposure required");
        double[] delta = new double[dimension];
        for (int index = 0; index < dimension; index++) {
            delta[index] = end[index] - start[index]; finite(delta[index]);
        }
        double[] nextPeak = new double[centers.length], nextExposure = new double[centers.length];
        double[][] nextDelta = new double[centers.length][dimension];
        for (int node = 0; node < centers.length; node++) {
            double square = 0;
            for (int index = 0; index < dimension; index++) {
                double difference = start[index] - centers[node][index]; square += difference * difference;
            }
            double logWeight = -square / (2 * variance); finite(logWeight);
            nextPeak[node] = Math.max(peak[node], logWeight);
            double scale = Math.exp(peak[node] - nextPeak[node]), weight = Math.exp(logWeight - nextPeak[node]);
            nextExposure[node] = exposure[node] * scale + weight * seconds; finite(nextExposure[node]);
            for (int index = 0; index < dimension; index++) {
                nextDelta[node][index] = weightedDelta[node][index] * scale + weight * delta[index];
                finite(nextDelta[node][index]);
            }
        }
        for (int node = 0; node < centers.length; node++) {
            peak[node] = nextPeak[node]; exposure[node] = nextExposure[node];
            System.arraycopy(nextDelta[node], 0, weightedDelta[node], 0, dimension);
        }
        pairs++;
    }
    Result project() {
        if (pairs < 2) throw new IllegalStateException("at least two continuous training pairs required");
        int states = centers.length;
        double[][] drift = new double[states][dimension], copy = new double[states][];
        for (int node = 0; node < states; node++) {
            copy[node] = centers[node].clone();
            for (int index = 0; index < dimension; index++) {
                drift[node][index] = weightedDelta[node][index] / exposure[node]; finite(drift[node][index]);
            }
        }
        double[] divergence = new double[states], energy = new double[states];
        double[][] edges = new double[states][states];
        for (int first = 0; first < states; first++) for (int second = first + 1; second < states; second++) {
            double integral = 0;
            for (int index = 0; index < dimension; index++)
                integral -= (drift[first][index] + drift[second][index]) * (centers[second][index] - centers[first][index]) / (2 * mobility);
            finite(integral); edges[first][second] = integral;
            divergence[first] -= integral; divergence[second] += integral;
            finite(divergence[first]); finite(divergence[second]);
        }
        for (int node = 0; node < states; node++) {
            energy[node] = (divergence[node] - divergence[0]) / states; finite(energy[node]);
        }
        double total = 0, residual = 0;
        for (int first = 0; first < states; first++) for (int second = first + 1; second < states; second++) {
            double error = edges[first][second] - (energy[second] - energy[first]);
            total += edges[first][second] * edges[first][second]; residual += error * error;
            finite(total); finite(residual);
        }
        return new Result(copy, drift, energy, Math.sqrt(residual / (states * (states - 1) / 2)),
            total == 0 ? Double.NaN : Math.max(0, Math.min(1, 1 - residual / total)), pairs);
    }
    private void finiteVector(double[] values) {
        if (values.length != dimension) throw new IllegalArgumentException("matching potential dimension required");
        for (double value : values) finite(value);
    }
    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("finite representable potential arithmetic required");
    }
}
