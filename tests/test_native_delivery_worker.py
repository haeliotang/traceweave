from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeDeliveryWorker(unittest.TestCase):
    def test_async_single_flight_stop_cleanup_and_closed_rejection(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "DeliveryTest.java"
            harness.write_text('''package org.traceform.edge;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
public final class DeliveryTest {
    public static void main(String[]args)throws Exception{
        NativeDeliveryWorker worker=new NativeDeliveryWorker();
        CountDownLatch entered=new CountDownLatch(1),blocked=new CountDownLatch(1),cleaned=new CountDownLatch(1),published=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();long owner=Thread.currentThread().getId();
        if(!worker.submit(() -> {if(Thread.currentThread().getId()==owner)throw new AssertionError("blocking caller");
            entered.countDown();try{blocked.await();}finally{cleaned.countDown();}return "old";},x -> calls.incrementAndGet()))throw new AssertionError("start");
        if(!entered.await(5,TimeUnit.SECONDS))throw new AssertionError("worker entry");
        if(worker.submit(() -> "overlap",x -> calls.incrementAndGet()))throw new AssertionError("single flight");
        worker.stop();if(!cleaned.await(5,TimeUnit.SECONDS))throw new AssertionError("stop cleanup");
        boolean accepted=false;
        for(int n=0;n<500&&!accepted;n++){accepted=worker.submit(() -> "new",x -> {calls.incrementAndGet();published.countDown();});if(!accepted)Thread.sleep(10);}
        if(!accepted||!published.await(5,TimeUnit.SECONDS)||calls.get()!=1)throw new AssertionError("old receipt published");
        worker.close();if(worker.submit(() -> "closed",x -> calls.incrementAndGet()))throw new AssertionError("closed accepted");
    }
}''')
            subprocess.run(["javac", "-d", directory, str(root / "android/src/org/traceform/edge/NativeDeliveryWorker.java"), str(harness)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "org.traceform.edge.DeliveryTest"], check=True, capture_output=True, timeout=20)
