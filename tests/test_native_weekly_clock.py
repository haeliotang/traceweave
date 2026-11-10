# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeWeeklyClock(unittest.TestCase):
    def test_local_windows_dst_and_completed_week(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "WeeklyClockTest.java"
            harness.write_text('''package com.inceptless.traceweave.edge;
import java.time.*;
public final class WeeklyClockTest {
    static long ms(String value) { return Instant.parse(value).toEpochMilli(); }
    static void check(String now, String zone, String completed, String key, String start, String end) {
        WeeklyMaintenanceClock.Window window = WeeklyMaintenanceClock.next(ms(now), ZoneId.of(zone), completed);
        if (!window.key.equals(key) || window.startMillis != ms(start) || window.endMillis != ms(end))
            throw new AssertionError("window mismatch " + zone + " " + now);
        if (!window.contains(window.startMillis) || window.contains(window.endMillis))
            throw new AssertionError("half-open window");
    }
    public static void main(String[] args) {
        check("2026-10-02T00:00:00Z", "Asia/Kathmandu", null, "2026-10-04", "2026-10-03T20:15:00Z", "2026-10-04T00:15:00Z");
        check("2026-10-04T03:00:00Z", "UTC", null, "2026-10-04", "2026-10-04T02:00:00Z", "2026-10-04T06:00:00Z");
        check("2026-10-04T06:00:00Z", "UTC", null, "2026-10-11", "2026-10-11T02:00:00Z", "2026-10-11T06:00:00Z");
        check("2026-10-04T03:00:00Z", "UTC", "2026-10-04", "2026-10-11", "2026-10-11T02:00:00Z", "2026-10-11T06:00:00Z");
        check("2026-03-08T06:00:00Z", "America/New_York", null, "2026-03-08", "2026-03-08T07:00:00Z", "2026-03-08T10:00:00Z");
        check("2026-11-01T04:00:00Z", "America/New_York", null, "2026-11-01", "2026-11-01T07:00:00Z", "2026-11-01T11:00:00Z");
        check("2026-12-31T00:00:00Z", "UTC", null, "2027-01-03", "2027-01-03T02:00:00Z", "2027-01-03T06:00:00Z");
    }
}''')
            subprocess.run(["javac", "-d", directory, str(root / "android/src/com/inceptless/traceweave/edge/WeeklyMaintenanceClock.java"), str(harness)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "com.inceptless.traceweave.edge.WeeklyClockTest"], check=True, capture_output=True)
