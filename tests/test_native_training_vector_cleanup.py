from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeTrainingVectorCleanup(unittest.TestCase):
    def test_process_exit_orphan_is_reclaimed_and_live_spool_is_preserved(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        harness = '''package org.traceform.edge;
public final class CleanupTest {
    public static void main(String[] args) throws Exception {
        java.io.File directory=new java.io.File(args[0]); String digest=String.format("%064d",1);
        if(args.length>1) {
            TrainingVectors orphan=new TrainingVectors(directory,1,1,digest);
            orphan.append(1000,new double[]{1},new double[]{0});orphan.seal();
            System.out.println(orphan.path.getAbsolutePath());System.out.flush();
            if(args[1].equals("live")) System.in.read();
            Runtime.getRuntime().halt(0);
        }
        Process child=new ProcessBuilder("java","-cp",System.getProperty("java.class.path"),"org.traceform.edge.CleanupTest",args[0],"crash").start();
        String path=new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream())).readLine();
        if(child.waitFor()!=0 || path==null) throw new AssertionError("orphan process did not exit");
        java.io.File orphan=new java.io.File(path);
        if(!orphan.exists()) throw new AssertionError("orphan fixture missing");
        try(TrainingVectors first=new TrainingVectors(directory,1,1,digest)) {
            if(orphan.exists()) throw new AssertionError("abandoned spool retained after new fit starts");
            first.append(1000,new double[]{2},new double[]{3});first.seal();
            try(TrainingVectors second=new TrainingVectors(directory,1,1,digest)) {
                if(!first.path.exists() || first.read(0,1000).position[0]!=2) throw new AssertionError("active spool removed");
            }
        }
        Process live=new ProcessBuilder("java","-cp",System.getProperty("java.class.path"),"org.traceform.edge.CleanupTest",args[0],"live").start();
        try {
            String activePath=new java.io.BufferedReader(new java.io.InputStreamReader(live.getInputStream())).readLine();
            if(activePath==null || !live.isAlive()) throw new AssertionError("live owner missing");
            try(TrainingVectors other=new TrainingVectors(directory,1,1,digest)) {
                if(!new java.io.File(activePath).exists()) throw new AssertionError("other process active spool removed");
            }
            live.getOutputStream().write(1);live.getOutputStream().flush();
            if(live.waitFor()!=0) throw new AssertionError("live owner did not exit");
            try(TrainingVectors next=new TrainingVectors(directory,1,1,digest)) {
                if(new java.io.File(activePath).exists()) throw new AssertionError("exited owner spool not reclaimed");
            }
        } finally { if(live.isAlive()) {live.destroyForcibly();live.waitFor();} }
    }
}'''
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'CleanupTest.java'
            source.write_text(harness)
            subprocess.run(['javac', '-d', directory, str(root / 'android/src/org/traceform/edge/TrainingVectors.java'), str(source)], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.CleanupTest', directory], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
