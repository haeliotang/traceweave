package org.traceform.agentos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pure merge for versions of one memory ID.
 *
 * Mirrors the experimental Rust rule: a version is a vector clock plus a content
 * hash (or a tombstone). Older versions are dropped, concurrent edits are kept
 * as separate candidates, a tombstone wins over everything, and one clock may
 * not describe two different contents.
 *
 * This class has no Android dependency so it can be exercised by a host test.
 */
final class MemoryMerge {
    /** Recency of an encoded clock: the largest counter anywhere in the map. */
    static long recency(String encodedClock) {
        if (encodedClock == null || encodedClock.isEmpty()) return 0;
        long best = 0;
        for (String entry : encodedClock.split(",")) {
            int colon = entry.lastIndexOf(':');
            if (colon < 0) continue;
            try {
                best = Math.max(best, Long.parseLong(entry.substring(colon + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    static final int MAX_ACTORS = 32;
    static final int MAX_BRANCHES = 64;
    static final int MAX_HASH_LENGTH = 128;

    static final class Version implements Comparable<Version> {
        final TreeMap<String, Long> clock;
        final String contentHash;
        final boolean tombstone;

        Version(Map<String, Long> clock, String contentHash, boolean tombstone) {
            this.clock = new TreeMap<>(clock);
            this.contentHash = contentHash;
            this.tombstone = tombstone;
        }

        boolean sameAs(Version other) {
            return clock.equals(other.clock) && Objects.equals(contentHash, other.contentHash) && tombstone == other.tombstone;
        }

        @Override public int compareTo(Version other) {
            int byClock = compareClock(clock, other.clock);
            if (byClock != 0) return byClock;
            int byHash = nullSafe(contentHash).compareTo(nullSafe(other.contentHash));
            if (byHash != 0) return byHash;
            return Boolean.compare(tombstone, other.tombstone);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Version && sameAs((Version) other);
        }

        @Override public int hashCode() {
            return Objects.hash(clock, contentHash, tombstone);
        }
    }

    private MemoryMerge() {}

    /** Merge two sets of versions; returns the surviving candidates in canonical order. */
    static List<Version> merge(List<Version> left, List<Version> right) {
        if (left.size() + right.size() > MAX_BRANCHES) throw new IllegalArgumentException("Too many memory versions");
        List<Version> all = new ArrayList<>(left);
        all.addAll(right);
        for (Version version : all) validate(version);
        Collections.sort(all);
        for (int index = 1; index < all.size(); index++) {
            Version previous = all.get(index - 1);
            Version current = all.get(index);
            if (previous.clock.equals(current.clock)
                    && (!Objects.equals(previous.contentHash, current.contentHash) || previous.tombstone != current.tombstone))
                throw new IllegalArgumentException("One clock identifies conflicting content");
        }
        List<Version> deduped = new ArrayList<>();
        for (Version version : all) {
            boolean duplicate = false;
            for (Version kept : deduped) if (kept.sameAs(version)) duplicate = true;
            if (!duplicate) deduped.add(version);
        }
        boolean hasTombstone = false;
        for (Version version : deduped) if (version.tombstone) hasTombstone = true;
        if (hasTombstone) {
            Iterator<Version> iterator = deduped.iterator();
            while (iterator.hasNext()) if (!iterator.next().tombstone) iterator.remove();
        }
        List<Version> result = new ArrayList<>();
        for (Version candidate : deduped) {
            boolean dominated = false;
            for (Version other : deduped) {
                if (other == candidate || other.clock.equals(candidate.clock)) continue;
                if (dominates(other.clock, candidate.clock)) dominated = true;
            }
            if (!dominated) result.add(candidate);
        }
        return result;
    }

    static boolean dominates(Map<String, Long> left, Map<String, Long> right) {
        for (Map.Entry<String, Long> entry : right.entrySet()) {
            Long counter = left.get(entry.getKey());
            if (counter == null || counter < entry.getValue()) return false;
        }
        return true;
    }

    static void validate(Version version) {
        if (version.clock.isEmpty() || version.clock.size() > MAX_ACTORS)
            throw new IllegalArgumentException("Invalid memory clock");
        for (Map.Entry<String, Long> entry : version.clock.entrySet()) {
            String actor = entry.getKey();
            if (!validActor(actor) || entry.getValue() == null || entry.getValue() <= 0)
                throw new IllegalArgumentException("Invalid memory clock");
        }
        if (!version.tombstone) {
            if (version.contentHash == null || version.contentHash.isEmpty() || version.contentHash.length() > MAX_HASH_LENGTH)
                throw new IllegalArgumentException("Invalid memory version content");
        } else if (version.contentHash != null && version.contentHash.length() > MAX_HASH_LENGTH) {
            throw new IllegalArgumentException("Invalid memory version content");
        }
    }

    /** The clock to persist for a resolved user choice: every candidate clock, plus one local tick. */
    static TreeMap<String, Long> resolvedClock(List<Version> reviewed, String localActor) {
        TreeMap<String, Long> merged = new TreeMap<>();
        for (Version version : reviewed) {
            for (Map.Entry<String, Long> entry : version.clock.entrySet())
                merged.merge(entry.getKey(), entry.getValue(), Math::max);
        }
        merged.merge(localActor, 1L, Long::sum);
        if (merged.size() > MAX_ACTORS) throw new IllegalArgumentException("Too many devices");
        return merged;
    }

    private static boolean validActor(String actor) {
        if (actor == null || actor.isEmpty() || actor.length() > 64) return false;
        for (int index = 0; index < actor.length(); index++) {
            char value = actor.charAt(index);
            boolean allowed = (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9') || value == '-' || value == '_';
            if (!allowed) return false;
        }
        return true;
    }

    private static int compareClock(Map<String, Long> left, Map<String, Long> right) {
        Iterator<Map.Entry<String, Long>> leftIterator = left.entrySet().iterator();
        Iterator<Map.Entry<String, Long>> rightIterator = right.entrySet().iterator();
        while (leftIterator.hasNext() || rightIterator.hasNext()) {
            Map.Entry<String, Long> a = leftIterator.hasNext() ? leftIterator.next() : null;
            Map.Entry<String, Long> b = rightIterator.hasNext() ? rightIterator.next() : null;
            if (a == null) return -1;
            if (b == null) return 1;
            int byKey = a.getKey().compareTo(b.getKey());
            if (byKey != 0) return byKey;
            int byValue = Long.compare(a.getValue(), b.getValue());
            if (byValue != 0) return byValue;
        }
        return 0;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
