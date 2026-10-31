from pathlib import Path
import random
import shutil
import subprocess
import tempfile
import unittest

from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode


class TestNativeIncrementalFit(unittest.TestCase):
    def test_shared_order_six_epoch_updates_match_python(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        rng = random.Random(42)
        inputs, expected = [], []
        for dimension in (1, 128):
            model = DynamicalEnergyManifold(grid_height=2, grid_width=2)
            model.nodes = [ManifoldNode(i, (i // 2, i % 2), str(i),
                                       [rng.uniform(-2, 2) for _ in range(dimension)],
                                       [rng.uniform(-1, 1) for _ in range(dimension)]) for i in range(4)]
            samples = [([rng.uniform(-1, 1) for _ in range(dimension)],
                        [rng.uniform(-0.5, 0.5) for _ in range(dimension)]) for _ in range(12)]
            initial = [value for node in model.nodes for value in node.center_x + node.center_v]
            ordered = []
            for epoch in range(6):
                indices = list(range(12))
                rng.shuffle(indices)
                ordered.extend(samples[index] for index in indices)
            inputs.append(' '.join(map(str, [dimension, *initial, *[value for x, v in ordered for value in x + v]])))
            for step, (position, velocity) in enumerate(ordered):
                fraction = step / 72
                nearest = min(model.nodes, key=lambda node: model.compute_energy_distance(position, velocity, node))
                sigma = max(0.5, 1 * (1 - fraction))
                for node in model.nodes:
                    weight = 0.1 * (1 - fraction) * model.compute_neighborhood_weight(node.grid_coord, nearest.grid_coord, sigma)
                    if weight > 1e-4:
                        for index in range(dimension):
                            node.center_x[index] += weight * (position[index] - node.center_x[index])
                            node.center_v[index] += weight * (velocity[index] - node.center_v[index])
            expected.append([value for node in model.nodes for value in node.center_x + node.center_v])
        harness = '''package com.inceptless.traceweave.edge;
public final class FitTest {
    public static void main(String[] args) {
        java.util.Scanner input=new java.util.Scanner(System.in).useLocale(java.util.Locale.ROOT);
        while(input.hasNext()) {
            int d=input.nextInt(); double[] centers=new double[4*d], velocities=new double[4*d];
            for(int n=0;n<4;n++) {
                for(int i=0;i<d;i++) centers[n*d+i]=input.nextDouble();
                for(int i=0;i<d;i++) velocities[n*d+i]=input.nextDouble();
            }
            double[] original=centers.clone();
            IncrementalManifoldFit fit=new IncrementalManifoldFit(centers,velocities,new int[][]{{0,0},{0,1},{1,0},{1,1}},12,0.35);
            centers[0]=999;
            for(int step=0;step<72;step++) {
                double[] x=new double[d],v=new double[d];
                for(int i=0;i<d;i++) x[i]=input.nextDouble();
                for(int i=0;i<d;i++) v[i]=input.nextDouble();
                fit.update(x,v);
            }
            if(!fit.complete()) throw new AssertionError("incomplete six epochs");
            boolean rejected=false;
            try { fit.update(new double[d],new double[d]); } catch(IllegalStateException expected) { rejected=true; }
            if(!rejected) throw new AssertionError("extra update accepted");
            double[] x=fit.centers(),v=fit.velocities();
            for(int n=0;n<4;n++) {
                for(int i=0;i<d;i++) System.out.print(x[n*d+i]+" ");
                for(int i=0;i<d;i++) System.out.print(v[n*d+i]+" ");
            }
            System.out.println();
            x[0]=999;
            if(fit.centers()[0]==999) throw new AssertionError("mutable output alias");
        }
    }
}'''
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'FitTest.java'
            source.write_text(harness)
            subprocess.run(['javac', '-d', directory, str(root / 'android/src/com/inceptless/traceweave/edge/TopologyStrain.java'), str(root / 'android/src/com/inceptless/traceweave/edge/IncrementalManifoldFit.java'), str(source)], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.FitTest'], input='\n'.join(inputs), text=True, check=True, capture_output=True)
        self.assertEqual(len(result.stdout.splitlines()), 2)
        for line, reference in zip(result.stdout.splitlines(), expected):
            actual = list(map(float, line.split()))
            self.assertEqual(len(actual), len(reference))
            for value, target in zip(actual, reference):
                self.assertAlmostEqual(value, target, places=10)
