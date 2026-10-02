package org.traceform.edge;

/** Six-epoch warm-start SOM updates; caller supplies source-bound sample order. */
final class IncrementalManifoldFit {
    private final double[] centers, velocities;
    private final int[][] grid;
    private final double beta, initialSigma;
    private final int totalSteps;
    private int step;

    IncrementalManifoldFit(double[] centers, double[] velocities, int[][] coordinates,
            int trainingRecords, double beta) {
        if (trainingRecords < 1 || trainingRecords > 172800 || coordinates.length < 1 || coordinates.length > 16
                || centers.length == 0 || centers.length != velocities.length
                || centers.length % coordinates.length != 0 || centers.length / coordinates.length > 128
                || !Double.isFinite(beta)) throw new IllegalArgumentException("bounded warm-start fit required");
        this.centers = centers.clone(); this.velocities = velocities.clone(); this.beta = beta;
        grid = new int[coordinates.length][2];
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int node = 0; node < grid.length; node++) {
            if (coordinates[node] == null || coordinates[node].length != 2) throw new IllegalArgumentException("two grid coordinates required");
            grid[node] = coordinates[node].clone();
            minX = Math.min(minX, grid[node][0]); maxX = Math.max(maxX, grid[node][0]);
            minY = Math.min(minY, grid[node][1]); maxY = Math.max(maxY, grid[node][1]);
        }
        for (double value : this.centers) if (!Double.isFinite(value)) throw new IllegalArgumentException("finite warm-start centers required");
        for (double value : this.velocities) if (!Double.isFinite(value)) throw new IllegalArgumentException("finite warm-start velocities required");
        initialSigma = Math.max((long) maxX - minX + 1, (long) maxY - minY + 1) / 2.0;
        totalSteps = trainingRecords * 6;
    }
    void update(double[] position, double[] velocity) {
        if (step >= totalSteps) throw new IllegalStateException("fit is complete");
        int dimension = centers.length / grid.length;
        if (position.length != dimension || velocity.length != dimension) throw new IllegalArgumentException("matching coordinates required");
        TopologyStrain.Result nearest = TopologyStrain.assess(position, velocity, centers, velocities, grid, beta);
        double fraction = step / (double) totalSteps, learningRate = 0.1 * (1 - fraction);
        double sigma = Math.max(0.5, initialSigma * (1 - fraction));
        for (int node = 0; node < grid.length; node++) {
            double dx = (double) grid[node][0] - grid[nearest.first][0], dy = (double) grid[node][1] - grid[nearest.first][1];
            double weight = learningRate * Math.exp(-(dx * dx + dy * dy) / Math.max(1e-6, 2 * sigma * sigma));
            if (weight > 1e-4) for (int index = 0; index < dimension; index++) {
                int offset = node * dimension + index;
                centers[offset] += weight * (position[index] - centers[offset]);
                velocities[offset] += weight * (velocity[index] - velocities[offset]);
            }
        }
        step++;
    }
    boolean complete() { return step == totalSteps; }
    double[] centers() { return centers.clone(); }
    double[] velocities() { return velocities.clone(); }
}
