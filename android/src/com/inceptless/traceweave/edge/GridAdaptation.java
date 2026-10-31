package com.inceptless.traceweave.edge;

/** Strictly decreasing training nearest-pair nonadjacency via coordinate swaps. */
final class GridAdaptation {
    static final class Result {
        final int[][] grid, swaps;
        final long before, after;
        Result(int[][] grid, int[][] swaps, long before, long after) {
            this.grid = grid; this.swaps = swaps; this.before = before; this.after = after;
        }
    }
    static Result adapt(String[] tokens, int[][] coordinates, long[][] pairs) throws InterruptedException {
        int states = tokens.length;
        if (states < 1 || states > 16 || coordinates.length != states || pairs.length != states)
            throw new IllegalArgumentException("bounded matching topology required");
        int[][] grid = new int[states][2];
        Integer[] order = new Integer[states];
        java.util.Set<String> names = new java.util.HashSet<>(), occupied = new java.util.HashSet<>();
        long count = 0;
        for (int node = 0; node < states; node++) {
            if (tokens[node] == null || tokens[node].isEmpty() || !names.add(tokens[node])
                    || coordinates[node] == null || coordinates[node].length != 2 || pairs[node].length != states)
                throw new IllegalArgumentException("unique tokens and matching coordinates required");
            grid[node] = coordinates[node].clone(); order[node] = node;
            if (!occupied.add(grid[node][0] + "," + grid[node][1])) throw new IllegalArgumentException("unique grid coordinates required");
            for (int other = 0; other < states; other++) {
                long value = pairs[node][other];
                if (value < 0 || value > 172800 || other <= node && value != 0)
                    throw new IllegalArgumentException("upper-triangle bounded pair counts required");
                count += value;
            }
        }
        if (count > 172800) throw new IllegalArgumentException("pair count exceeds retained training bound");
        java.util.Arrays.sort(order, java.util.Comparator.comparing(index -> tokens[index]));
        long before = loss(grid, pairs), current = before;
        java.util.List<int[]> swaps = new java.util.ArrayList<>();
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("grid adaptation cancelled");
            long best = current;
            int left = -1, right = -1;
            for (int index = 0; index < states; index++) for (int other = index + 1; other < states; other++) {
                int first = order[index], second = order[other];
                swap(grid, first, second);
                long proposal = loss(grid, pairs);
                swap(grid, first, second);
                if (proposal < best) { best = proposal; left = first; right = second; }
            }
            if (left < 0) break;
            swap(grid, left, right); swaps.add(new int[]{left, right}); current = best;
        }
        return new Result(grid, swaps.toArray(new int[0][]), before, current);
    }
    private static void swap(int[][] grid, int first, int second) {
        int[] coordinate = grid[first]; grid[first] = grid[second]; grid[second] = coordinate;
    }
    private static long loss(int[][] grid, long[][] pairs) {
        long total = 0;
        for (int first = 0; first < grid.length; first++) for (int second = first + 1; second < grid.length; second++)
            if (Math.abs((long) grid[first][0] - grid[second][0]) + Math.abs((long) grid[first][1] - grid[second][1]) > 1)
                total += pairs[first][second];
        return total;
    }
}
