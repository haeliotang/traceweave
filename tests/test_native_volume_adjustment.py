# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeVolumeAdjustment(unittest.TestCase):
    def test_readback_restoration_external_change_failure_and_interrupt(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "VolumeTest.java"
            harness.write_text('''package com.inceptless.traceweave.edge;
public final class VolumeTest {
    static class Fake implements VolumeAdjustment.Control {
        int value=5,writes;boolean ignore,failRestore,external,interrupt,failSet;
        public int volume(){return value;}
        public boolean setVolume(int v)throws Exception{
            writes++;if(failRestore&&v==5)return false;
            if(!ignore)value=v;if(failSet&&v==4)throw new Exception("after-write error");return true;
        }
        public void pause()throws Exception{if(external)value=3;if(interrupt)throw new InterruptedException();}
    }
    public static void main(String[]args)throws Exception{
        Fake f=new Fake();VolumeAdjustment.Result r=VolumeAdjustment.run(f,15,true);
        if(!r.delivered||!r.restored||f.value!=5||f.writes!=2)throw new AssertionError("normal restore");
        f=new Fake();f.ignore=true;r=VolumeAdjustment.run(f,15,true);
        if(r.delivered||f.writes!=1)throw new AssertionError("unverified target");
        f=new Fake();f.external=true;r=VolumeAdjustment.run(f,15,true);
        if(!r.delivered||r.restored||f.value!=3||f.writes!=1)throw new AssertionError("external value overwritten");
        f=new Fake();f.failRestore=true;r=VolumeAdjustment.run(f,15,true);
        if(!r.delivered||r.restored||!r.restorationStatus.equals("failed"))throw new AssertionError("failed restoration");
        f=new Fake();f.interrupt=true;boolean interrupted=false;
        try{VolumeAdjustment.run(f,15,true);}catch(InterruptedException e){interrupted=true;}
        if(!interrupted||f.value!=5||!Thread.interrupted())throw new AssertionError("interrupt restore");
        f=new Fake();f.failSet=true;try{VolumeAdjustment.run(f,15,true);}catch(Exception e){}
        if(f.value!=5)throw new AssertionError("post-write exception restore");
        f=new Fake();r=VolumeAdjustment.run(f,15,false);if(f.writes!=0)throw new AssertionError("disabled");
        f=new Fake();f.value=4;r=VolumeAdjustment.recover(f,5,4,15);
        if(!r.restored||r.delivered||f.value!=5)throw new AssertionError("recovery");
        f=new Fake();f.value=3;r=VolumeAdjustment.recover(f,5,4,15);
        if(r.restored||f.writes!=0||f.value!=3)throw new AssertionError("recovery external ownership");
    }
}''')
            subprocess.run(["javac", "-d", directory, str(root / "android/src/com/inceptless/traceweave/edge/VolumeAdjustment.java"), str(harness)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "com.inceptless.traceweave.edge.VolumeTest"], check=True, capture_output=True)
