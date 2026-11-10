# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeWarmBucket(unittest.TestCase):
    def test_bucket_breaks_and_missing_intervals(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "WarmTest.java"
            harness.write_text('''package com.inceptless.traceweave.edge;
public final class WarmTest {
    public static void main(String[] args) {
        WarmBucket first = WarmBucket.next(1000, true, null);
        WarmBucket same = WarmBucket.next(2000, true, first);
        if (same.continuous || same.frames != 2 || same.bucket != 0) throw new AssertionError("first bucket boundary");
        WarmBucket next = WarmBucket.next(31000, true, same);
        if (!next.continuous) throw new AssertionError("adjacent bucket");
        WarmBucket broken = WarmBucket.next(32000, false, next);
        if (WarmBucket.next(33000, true, broken).continuous) throw new AssertionError("lost internal break");
        WarmBucket gap = WarmBucket.next(121000, true, broken);
        if (gap.continuous || gap.missingBefore != 2) throw new AssertionError("missing buckets");
        if (WarmBucket.next(2000, true, gap).continuous) throw new AssertionError("clock reversal");
    }
}''')
            subprocess.run(["javac", "-d", directory, str(root / "android/src/com/inceptless/traceweave/edge/WarmBucket.java"), str(harness)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "com.inceptless.traceweave.edge.WarmTest"], check=True, capture_output=True)
