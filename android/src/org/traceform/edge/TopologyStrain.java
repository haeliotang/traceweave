package org.traceform.edge;

/** Nearest-two energy/grid diagnostic using the established kinetic distance. */
final class TopologyStrain {
    static final class Result {
        final int first, second;
        final double energy;
        final boolean nonadjacent;
        Result(int first, int second, double energy, boolean nonadjacent) {
            this.first = first; this.second = second; this.energy = energy; this.nonadjacent = nonadjacent;
        }
    }
    static Result assess(double[] position, double[] velocity, double[] centers,
            double[] centerVelocity, int[][] grid, double beta) {
        int dimension = position.length, states = grid.length;
        if (dimension < 1 || dimension > 128 || velocity.length != dimension || states < 1 || states > 16
                || centers.length != states * dimension || centerVelocity.length != centers.length || !Double.isFinite(beta))
            throw new IllegalArgumentException("bounded topology dimensions required");
        double speedSquared = 0;
        for (int index = 0; index < dimension; index++) {
            if (!Double.isFinite(position[index]) || !Double.isFinite(velocity[index])) throw new IllegalArgumentException("finite coordinates required");
            speedSquared += velocity[index] * velocity[index];
        }
        double speed = Math.sqrt(speedSquared), best = Double.POSITIVE_INFINITY, runner = Double.POSITIVE_INFINITY;
        int first = -1, second = -1;
        for (int node = 0; node < states; node++) {
            if (grid[node] == null || grid[node].length != 2) throw new IllegalArgumentException("two grid coordinates required");
            double distance = 0, kinetic = 0, centerSpeedSquared = 0, dot = 0;
            for (int index = 0; index < dimension; index++) {
                int offset = node * dimension + index;
                if (!Double.isFinite(centers[offset]) || !Double.isFinite(centerVelocity[offset])) throw new IllegalArgumentException("finite centers required");
                double delta = position[index] - centers[offset], velocityDelta = velocity[index] - centerVelocity[offset];
                distance += delta * delta; kinetic += velocityDelta * velocityDelta;
                centerSpeedSquared += centerVelocity[offset] * centerVelocity[offset];
                dot += velocity[index] * centerVelocity[offset];
            }
            double centerSpeed = Math.sqrt(centerSpeedSquared);
            if (speed >= 1e-4 && centerSpeed >= 1e-4) {
                double cosine = Math.max(-1, Math.min(1, dot / (speed * centerSpeed)));
                kinetic = (1 - cosine) * speed + 0.1 * Math.abs(speed - centerSpeed);
            }
            double energy = distance + beta * kinetic;
            if (!Double.isFinite(energy) || !Double.isFinite(speed)) throw new IllegalArgumentException("representable energy required");
            if (energy < best) { runner = best; second = first; best = energy; first = node; }
            else if (energy < runner) { runner = energy; second = node; }
        }
        boolean nonadjacent = second >= 0 && Math.abs((long) grid[first][0] - grid[second][0])
            + Math.abs((long) grid[first][1] - grid[second][1]) > 1;
        return new Result(first, second, best, nonadjacent);
    }
}
