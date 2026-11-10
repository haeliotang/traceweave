# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeTrainingVectors(unittest.TestCase):
    def test_spooled_six_epoch_fit_matches_direct_vectors_and_rejects_corruption(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        harness = '''package com.inceptless.traceweave.edge;
public final class SpoolTest {
    public static void main(String[] args) throws Exception {
        java.io.File directory=new java.io.File(args[0]);
        String digest=String.format("%064d",1);
        java.util.Random random=new java.util.Random(42);
        for(int dimension:new int[]{1,128}) {
            double[] centers=new double[16*dimension],velocities=new double[16*dimension];
            int[][] grid=new int[16][2];
            for(int n=0;n<16;n++) { grid[n][0]=n/4;grid[n][1]=n%4; }
            for(int i=0;i<centers.length;i++) {centers[i]=random.nextDouble();velocities[i]=random.nextDouble();}
            double[][] positions=new double[70][dimension],speeds=new double[70][dimension];
            for(int n=0;n<70;n++) for(int i=0;i<dimension;i++) {positions[n][i]=random.nextDouble();speeds[n][i]=random.nextDouble();}
            positions[0][0]=-0.0; speeds[0][0]=Double.MIN_VALUE;
            java.io.File owned;
            try(TrainingVectors spool=new TrainingVectors(directory,dimension,70,digest)) {
                owned=spool.path;
                for(int n=0;n<70;n++) spool.append(n*1000L,positions[n],speeds[n]);
                spool.seal();
                IncrementalManifoldFit direct=new IncrementalManifoldFit(centers,velocities,grid,70,0.35);
                IncrementalManifoldFit cached=new IncrementalManifoldFit(centers,velocities,grid,70,0.35);
                int[] order=new int[70];for(int n=0;n<70;n++) order[n]=n;
                java.util.Random shuffle=new java.util.Random(42);
                for(int epoch=0;epoch<6;epoch++) {
                    for(int i=69;i>0;i--) {int j=shuffle.nextInt(i+1),swap=order[i];order[i]=order[j];order[j]=swap;}
                    for(int n:order) {
                        TrainingVectors.Sample sample=spool.read(n,n*1000L);
                        if(!java.util.Arrays.equals(sample.position,positions[n]) || !java.util.Arrays.equals(sample.velocity,speeds[n])) throw new AssertionError("double bits changed");
                        direct.update(positions[n],speeds[n]); cached.update(sample.position,sample.velocity);
                    }
                }
                if(!java.util.Arrays.equals(direct.centers(),cached.centers()) || !java.util.Arrays.equals(direct.velocities(),cached.velocities())) throw new AssertionError("six epoch output changed");
                boolean rejected=false;
                try {spool.read(0,1);} catch(java.io.IOException expected) {rejected=true;}
                if(!rejected) throw new AssertionError("timestamp mismatch accepted");
                Thread.currentThread().interrupt();rejected=false;
                try {spool.read(0,0);} catch(InterruptedException expected) {rejected=true;}
                Thread.interrupted();if(!rejected) throw new AssertionError("cancellation ignored");
                try(java.io.RandomAccessFile corrupt=new java.io.RandomAccessFile(owned,"rw")) {corrupt.seek(84);corrupt.writeByte(123);}
                rejected=false;try {spool.read(0,0);} catch(java.io.IOException expected) {rejected=true;}
                if(!rejected) throw new AssertionError("record corruption accepted");
                try(java.io.RandomAccessFile corrupt=new java.io.RandomAccessFile(owned,"rw")) {corrupt.seek(12);corrupt.writeByte(120);}
                rejected=false;try {spool.read(1,1000);} catch(java.io.IOException expected) {rejected=true;}
                if(!rejected) throw new AssertionError("source binding corruption accepted");
                try(java.io.RandomAccessFile corrupt=new java.io.RandomAccessFile(owned,"rw")) {corrupt.setLength(76);}
                rejected=false;try {spool.read(1,1000);} catch(java.io.IOException expected) {rejected=true;}
                if(!rejected) throw new AssertionError("truncation accepted");
            }
            if(owned.exists()) throw new AssertionError("owned spool not cleaned");
        }
    }
}'''
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'SpoolTest.java'
            source.write_text(harness)
            classes = root / 'android/src/com/inceptless/traceweave/edge'
            subprocess.run(['javac', '-d', directory, *[str(classes / name) for name in ('TrainingVectors.java', 'TopologyStrain.java', 'IncrementalManifoldFit.java')], str(source)], check=True, capture_output=True)
            subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.SpoolTest', directory], check=True, capture_output=True)
