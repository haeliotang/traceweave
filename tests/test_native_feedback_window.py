# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeFeedbackWindow(unittest.TestCase):
    def test_feedback_window_and_source_clock_censoring(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "FeedbackTest.java"
            harness.write_text('''package com.inceptless.traceweave.edge;
public final class FeedbackTest {
    static FeedbackWindow.Frame frame(long time,String identity,String token,boolean continuous) {
        return new FeedbackWindow.Frame(1700000000000L+time,time,identity,token,continuous);
    }
    public static void main(String[] args) {
        FeedbackWindow.Frame original=frame(0,"bound-model-session-source","A",false),previous=original;
        for(long time:new long[]{200000,400000,599999}) {
            FeedbackWindow.Frame now=frame(time,original.identity,"B",true);
            if(!FeedbackWindow.observe(original,previous,now).status.equals("observing")) throw new AssertionError("early feedback");
            previous=now;
        }
        for(String token:new String[]{"A","B"}) {
            FeedbackWindow.Outcome result=FeedbackWindow.observe(original,previous,frame(600000,original.identity,token,true));
            if(!result.status.equals("evaluated") || result.reward!=(token.equals("A")?0:1)) throw new AssertionError("ten minute feedback");
        }
        for(FeedbackWindow.Frame now:new FeedbackWindow.Frame[]{
            frame(600000,"changed-model-session-source","B",true),frame(600000,original.identity,"B",false),
            frame(400000,original.identity,"B",true),frame(900001,original.identity,"B",true),
            new FeedbackWindow.Frame(1700000605000L,600000,original.identity,"B",true)})
            if(!FeedbackWindow.observe(original,previous,now).status.equals("censored")) throw new AssertionError("censoring");
        if(!FeedbackWindow.observe(original,original,frame(600000,original.identity,"B",true)).status.equals("censored"))
            throw new AssertionError("unobserved gap");
        if(!FeedbackWindow.observe(original,frame(600000,original.identity,"A",true),frame(900000,original.identity,"B",true)).status.equals("evaluated"))
            throw new AssertionError("fifteen minute equality");
    }
}''')
            subprocess.run(["javac", "-d", directory, str(root / "android/src/com/inceptless/traceweave/edge/FeedbackWindow.java"), str(harness)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "com.inceptless.traceweave.edge.FeedbackTest"], check=True, capture_output=True)
