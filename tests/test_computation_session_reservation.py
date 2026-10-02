from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestComputationSessionReservation(unittest.TestCase):
    def test_restart_replay_bounds_and_required_lease(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            harness = base / 'ReservationTest.java'
            harness.write_text('''package org.traceform.edge;
import java.io.File;
import java.nio.file.Files;
public class ReservationTest {
    static void rejected(File root,byte[] session,AlignmentQueryLease lease) throws Exception {
        try { ComputationSessions.reserve(root,session,lease); }
        catch (SecurityException expected) { return; }
        throw new AssertionError("bad reservation accepted");
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]), ledger = new File(root,"computation_sessions.bin");
        byte[] session = new byte[32];session[0]=7;
        if (args[1].equals("first")) {
            rejected(root,session,null);
            try (AlignmentQueryLease color=AlignmentQueryLease.acquireColorTemperature(root)) { rejected(root,session,color); }
            if (ledger.exists()) throw new AssertionError("unlocked reservation wrote ledger");
            try (AlignmentQueryLease lease=AlignmentQueryLease.acquire(root)) {
                rejected(root,new byte[31],lease);
                File other=new File(root,"other");other.mkdir();rejected(other,session,lease);
                ComputationSessions.reserve(root,session,lease);
                if (ledger.length()!=32) throw new AssertionError("reservation missing");
                Runtime.getRuntime().halt(0);
            }
            if (ledger.length()!=32) throw new AssertionError("reservation missing");
            return;
        }
        try (AlignmentQueryLease lease=AlignmentQueryLease.acquire(root)) {
            byte[] before=Files.readAllBytes(ledger.toPath());
            rejected(root,session,lease);
            if (!java.util.Arrays.equals(before,Files.readAllBytes(ledger.toPath()))) throw new AssertionError("replay changed ledger");
            session[0]=8;ComputationSessions.reserve(root,session,lease);
            Files.write(ledger.toPath(),new byte[33]);rejected(root,session,lease);
            Files.write(ledger.toPath(),new byte[131072]);rejected(root,session,lease);
            Files.write(ledger.toPath(),new byte[131073]);rejected(root,session,lease);
        }
        AlignmentQueryLease closed=AlignmentQueryLease.acquire(root);closed.close();rejected(root,session,closed);
    }
}''')
            compiled = subprocess.run(['javac', '--release', '8', '-d', directory,
                *[str(root / 'android/src/org/traceform/edge' / (name + '.java')) for name in
                  ['AlignmentQueryLease', 'OwnerAuthentication', 'ComputationSessions']], str(harness)], capture_output=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr.decode())
            for phase in ['first', 'restart']:
                result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.ReservationTest', directory, phase],
                                        capture_output=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr.decode())
