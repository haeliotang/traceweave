from pathlib import Path
import select
import shutil
import subprocess
import tempfile
import unittest


class TestAlignmentQueryLease(unittest.TestCase):
    def test_same_process_cross_process_and_crash_release(self):
        self.exercise_lease("alignment")

    def test_color_operation_cross_process_and_crash_release(self):
        self.exercise_lease("color")

    def exercise_lease(self, purpose):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory, 'LeaseTest.java')
            harness.write_text('''package org.traceform.edge;
import java.io.File;
public final class LeaseTest {
    static AlignmentQueryLease acquire(File root, String purpose) throws Exception {
        return purpose.equals("color") ? AlignmentQueryLease.acquireColorTemperature(root) : AlignmentQueryLease.acquire(root);
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        if (args[1].equals("hold")) {
            try (AlignmentQueryLease lease = acquire(root, args[2])) {
                boolean rejected = false;
                try (AlignmentQueryLease duplicate = acquire(root, args[2])) { }
                catch (SecurityException expected) { rejected = true; }
                if (!rejected) throw new AssertionError("same-process overlap accepted");
                try (AlignmentQueryLease independent = acquire(root, args[2].equals("color") ? "alignment" : "color")) { }
                System.out.println("READY"); System.out.flush();
                System.in.read();
            }
        } else {
            try (AlignmentQueryLease lease = acquire(root, args[2])) { System.out.println("ACQUIRED"); }
            catch (SecurityException expected) { System.out.println("BUSY"); System.exit(3); }
        }
    }
}''')
            subprocess.run(['javac', '--release', '8', '-d', directory,
                str(root / 'android/src/org/traceform/edge/AlignmentQueryLease.java'), str(harness)],
                check=True, capture_output=True)
            base = ['java', '-cp', directory, 'org.traceform.edge.LeaseTest', directory]
            holder = subprocess.Popen(base + ['hold', purpose], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                self.assertTrue(select.select([holder.stdout], [], [], 5)[0], 'holder did not become ready')
                self.assertEqual(holder.stdout.readline(), b'READY\n')
                rejected = subprocess.run(base + ['probe', purpose], capture_output=True, timeout=5)
                self.assertEqual(rejected.returncode, 3, rejected.stderr.decode())
                self.assertEqual(rejected.stdout, b'BUSY\n')
                holder.kill()
                holder.communicate(timeout=5)
                resumed = subprocess.run(base + ['probe', purpose], capture_output=True, timeout=5)
                self.assertEqual(resumed.returncode, 0, resumed.stderr.decode())
                self.assertEqual(resumed.stdout, b'ACQUIRED\n')
                self.assertTrue(Path(directory, 'color_temperature.lock' if purpose == 'color' else 'alignment_query.lock').exists())
            finally:
                if holder.poll() is None:
                    holder.kill()
                    holder.communicate(timeout=5)
