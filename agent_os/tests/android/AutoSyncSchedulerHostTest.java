package org.traceform.agentos;

public final class AutoSyncSchedulerHostTest {
    public static void main(String[] args) {
        if (AutoSyncPolicy.clampInterval(0) != 60 * 60 * 1000L) throw new AssertionError("default interval");
        if (AutoSyncPolicy.clampInterval(60_000) != 15 * 60 * 1000L) throw new AssertionError("minimum interval");
        if (AutoSyncPolicy.clampInterval(2 * 60 * 60 * 1000L) != 2 * 60 * 60 * 1000L) throw new AssertionError("explicit interval");
        if (AutoSyncPolicy.pickTarget(0, new long[0]) != -1) throw new AssertionError("empty target");
        if (AutoSyncPolicy.pickTarget(3, new long[]{5, 9, 7}) != 1) throw new AssertionError("newest target");
        if (AutoSyncPolicy.pickTarget(2, new long[]{0, 0}) != 0) throw new AssertionError("tie target");
        System.out.println("AUTO_SYNC_SCHEDULER_OK");
    }
}
