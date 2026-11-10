# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeShadowComparison(unittest.TestCase):
    def test_single_flight_cooldown_and_invalidated_publication(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "ShadowTest.java"
            harness.write_text('''package com.inceptless.traceweave.edge;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
public final class ShadowTest {
    public static void main(String[] args) throws Exception {
        ShadowComparisonWorker worker = new ShadowComparisonWorker();
        AtomicInteger published = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        if (worker.update(0, false, () -> "bad", x -> published.incrementAndGet())) throw new AssertionError("gate");
        if (!worker.update(0, true, () -> { entered.countDown(); release.await(); return "old"; }, x -> published.incrementAndGet())) throw new AssertionError("start");
        if (!entered.await(5,TimeUnit.SECONDS)) throw new AssertionError("worker start");
        if (worker.update(900000,true, () -> "overlap",x -> published.incrementAndGet())) throw new AssertionError("single flight");
        worker.invalidate(); release.countDown();
        boolean accepted = false;
        for (int i=0;i<500 && !accepted;i++) {
            accepted = worker.update(900000,true, () -> "new", x -> { published.incrementAndGet(); finished.countDown(); });
            if (!accepted) Thread.sleep(10);
        }
        if (!accepted || !finished.await(5,TimeUnit.SECONDS) || published.get()!=1) throw new AssertionError("stale publication");
        if (worker.update(1799999,true, () -> "early",x -> published.incrementAndGet())) throw new AssertionError("cooldown");
        worker.close();
        if (worker.update(1800000,true, () -> "closed",x -> published.incrementAndGet())) throw new AssertionError("closed");
        ShadowComparisonWorker stopped = new ShadowComparisonWorker();
        CountDownLatch running = new CountDownLatch(1), blocked = new CountDownLatch(1);
        stopped.update(0,true, () -> { running.countDown(); blocked.await(); return "stopped"; },x -> published.incrementAndGet());
        if (!running.await(5,TimeUnit.SECONDS)) throw new AssertionError("stop start");
        stopped.close(); blocked.countDown();
        Thread.sleep(50);
        if (published.get()!=1) throw new AssertionError("publication after close");
    }
}''')
            subprocess.run(["javac", "-d", directory, str(root / "android/src/com/inceptless/traceweave/edge/ShadowComparisonWorker.java"), str(harness)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "com.inceptless.traceweave.edge.ShadowTest"], check=True, capture_output=True, timeout=20)
