package org.traceform.edge;

public final class NativeCore {
    static { System.loadLibrary("traceform"); }
    private NativeCore() {}
    public static native double[] periodicCdf(double[] prior, double[] queryPhases,
        double[] observedPhases, int[] sources, int[] targets, int states);
    static native long fitSpectrum(int states, double[] observedPhases, int[] sources, int[] targets);
    static native double[] spectrumCdf(long handle, double[] prior, double[] queryPhases);
    static native long[] spectrumRollouts(long handle, double[] prior, double[] queryPhases,
        int runs, int initial, double[] draws);
    static native double[] spectrumTimings(long handle);
    static native void closeSpectrum(long handle);
    static native double[] randomDraws(long seed, int count);
    static native long[] counterfactual(double[] cdf, double[] globalPrior, int states,
        int horizon, int runs, int initial, int maxDwell, double[] baselineDraws, double[] scenarioDraws);
    public static native double[] normalize(double[] raw, double[] median, double[] iqr);
    public static native double[] quantize(double[] position, double[] velocity, double[] centers, double[] centerVelocity, int nodes, double beta);
    public static native long[] rollouts(double[] cdfByStep, int states, int horizon, int runs, int initial, double[] draws);
}
