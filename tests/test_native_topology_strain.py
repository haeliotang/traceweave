from pathlib import Path
import random
import shutil
import subprocess
import tempfile
import unittest

from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode


class TestNativeTopologyStrain(unittest.TestCase):
    def test_nearest_two_and_energy_match_python(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        rng = random.Random(42)
        model = DynamicalEnergyManifold()
        samples, expected = [], []
        for dimension in (1, 3, 128):
            for sample in range(12):
                position = [rng.uniform(-2, 2) for _ in range(dimension)]
                velocity = [0 if sample == 0 else rng.uniform(-1, 1) for _ in range(dimension)]
                nodes = [ManifoldNode(index, (index // 4, index % 4), str(index),
                                      [rng.uniform(-2, 2) for _ in range(dimension)],
                                      [0 if sample == 1 else rng.uniform(-1, 1) for _ in range(dimension)])
                         for index in range(16)]
                energies = [model.compute_energy_distance(position, velocity, node) for node in nodes]
                first, second = sorted(range(16), key=lambda index: energies[index])[:2]
                separation = sum(abs(a - b) for a, b in zip(nodes[first].grid_coord, nodes[second].grid_coord))
                expected.append((first, second, energies[first], separation > 1))
                values = [dimension, *position, *velocity]
                for node in nodes:
                    values.extend([*node.grid_coord, *node.center_x, *node.center_v])
                samples.append(' '.join(map(str, values)))
        harness = '''package org.traceform.edge;
public final class StrainTest {
    public static void main(String[] args) {
        java.util.Scanner input = new java.util.Scanner(System.in).useLocale(java.util.Locale.ROOT);
        while (input.hasNext()) {
            int dimension = input.nextInt();
            double[] x = new double[dimension], v = new double[dimension];
            double[] centers = new double[16 * dimension], velocities = new double[16 * dimension];
            int[][] grid = new int[16][2];
            for (int i=0;i<dimension;i++) x[i]=input.nextDouble();
            for (int i=0;i<dimension;i++) v[i]=input.nextDouble();
            for (int node=0;node<16;node++) {
                grid[node][0]=input.nextInt(); grid[node][1]=input.nextInt();
                for (int i=0;i<dimension;i++) centers[node*dimension+i]=input.nextDouble();
                for (int i=0;i<dimension;i++) velocities[node*dimension+i]=input.nextDouble();
            }
            TopologyStrain.Result r = TopologyStrain.assess(x,v,centers,velocities,grid,0.35);
            System.out.println(r.first+" "+r.second+" "+r.energy+" "+r.nonadjacent);
        }
        TopologyStrain.Result tied = TopologyStrain.assess(new double[]{0},new double[]{0},new double[]{0,0},new double[]{0,0},new int[][]{{0,0},{0,1}},0.35);
        if(tied.first!=0 || tied.second!=1 || tied.nonadjacent) throw new AssertionError("stable ties");
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.MAX_VALUE}) {
            boolean rejected=false;
            try { TopologyStrain.assess(new double[]{invalid},new double[]{0},new double[]{0},new double[]{0},new int[][]{{0,0}},0.35); }
            catch(IllegalArgumentException expected) { rejected=true; }
            if(!rejected) throw new AssertionError("invalid/overflow coordinates");
        }
    }
}'''
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'StrainTest.java'
            source.write_text(harness)
            subprocess.run(['javac', '-d', directory, str(root / 'android/src/org/traceform/edge/TopologyStrain.java'), str(source)], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.StrainTest'], input='\n'.join(samples), text=True, check=True, capture_output=True)
        for line, reference in zip(result.stdout.splitlines(), expected):
            first, second, energy, nonadjacent = line.split()
            self.assertEqual((int(first), int(second), nonadjacent == 'true'), (reference[0], reference[1], reference[3]))
            self.assertAlmostEqual(float(energy), reference[2], places=9)
        self.assertEqual(len(result.stdout.splitlines()), len(expected))
