// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Host-only checks for the pure memory merge rule. No Android dependency. */
public final class MemoryMergeHostTest {
    private static int checks;

    public static void main(String[] args) {
        olderVersionIsExcluded();
        concurrentEditsStayCandidates();
        replayDoesNotDuplicate();
        tombstoneWins();
        actorLimitIsEnforced();
        oneClockCannotHoldTwoContents();
        resolvedClockAddsOneLocalTick();
        System.out.println("PASS memory_merge checks=" + checks);
    }

    private static MemoryMerge.Version version(long counter, String hash) {
        TreeMap<String, Long> clock = new TreeMap<>();
        clock.put("device-a", counter);
        return new MemoryMerge.Version(clock, hash, false);
    }

    private static void olderVersionIsExcluded() {
        List<MemoryMerge.Version> merged = MemoryMerge.merge(Arrays.asList(version(2, "new")), Arrays.asList(version(1, "old")));
        check(merged.size() == 1 && "new".equals(merged.get(0).contentHash), "older version is dropped");
    }

    private static void concurrentEditsStayCandidates() {
        TreeMap<String, Long> other = new TreeMap<>();
        other.put("device-b", 1L);
        MemoryMerge.Version left = version(1, "x");
        MemoryMerge.Version right = new MemoryMerge.Version(other, "y", false);
        List<MemoryMerge.Version> merged = MemoryMerge.merge(Collections.singletonList(left), Collections.singletonList(right));
        check(merged.size() == 2, "concurrent edits stay separate");
    }

    private static void replayDoesNotDuplicate() {
        MemoryMerge.Version item = version(3, "same");
        List<MemoryMerge.Version> merged = MemoryMerge.merge(Collections.singletonList(item), Collections.singletonList(item));
        check(merged.size() == 1, "replayed version is deduplicated");
    }

    private static void tombstoneWins() {
        TreeMap<String, Long> clock = new TreeMap<>();
        clock.put("device-a", 1L);
        MemoryMerge.Version tombstone = new MemoryMerge.Version(clock, null, true);
        List<MemoryMerge.Version> merged = MemoryMerge.merge(Arrays.asList(version(9, "resurrected")), Collections.singletonList(tombstone));
        check(merged.size() == 1 && merged.get(0).tombstone, "deletion wins over concurrent edits");
    }

    private static void actorLimitIsEnforced() {
        TreeMap<String, Long> clock = new TreeMap<>();
        for (int index = 0; index < MemoryMerge.MAX_ACTORS + 1; index++) clock.put("device-" + index, 1L);
        boolean rejected = false;
        try {
            MemoryMerge.merge(new ArrayList<>(), Arrays.asList(new MemoryMerge.Version(clock, "x", false)));
        } catch (IllegalArgumentException error) {
            rejected = true;
        }
        check(rejected, "more than 32 devices is rejected");
    }

    private static void oneClockCannotHoldTwoContents() {
        boolean rejected = false;
        try {
            MemoryMerge.merge(Arrays.asList(version(1, "a")), Arrays.asList(version(1, "b")));
        } catch (IllegalArgumentException error) {
            rejected = true;
        }
        check(rejected, "same clock with different content is rejected");
    }

    private static void resolvedClockAddsOneLocalTick() {
        TreeMap<String, Long> clock = new TreeMap<>();
        clock.put("device-a", 3L);
        TreeMap<String, Long> resolved = MemoryMerge.resolvedClock(
            Arrays.asList(new MemoryMerge.Version(clock, "x", false)), "device-b");
        check(resolved.get("device-a") == 3L && resolved.get("device-b") == 1L, "resolution merges clocks and ticks locally");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new IllegalStateException("assertion: " + message);
    }
}
