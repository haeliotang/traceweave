from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestPrivateInterruptedSnapshotRecovery(unittest.TestCase):
    def test_lease_bound_cleanup_preserves_legacy_and_symlink_targets(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory, 'RecoveryTest.java')
            harness.write_text('''package org.traceform.edge;
import java.io.File;
import java.nio.file.Files;
public final class RecoveryTest {
    public static void main(String[] args) throws Exception {
        File files = new File(args[0],"files"), cache = new File(args[0],"cache"), other = new File(args[0],"other");
        files.mkdir(); cache.mkdir(); other.mkdir();
        File stale = new File(cache,"private-circuit-approved-123.txt");
        File legacy = new File(cache,"private-circuit-456.txt");
        File keep = new File(other,"keep.txt");
        Files.write(stale.toPath(),new byte[]{1}); Files.write(legacy.toPath(),new byte[]{2});
        Files.write(keep.toPath(),new byte[]{3});
        File link = new File(cache,"private-circuit-approved-789.txt");
        Files.createSymbolicLink(link.toPath(),keep.toPath());
        try(AlignmentQueryLease wrong = AlignmentQueryLease.acquire(other)) {
            boolean rejected=false;
            try { UniformPrivateAlignment.recoverInterruptedSnapshots(cache, files, wrong); }
            catch(SecurityException expected) { rejected=true; }
            if(!rejected || !stale.exists()) throw new AssertionError("mismatched lease removed snapshot");
        }
        try(AlignmentQueryLease lease = AlignmentQueryLease.acquire(files)) {
            boolean rejected=false;
            try(AlignmentQueryLease duplicate = AlignmentQueryLease.acquire(files)) { }
            catch(SecurityException expected) { rejected=true; }
            if(!rejected) throw new AssertionError("active query not exclusive");
            if(UniformPrivateAlignment.recoverInterruptedSnapshots(cache, files, lease)!=1)
                throw new AssertionError("abandoned snapshot not recovered");
            if(stale.exists() || !legacy.exists() || !link.exists() || !keep.exists())
                throw new AssertionError("unrelated or symlink file altered");
            if(UniformPrivateAlignment.recoverInterruptedSnapshots(cache, files, lease)!=0)
                throw new AssertionError("recovery not idempotent");
        }
    }
}''')
            manager = Path(directory, 'ActivityManager.java')
            manager.write_text('package android.app; public class ActivityManager { public static class MemoryInfo { public long availMem; public boolean lowMemory; } public void getMemoryInfo(MemoryInfo memory) {} }')
            context = Path(directory, 'Context.java')
            context.write_text('package android.content; public class Context { public static final String ACTIVITY_SERVICE="activity"; public Object getSystemService(String name) {return null;} public java.io.File getCacheDir(){return null;} public java.io.File getFilesDir(){return null;} }')
            names = ('PrivateParticipant', 'PrivateAlignmentOutput', 'AlignmentQueryLease',
                     'UniformPrivateAlignment', 'OwnerAuthentication', 'ComputationSessions')
            compiled = subprocess.run(['javac', '--release', '8', '-d', directory,
                *[str(root / 'android/src/org/traceform/edge' / (name + '.java')) for name in names],
                str(manager), str(context), str(harness)], capture_output=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr.decode())
            result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.RecoveryTest', directory],
                                    capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
