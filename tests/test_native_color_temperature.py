from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeColorTemperature(unittest.TestCase):
    def test_adjustment_readback_restoration_and_guards(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "ColorTest.java"
            harness.write_text("""package org.traceform.edge;
public final class ColorTest {
    static final class Fake implements ColorTemperatureAdjustment.Control {
        int value = 3000, writes, reads, mode;
        boolean active = true, apply = true, external, interrupt, rejectRestore, failJournal, failSchedule;
        int[] pending;
        public int temperature() { reads++; return value; }
        public boolean active() { reads++; return active; }
        public int autoMode() { reads++; return mode; }
        public boolean setTemperature(int target) { writes++; if (rejectRestore && writes == 2) return false; if (apply) value = target; return true; }
        public void beforeAdjustment(int original, int target) throws Exception {
            if (failJournal) throw new java.io.IOException("journal failed");
            pending = new int[]{original, target};
            if (failSchedule) { value = target; throw new IllegalStateException("schedule failed after external change"); }
        }
        public void afterAdjustment(ColorTemperatureAdjustment.Result result) {
            if (result.restored || result.restorationStatus.equals("external_change_preserved")) pending = null;
        }
        public void pause() throws Exception {
            if (external) value = 2800;
            if (interrupt) throw new InterruptedException("cancelled");
        }
    }
    public static void main(String[] args) throws Exception {
        Fake normal = new Fake();
        ColorTemperatureAdjustment.Result result = ColorTemperatureAdjustment.run(normal, 2900, 686, 4082, true, true);
        if (!result.delivered || !result.restored || result.writes != 2 || normal.value != 3000) throw new AssertionError("temporary restoration");
        Fake unchanged = new Fake(); unchanged.apply = false;
        if (ColorTemperatureAdjustment.run(unchanged, 2900, 686, 4082, true, true).delivered) throw new AssertionError("readback required");
        Fake external = new Fake(); external.external = true;
        result = ColorTemperatureAdjustment.run(external, 2900, 686, 4082, true, true);
        if (!result.delivered || result.restored || external.writes != 1 || external.value != 2800) throw new AssertionError("external change preserved");
        for (boolean enabled : new boolean[]{false, true}) {
            Fake denied = new Fake();
            result = ColorTemperatureAdjustment.run(denied, 2900, 686, 4082, enabled, false);
            if (result.delivered || denied.writes != 0 || denied.reads != 0) throw new AssertionError("permission guard");
        }
        Fake disabled = new Fake();
        result = ColorTemperatureAdjustment.run(disabled, 2900, 686, 4082, false, true);
        if (result.delivered || disabled.writes != 0 || disabled.reads != 0) throw new AssertionError("user disabled");
        Fake automatic = new Fake(); automatic.mode = 1;
        if (ColorTemperatureAdjustment.run(automatic, 2900, 686, 4082, true, true).delivered || automatic.writes != 0) throw new AssertionError("automatic mode");
        Fake off = new Fake(); off.active = false;
        if (ColorTemperatureAdjustment.run(off, 2900, 686, 4082, true, true).delivered || off.writes != 0) throw new AssertionError("night display off");
        for (int target : new int[]{0, 685, 4083}) {
            Fake invalid = new Fake();
            try { ColorTemperatureAdjustment.run(invalid, target, 686, 4082, true, true); throw new AssertionError("invalid target accepted"); }
            catch (IllegalArgumentException expected) { if (invalid.writes != 0) throw new AssertionError("invalid wrote"); }
        }
        Fake failedRestore = new Fake(); failedRestore.rejectRestore = true;
        result = ColorTemperatureAdjustment.run(failedRestore, 2900, 686, 4082, true, true);
        if (!result.delivered || result.restored || failedRestore.value != 2900 || !result.reason.equals("restoration_failed")) throw new AssertionError("failed restoration receipt");
        if (failedRestore.pending == null || failedRestore.pending[0] != 3000 || failedRestore.pending[1] != 2900) throw new AssertionError("failed recovery record lost");
        failedRestore.rejectRestore = false; failedRestore.writes = 0;
        result = ColorTemperatureAdjustment.recover(failedRestore, 3000, 2900, 686, 4082, true);
        if (result.delivered || !result.restored || failedRestore.value != 3000 || result.writes != 1) throw new AssertionError("pending recovery");
        Fake superseded = new Fake(); superseded.value = 2800;
        result = ColorTemperatureAdjustment.recover(superseded, 3000, 2900, 686, 4082, true);
        if (result.delivered || superseded.writes != 0 || !result.restorationStatus.equals("external_change_preserved")) throw new AssertionError("recovery ownership");
        Fake recoveryDenied = new Fake(); recoveryDenied.value = 2900;
        result = ColorTemperatureAdjustment.recover(recoveryDenied, 3000, 2900, 686, 4082, false);
        if (result.delivered || recoveryDenied.writes != 0 || recoveryDenied.reads != 0 || !result.restorationStatus.equals("pending")) throw new AssertionError("recovery permission");
        Fake already = new Fake();
        result = ColorTemperatureAdjustment.recover(already, 3000, 2900, 686, 4082, true);
        if (!result.restored || result.writes != 0 || result.delivered) throw new AssertionError("idempotent recovery");
        Fake failedJournal = new Fake(); failedJournal.failJournal = true;
        try { ColorTemperatureAdjustment.run(failedJournal, 2900, 686, 4082, true, true); throw new AssertionError("journal failure ignored"); }
        catch (java.io.IOException expected) { if (failedJournal.writes != 0 || failedJournal.value != 3000) throw new AssertionError("write before journal"); }
        Fake failedSchedule = new Fake(); failedSchedule.failSchedule = true;
        try { ColorTemperatureAdjustment.run(failedSchedule, 2900, 686, 4082, true, true); throw new AssertionError("schedule failure ignored"); }
        catch (IllegalStateException expected) {
            if (failedSchedule.writes != 0 || failedSchedule.value != 2900 || failedSchedule.pending == null)
                throw new AssertionError("settings changed despite scheduling failure");
        }
        Fake cancelled = new Fake(); cancelled.interrupt = true;
        try { ColorTemperatureAdjustment.run(cancelled, 2900, 686, 4082, true, true); throw new AssertionError("interrupt ignored"); }
        catch (InterruptedException expected) {
            if (cancelled.value != 3000 || cancelled.writes != 2 || !Thread.currentThread().isInterrupted()) throw new AssertionError("interrupt restoration");
            Thread.interrupted();
        }
    }
}""")
            compiled = subprocess.run(["javac", "-d", directory, str(root / "android/src/org/traceform/edge/ColorTemperatureAdjustment.java"), str(harness)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            executed = subprocess.run(["java", "-cp", directory, "org.traceform.edge.ColorTest"], capture_output=True, text=True)
            self.assertEqual(executed.returncode, 0, executed.stderr)
