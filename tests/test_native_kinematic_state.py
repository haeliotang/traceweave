from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeKinematicState(unittest.TestCase):
    def test_missing_coverage_change_resets_velocity(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "KinematicTest.java"
            harness.write_text('''package org.traceform.edge;
public final class KinematicTest {
    public static void main(String[] args) {
        KinematicState state = new KinematicState();
        if (state.update(new double[]{0}, new boolean[]{false}, 1000000000L, 0.5).continuous) throw new AssertionError("first frame");
        KinematicState.Result changed = state.update(new double[]{10}, new boolean[]{true}, 2000000000L, 0.5);
        if (changed.continuous || changed.velocity[0] != 0) throw new AssertionError("imputation jump");
        KinematicState.Result valid = state.update(new double[]{12}, new boolean[]{true}, 3000000000L, 0.5);
        if (!valid.continuous || valid.velocity[0] != 2) throw new AssertionError("first measured derivative");
        if (state.update(new double[]{16}, new boolean[]{true}, 4000000000L, 0.5).velocity[0] != 3) throw new AssertionError("smoothing");
        if (state.update(new double[]{20}, new boolean[]{true}, 4000000000L, 0.5).continuous) throw new AssertionError("zero interval");
        if (state.update(new double[]{21}, new boolean[]{true}, 305000000000L, 0.5).continuous) throw new AssertionError("gap");
        state.reset();
        state.update(new double[]{0}, new boolean[]{true}, 1000000000L, 0.5, 1000);
        if (state.update(new double[]{1}, new boolean[]{true}, 2000000000L, 0.5, 5000).continuous) throw new AssertionError("wall clock jump");
    }
}''')
            subprocess.run(["javac", "-d", directory, str(root / "android/src/org/traceform/edge/KinematicState.java"), str(harness)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "org.traceform.edge.KinematicTest"], check=True, capture_output=True)
