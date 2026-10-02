from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeContextualPolicy(unittest.TestCase):
    def test_exploration_control_learning_and_fatigue_boundaries(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "PolicyTest.java"
            harness.write_text('''package org.traceform.edge;
public final class PolicyTest {
    public static void main(String[] args) {
        long[] counts = {0,10,0,0}, rewards = {0,10,0,0};
        double[] draws = {0,.249999,.25,.287499,.287501,.925001,.962501};
        String[] expected = {"none","none","pulse","pulse","notification","volume_duck","color_temperature"};
        for (int i=0;i<draws.length;i++) {
            ContextualPolicy.Decision d = ContextualPolicy.select(counts,rewards,draws[i]);
            if (!d.action.equals(expected[i])) throw new AssertionError("choice boundary " + i);
            double total=0; for (double p:d.probabilities) total+=p;
            if (Math.abs(total-1)>1e-12 || d.probabilities[0]!=.25 || Math.abs(d.probabilities[2]-.6375)>1e-12)
                throw new AssertionError("logged exploration distribution");
        }
        if (!ContextualPolicy.select(new long[4],new long[4],.3).action.equals("pulse")) throw new AssertionError("prior tie");
        for(int n=0;n<5;n++) if(ContextualPolicy.cooldownMillis(n)!=(900000L<<Math.min(2,n))) throw new AssertionError("fatigue");
        for(double bad:new double[]{-1,1,Double.NaN,Double.POSITIVE_INFINITY}) {
            boolean rejected=false; try{ContextualPolicy.select(counts,rewards,bad);}catch(IllegalArgumentException e){rejected=true;}
            if(!rejected)throw new AssertionError("invalid draw");
        }
        boolean rejected=false; try{ContextualPolicy.select(new long[]{0,0,0,0},new long[]{1,0,0,0},0);}catch(IllegalArgumentException e){rejected=true;}
        if(!rejected)throw new AssertionError("impossible rewards");
    }
}''')
            subprocess.run(["javac", "-d", directory, str(root / "android/src/org/traceform/edge/ContextualPolicy.java"), str(harness)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "org.traceform.edge.PolicyTest"], check=True, capture_output=True)
