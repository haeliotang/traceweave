// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

/** Owned native fitted snapshot. Caller binds eligible history, model and timezone. */
public final class FittedPeriodicSpectrum implements AutoCloseable {
    private long handle;
    public FittedPeriodicSpectrum(int states, double[] observedPhases, int[] sources, int[] targets) {
        handle = NativeCore.fitSpectrum(states, observedPhases, sources, targets);
    }
    public synchronized double[] cdf(double[] prior, double[] queryPhases) {
        if (handle == 0) throw new IllegalStateException("spectrum is closed");
        return NativeCore.spectrumCdf(handle, prior, queryPhases);
    }
    public synchronized long[] rollouts(double[] prior, double[] queryPhases,
            int runs, int initial, double[] draws) {
        if (handle == 0) throw new IllegalStateException("spectrum is closed");
        return NativeCore.spectrumRollouts(handle, prior, queryPhases, runs, initial, draws);
    }
    synchronized double[] timings() {
        if (handle == 0) throw new IllegalStateException("spectrum is closed");
        return NativeCore.spectrumTimings(handle);
    }
    @Override public synchronized void close() {
        if (handle != 0) { NativeCore.closeSpectrum(handle); handle = 0; }
    }
}
