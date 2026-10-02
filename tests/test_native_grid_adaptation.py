from pathlib import Path
import random
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from traceform.evolution.guarded_calibration import adapt_grid_structure
from traceform.representation.manifold_lattice import DynamicalEnergyManifold, ManifoldNode


class TestNativeGridAdaptation(unittest.TestCase):
    def test_training_pair_coordinate_swaps_match_python(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        rng = random.Random(42)
        inputs, expected = [], []
        for case in range(36):
            states = (4, 9, 16)[case % 3]
            width = int(states ** 0.5)
            tokens = [f'M{index:02}' for index in range(states)]
            rng.shuffle(tokens)
            coordinates = [(index // width, index % width) for index in range(states)]
            pairs = [[0] * states for _ in range(states)]
            for first in range(states):
                for second in range(first + 1, states):
                    pairs[first][second] = rng.randrange(30) if case else 0
            model = DynamicalEnergyManifold(grid_height=width, grid_width=width)
            model.nodes = [ManifoldNode(index, coordinates[index], tokens[index], [0.0], [0.0]) for index in range(states)]
            counts = [{'tokens': [tokens[first], tokens[second]], 'count': pairs[first][second]}
                      for first in range(states) for second in range(first + 1, states)]
            with patch('traceform.evolution.guarded_calibration.assess_topology', return_value={'nearest_pair_counts': counts}):
                report = adapt_grid_structure(model, {}, [])
            expected.append([report['training_nonadjacent_pairs_before'], report['training_nonadjacent_pairs_after'],
                             *[value for node in model.nodes for value in node.grid_coord],
                             *[tokens.index(token) for swap in report['coordinate_swaps'] for token in swap]])
            inputs.append(' '.join(map(str, [states, *tokens, *[value for coord in coordinates for value in coord],
                                               *[value for row in pairs for value in row]])))
        harness = '''package org.traceform.edge;
public final class GridTest {
    public static void main(String[] args) throws Exception {
        java.util.Scanner input = new java.util.Scanner(System.in);
        while (input.hasNext()) {
            int n = input.nextInt(); String[] tokens = new String[n];
            int[][] grid = new int[n][2]; long[][] pairs = new long[n][n];
            for (int i=0;i<n;i++) tokens[i]=input.next();
            for (int i=0;i<n;i++) for(int j=0;j<2;j++) grid[i][j]=input.nextInt();
            for (int i=0;i<n;i++) for(int j=0;j<n;j++) pairs[i][j]=input.nextLong();
            String original=java.util.Arrays.deepToString(grid);
            GridAdaptation.Result result=GridAdaptation.adapt(tokens,grid,pairs);
            if(!original.equals(java.util.Arrays.deepToString(grid))) throw new AssertionError("input changed");
            System.out.print(result.before+" "+result.after+" ");
            for(int[] coord:result.grid) for(int value:coord) System.out.print(value+" ");
            for(int[] swap:result.swaps) for(int value:swap) System.out.print(value+" ");
            System.out.println();
            pairs[0][0]=1;
            boolean rejected=false;
            try { GridAdaptation.adapt(tokens,grid,pairs); } catch(IllegalArgumentException expected) { rejected=true; }
            if(!rejected) throw new AssertionError("diagonal count accepted");
            pairs[0][0]=0; Thread.currentThread().interrupt();
            rejected=false;
            try { GridAdaptation.adapt(tokens,grid,pairs); } catch(InterruptedException expected) { rejected=true; }
            Thread.interrupted();
            if(!rejected) throw new AssertionError("cancellation ignored");
        }
    }
}'''
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'GridTest.java'
            source.write_text(harness)
            subprocess.run(['javac', '-d', directory, str(root / 'android/src/org/traceform/edge/GridAdaptation.java'), str(source)], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.GridTest'], input='\n'.join(inputs), text=True, check=True, capture_output=True)
        self.assertEqual([list(map(int, line.split())) for line in result.stdout.splitlines()], expected)
        self.assertTrue(any(row[1] < row[0] for row in expected))
