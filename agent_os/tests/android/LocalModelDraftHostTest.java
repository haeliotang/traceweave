package org.traceform.agentos;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalModelDraftHostTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        AtomicBoolean active = new AtomicBoolean();
        AtomicBoolean waiting = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Throwable> waitingFailure = new AtomicReference<>();
        AgentStore.Task task = new AgentStore.Task();
        Thread first = new Thread(() -> {
            try { LocalModelDraft.generate(task, new File("synthetic"), active); }
            catch (Throwable error) { failure.set(error); }
        });
        first.start();
        check(LocalModelRuntime.entered.await(5, TimeUnit.SECONDS), "active request did not enter runtime");
        Thread second = new Thread(() -> {
            try { LocalModelDraft.generate(task, new File("synthetic"), waiting); }
            catch (Throwable error) { waitingFailure.set(error); }
        });
        second.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (second.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
        check(second.getState() == Thread.State.BLOCKED, "second request did not wait for model resource");
        LocalModelDraft.cancel(waiting);
        check(!LocalModelRuntime.cancelled.get(), "cancelling a waiting request interrupted the active request");
        LocalModelRuntime.release.countDown();
        first.join(5000); second.join(5000);
        check(!first.isAlive() && !second.isAlive(), "requests did not finish");
        check(failure.get() == null, "active request failed after cancelling another request");
        check(waiting.get() && waitingFailure.get() instanceof IllegalStateException, "waiting request was not cancelled");

        LocalModelRuntime.reset();
        AtomicBoolean ownRequest = new AtomicBoolean();
        AtomicReference<Throwable> cancelledFailure = new AtomicReference<>();
        Thread third = new Thread(() -> {
            try { LocalModelDraft.generate(task, new File("synthetic"), ownRequest); }
            catch (Throwable error) { cancelledFailure.set(error); }
        });
        third.start();
        check(LocalModelRuntime.entered.await(5, TimeUnit.SECONDS), "new request did not enter runtime");
        LocalModelDraft.cancel(ownRequest);
        check(LocalModelRuntime.cancelled.get(), "active request did not reach runtime cancellation");
        LocalModelRuntime.release.countDown();
        third.join(5000);
        check(!third.isAlive() && cancelledFailure.get() instanceof IllegalStateException, "active request did not stop");

        LocalModelRuntime.reset();
        LocalModelRuntime.release.countDown();
        check("synthetic draft".equals(LocalModelDraft.generate(task, new File("synthetic"), new AtomicBoolean())),
            "subsequent request did not recover after cancellation");
    }
}

final class AgentStore {
    static final String QUEUED = "queued";
    static final class Memory { String content; }
    static final class Task { String status = QUEUED; String title = "synthetic"; Object plan = new Object(); }
}
final class TaskContext {
    static boolean isDocument(Object plan) { return true; }
    static boolean localOnly(Object plan) { return true; }
    static List<AgentStore.Memory> memories(Object plan) { return Collections.emptyList(); }
}
final class DocumentAction { static void validateContent(String text) {} }
final class LocalModelFiles { static boolean verifyQwen15(File file, AtomicBoolean token) { return !token.get(); } }
final class LocalModelRuntime {
    static CountDownLatch entered = new CountDownLatch(1);
    static CountDownLatch release = new CountDownLatch(1);
    static final AtomicBoolean cancelled = new AtomicBoolean();
    static void reset() { entered = new CountDownLatch(1); release = new CountDownLatch(1); cancelled.set(false); }
    static boolean isAvailable() { return true; }
    static void prepare() { cancelled.set(false); }
    static void cancel() { cancelled.set(true); }
    static byte[] generate(String path, byte[] prompt, int maxTokens) throws Exception {
        entered.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("runtime timed out");
        if (cancelled.get()) throw new IllegalStateException("runtime cancelled");
        return "synthetic draft".getBytes(StandardCharsets.UTF_8);
    }
}
