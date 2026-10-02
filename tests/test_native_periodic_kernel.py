import datetime
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from traceform.forecasting.continuity import same_stream_segment
from traceform.forecasting.periodic_kernel import PeriodicTransitionKernel


class TestNativePeriodicKernel(unittest.TestCase):
    def test_python_parity_and_continuity_contract(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        records = []
        for day, target in [(5, 1), (10, 2)]:
            for minute in range(20):
                for second, token in [(0, 0), (1, target)]:
                    records.append({"timestamp_utc": f"2026-10-{day:02d}T06:{minute:02d}:{second:02d}Z",
                                    "state_token": token, "model_version": str(day),
                                    "continuous_from_previous": minute != 10})
        epochs = [int(datetime.datetime.fromisoformat(row["timestamp_utc"].replace("Z", "+00:00")).timestamp() * 1000) for row in records]
        counts = {source: {target: 0 for target in range(3)} for source in range(3)}
        eligible = 0
        for index, (first, second) in enumerate(zip(records, records[1:])):
            if same_stream_segment(first, second) and 0 < epochs[index + 1] - epochs[index] <= 1800000:
                counts[first["state_token"]][second["state_token"]] += 1
                eligible += 1
        prior = {source: {target: count / sum(row.values()) for target, count in row.items()} if sum(row.values()) else {source: 1.0}
                 for source, row in counts.items()}
        queries = [datetime.datetime.fromisoformat(text) for text in [
            "2026-10-05T06:00:00+00:00", "2026-10-10T06:00:00+00:00",
            "2026-10-11T23:59:59.999+00:00", "2026-10-12T00:00:00.001+00:00",
            "2026-11-01T05:59:59.999+00:00", "2026-11-01T06:00:00.001+00:00",
            "2026-03-08T06:59:59.999+00:00", "1969-12-31T23:59:59.999+00:00"]]
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "KernelTest.java"
            harness.write_text('''package org.traceform.edge;
public final class KernelTest {
    public static void main(String[] args) {
        long[] epochs = new long[]{EPOCHS};
        int[] tokens = new int[]{TOKENS};
        String[] segments = new String[]{SEGMENTS};
        boolean[] continuous = new boolean[]{CONTINUOUS};
        for (String zone : new String[]{"UTC", "Asia/Kathmandu", "America/New_York", "Australia/Lord_Howe"}) {
            PeriodicTransitionKernel kernel = new PeriodicTransitionKernel(zone, 3, epochs, tokens, segments, continuous, 1800000);
            if (kernel.eligiblePairs != ELIGIBLE) throw new AssertionError("eligible pairs");
            for (long query : new long[]{QUERIES}) for (int source = 0; source < 3; source++) {
                double[] row = kernel.row(source, query);
                for (double value : row) System.out.println(value);
            }
            for (long query : new long[]{QUERIES}) {
                double[] sequence = kernel.phaseSequence(query, 30000, 8);
                for (double value : sequence) System.out.println(value);
            }
            double[] cdf = kernel.cdf(epochs[0], 30000, 4);
            for (double value : cdf) System.out.println(value);
            for (int offset = 0; offset < cdf.length; offset += 3) {
                if (cdf[offset] < 0 || cdf[offset] > cdf[offset + 1] || cdf[offset + 1] > 1 || cdf[offset + 2] != 1)
                    throw new AssertionError("CDF normalization");
            }
        }
        PeriodicTransitionKernel empty = new PeriodicTransitionKernel("UTC", 3, new long[0], new int[0], new String[0], new boolean[0], 1800000);
        if (empty.row(2, 0)[2] != 1) throw new AssertionError("unobserved fallback");
        try { empty.cdf(Long.MAX_VALUE, 1, 2); throw new AssertionError("timestamp overflow accepted"); }
        catch (ArithmeticException expected) {}
    }
}'''.replace("EPOCHS", ",".join(str(value) + "L" for value in epochs))
                .replace("TOKENS", ",".join(str(row["state_token"]) for row in records))
                .replace("SEGMENTS", ",".join('"' + row["model_version"] + '"' for row in records))
                .replace("CONTINUOUS", ",".join(str(row["continuous_from_previous"]).lower() for row in records))
                .replace("ELIGIBLE", str(eligible))
                .replace("QUERIES", ",".join(str(int(query.timestamp() * 1000)) + "L" for query in queries)))
            subprocess.run(["javac", "--release", "8", "-d", directory, str(root / "android/src/org/traceform/edge/PeriodicTransitionKernel.java"), str(root / "android/src/org/traceform/edge/NativeCore.java"), str(root / "android/src/org/traceform/edge/FittedPeriodicSpectrum.java"), str(harness)], check=True, capture_output=True)
            output = subprocess.run(["java", "-cp", directory, "org.traceform.edge.KernelTest"], check=True, capture_output=True, text=True)
        actual = [float(line) for line in output.stdout.splitlines()]
        expected = []
        for zone in ["UTC", "Asia/Kathmandu", "America/New_York", "Australia/Lord_Howe"]:
            kernel = PeriodicTransitionKernel(zone).fit(records, prior)
            for query in queries:
                for source in range(3):
                    row = kernel.row(source, query)
                    expected.extend(row.get(target, 0) for target in range(3))
            for query in queries:
                for step in range(8):
                    expected.extend(kernel.phases(query + datetime.timedelta(seconds=step * 30)))
            for step in range(4):
                instant = datetime.datetime.fromtimestamp(epochs[0] / 1000, datetime.timezone.utc) + datetime.timedelta(seconds=step * 30)
                for source in range(3):
                    row = kernel.row(source, instant)
                    cumulative = 0
                    for target in range(3):
                        cumulative += row.get(target, 0)
                        expected.append(1.0 if target == 2 else cumulative)
        self.assertEqual(len(actual), len(expected))
        for first, second in zip(actual, expected):
            self.assertAlmostEqual(first, second, places=12)
