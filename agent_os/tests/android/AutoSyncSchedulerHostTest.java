package org.traceform.agentos;

public final class AutoSyncSchedulerHostTest {
    public static void main(String[] args) {
        if (AutoSyncPolicy.clampInterval(0) != 60 * 60 * 1000L) throw new AssertionError("default interval");
        if (AutoSyncPolicy.clampInterval(60_000) != 15 * 60 * 1000L) throw new AssertionError("minimum interval");
        if (AutoSyncPolicy.clampInterval(2 * 60 * 60 * 1000L) != 2 * 60 * 60 * 1000L) throw new AssertionError("explicit interval");
        System.out.println("AUTO_SYNC_SCHEDULER_OK");
    }
}
