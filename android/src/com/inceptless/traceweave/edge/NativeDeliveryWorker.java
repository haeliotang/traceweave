// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One actuator at a time, with interruption for conditional restoration on stop. */
final class NativeDeliveryWorker implements AutoCloseable {
    private ExecutorService executor;
    private Thread active;
    private long generation;
    private boolean busy,closed;
    synchronized boolean submit(Callable<String> execute,Consumer<String> publish) {
        if(closed || busy)return false;
        if(executor==null)executor=Executors.newSingleThreadExecutor();
        busy=true;long ticket=generation;
        executor.submit(() -> {
            try {
                synchronized(NativeDeliveryWorker.this){
                    if(closed || ticket!=generation)return;
                    active=Thread.currentThread();
                }
                String receipt=execute.call();
                synchronized(NativeDeliveryWorker.this){if(!closed && ticket==generation)publish.accept(receipt);}
            } catch(Exception error){
                java.util.logging.Logger.getLogger("Traceform").log(java.util.logging.Level.WARNING,"delivery task failed",error);
            } finally {synchronized(NativeDeliveryWorker.this){active=null;busy=false;}}
        });
        return true;
    }
    synchronized void stop(){generation++;if(active!=null)active.interrupt();}
    @Override public synchronized void close(){closed=true;stop();if(executor!=null)executor.shutdownNow();}
}
