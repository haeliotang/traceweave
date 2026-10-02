package org.traceform.edge;

import java.util.Map;

/** One service-owned fitted snapshot; every query first binds the current history. */
final class SpectrumForecastCache implements AutoCloseable {
    static final class Query {
        final long[] occupancy;
        final boolean hit;
        final double fitMillis, phaseMillis, queryMillis;
        Query(long[] occupancy, boolean hit, double fitMillis, double phaseMillis, double queryMillis) {
            this.occupancy = occupancy; this.hit = hit; this.fitMillis = fitMillis;
            this.phaseMillis = phaseMillis; this.queryMillis = queryMillis;
        }
    }
    private FittedPeriodicSpectrum snapshot;
    static final class CdfQuery {
        final double[] cdf;
        final boolean hit;
        final double fitMillis, phaseMillis, queryMillis;
        CdfQuery(double[] cdf, boolean hit, double fitMillis, double phaseMillis, double queryMillis) {
            this.cdf=cdf;this.hit=hit;this.fitMillis=fitMillis;this.phaseMillis=phaseMillis;this.queryMillis=queryMillis;
        }
    }
    private String key;
    private PeriodicTransitionKernel kernel;
    private String kernelKey;
    private Map<Long, HistoryForecast.ParsedRow> parsedRows;
    private String parsedBinding;
    private boolean closed;
    synchronized void ensureOpen() {
        if (closed) throw new IllegalStateException("forecast cache is closed");
    }
    synchronized PeriodicTransitionKernel kernel(String requestedKey) {
        ensureOpen();
        return requestedKey.equals(kernelKey) ? kernel : null;
    }
    synchronized void rememberKernel(String requestedKey, PeriodicTransitionKernel value) {
        ensureOpen();
        kernel = value; kernelKey = requestedKey;
    }
    synchronized Map<Long, HistoryForecast.ParsedRow> parsedRows(String binding) {
        ensureOpen();
        return binding.equals(parsedBinding) ? parsedRows : null;
    }
    synchronized void rememberParsedRows(String binding, Map<Long, HistoryForecast.ParsedRow> rows) {
        ensureOpen();
        parsedBinding = binding; parsedRows = rows;
    }
    synchronized Query query(String requestedKey, PeriodicTransitionKernel kernel,
            long epochMillis, long stepMillis, int horizon, int runs, int initial, double[] draws) {
        ensureOpen();
        boolean hit = snapshot != null && requestedKey.equals(key);
        double fitMillis = fitIfNeeded(requestedKey,kernel);
        long start = System.nanoTime();
        double[] prior = kernel.priorArray(), phases = kernel.phaseSequence(epochMillis, stepMillis, horizon);
        double phaseMillis = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        long[] occupancy = snapshot.rollouts(prior, phases, runs, initial, draws);
        double queryMillis = (System.nanoTime() - start) / 1e6;
        return new Query(occupancy, hit, fitMillis, phaseMillis, queryMillis);
    }
    private double fitIfNeeded(String requestedKey,PeriodicTransitionKernel kernel) {
        if (snapshot == null || !requestedKey.equals(key)) {
            if (snapshot != null) snapshot.close();
            snapshot = null; key = null;
            long start = System.nanoTime();
            snapshot = kernel.fitSpectrum();
            double fitMillis = (System.nanoTime() - start) / 1e6;
            key = requestedKey;
            return fitMillis;
        }
        return 0;
    }
    synchronized CdfQuery queryCdf(String requestedKey,PeriodicTransitionKernel kernel,long epochMillis,long stepMillis,int horizon) {
        ensureOpen();
        boolean hit=snapshot!=null && requestedKey.equals(key);
        double fitMillis=fitIfNeeded(requestedKey,kernel);
        long start = System.nanoTime();
        double[] prior = kernel.priorArray(), phases = kernel.phaseSequence(epochMillis, stepMillis, horizon);
        double phaseMillis = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        double[] cdf=snapshot.cdf(prior,phases);
        double queryMillis = (System.nanoTime() - start) / 1e6;
        return new CdfQuery(cdf,hit,fitMillis,phaseMillis,queryMillis);
    }
    @Override public synchronized void close() {
        closed = true;
        if (snapshot != null) snapshot.close();
        snapshot = null; key = null;
        kernel = null; kernelKey = null;
        parsedRows = null; parsedBinding = null;
    }
}
