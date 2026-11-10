// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Single-flight model comparisons; invalidated work never publishes an action. */
final class ShadowComparisonWorker implements AutoCloseable {
    private ExecutorService executor;
    private long generation, lastAttempt;
    private boolean attempted, busy, closed;

    synchronized boolean update(long elapsedMillis, boolean gate, Callable<String> compute, Consumer<String> publish) {
        if (closed) return false;
        if (!gate) { generation++; return false; }
        if (elapsedMillis < 0) throw new IllegalArgumentException("monotonic time required");
        if (busy || (attempted && elapsedMillis - lastAttempt < 900000)) return false;
        if (executor == null) executor = Executors.newSingleThreadExecutor();
        long ticket = generation;
        busy = true; attempted = true; lastAttempt = elapsedMillis;
        executor.submit(() -> {
            try {
                String result = compute.call();
                synchronized (ShadowComparisonWorker.this) {
                    if (!closed && ticket == generation) publish.accept(result);
                }
            } catch (Exception error) {
                java.util.logging.Logger.getLogger("Traceform").log(java.util.logging.Level.WARNING,
                    "shadow comparison failed; no action published", error);
            } finally {
                synchronized (ShadowComparisonWorker.this) { busy = false; }
            }
        });
        return true;
    }
    synchronized void invalidate() { generation++; }
    @Override public synchronized void close() {
        closed = true; generation++;
        if (executor != null) executor.shutdownNow();
    }
}
