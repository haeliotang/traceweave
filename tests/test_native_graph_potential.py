from pathlib import Path
import math
import random
import shutil
import subprocess
import tempfile
import unittest

from traceform.dynamics.continuous import ContinuousDynamics


class TestNativeGraphPotential(unittest.TestCase):
    def test_gaussian_drift_and_graph_projection_match_python(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        rng = random.Random(42)
        inputs, expected = [], []
        for dimension in (1, 3, 128):
            for origin in (0, 1000):
                centers = [[origin + rng.uniform(-2, 2) for _ in range(dimension)] for _ in range(4)]
                pairs = [([rng.uniform(-1, 1) for _ in range(dimension)],
                          [rng.uniform(-1, 1) for _ in range(dimension)], rng.uniform(0.3, 3)) for _ in range(12)]
                drifts = []
                for center in centers:
                    logs = [-sum((left - right) ** 2 for left, right in zip(center, start)) / 2 for start, _, _ in pairs]
                    weights = [math.exp(value - max(logs)) for value in logs]
                    exposure = sum(weight * seconds for weight, (_, _, seconds) in zip(weights, pairs))
                    drifts.append([sum(weight * (end[index] - start[index]) for weight, (start, end, _) in zip(weights, pairs)) / exposure
                                   for index in range(dimension)])
                model = ContinuousDynamics()
                model.dimension, model.model_version = dimension, 'fixture'
                model.anchors = [{'center': center, 'drift': drift} for center, drift in zip(centers, drifts)]
                potential = model.infer_potential()
                expected.append([*[value for drift in drifts for value in drift], *potential['relative_energy'],
                                 potential['edge_residual_rms'], potential['conservative_fraction'], 12])
                inputs.append(' '.join(map(str, [dimension, *[value for center in centers for value in center],
                                                  *[value for start, end, seconds in pairs for value in [*start, *end, seconds]]])))
        harness = '''package org.traceform.edge;
public final class PotentialTest {
    public static void main(String[] args) throws Exception {
        java.util.Scanner input = new java.util.Scanner(System.in).useLocale(java.util.Locale.ROOT);
        while(input.hasNext()) {
            int d=input.nextInt(); double[][] centers=new double[4][d];
            for(int n=0;n<4;n++) for(int i=0;i<d;i++) centers[n][i]=input.nextDouble();
            EmpiricalGraphPotential fit=new EmpiricalGraphPotential(centers,1,1);
            centers[0][0]=999999;
            for(int sample=0;sample<12;sample++) {
                double[] start=new double[d],end=new double[d];
                for(int i=0;i<d;i++) start[i]=input.nextDouble();
                for(int i=0;i<d;i++) end[i]=input.nextDouble();
                fit.add(start,end,input.nextDouble());
            }
            EmpiricalGraphPotential.Result result=fit.project();
            for(double[] drift:result.drift) for(double value:drift) System.out.print(value+" ");
            for(double value:result.energy) System.out.print(value+" ");
            System.out.println(result.residualRms+" "+result.conservativeFraction+" "+result.pairs);
            double[] invalid=new double[d]; invalid[0]=Double.NaN;
            boolean rejected=false;
            try { fit.add(invalid,new double[d],1); } catch(IllegalArgumentException expected) { rejected=true; }
            if(!rejected || fit.project().pairs!=12) throw new AssertionError("invalid sample changed fit");
            Thread.currentThread().interrupt(); rejected=false;
            try { fit.add(new double[d],new double[d],1); } catch(InterruptedException expected) { rejected=true; }
            Thread.interrupted();
            if(!rejected || fit.project().pairs!=12) throw new AssertionError("cancellation changed fit");
            result.energy[0]=999;
            if(fit.project().energy[0]!=0) throw new AssertionError("mutable projection alias");
        }
        EmpiricalGraphPotential zero=new EmpiricalGraphPotential(new double[][]{{0},{1}},1,1);
        zero.add(new double[]{0},new double[]{0},1); zero.add(new double[]{1},new double[]{1},1);
        if(!Double.isNaN(zero.project().conservativeFraction) || zero.project().residualRms!=0) throw new AssertionError("zero drift fraction must be undefined");
    }
}'''
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'PotentialTest.java'
            source.write_text(harness)
            subprocess.run(['javac', '-d', directory, str(root / 'android/src/org/traceform/edge/EmpiricalGraphPotential.java'), str(source)], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.PotentialTest'], input='\n'.join(inputs), text=True, check=True, capture_output=True)
        self.assertEqual(len(result.stdout.splitlines()), len(expected))
        for line, reference in zip(result.stdout.splitlines(), expected):
            actual = list(map(float, line.split()))
            self.assertEqual(len(actual), len(reference))
            for value, target in zip(actual, reference):
                self.assertTrue(math.isclose(value, target, rel_tol=1e-9, abs_tol=1e-10), (value, target))
