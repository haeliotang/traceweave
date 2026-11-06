package com.inceptless.traceweave;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rules that decide whether an anchored audit trail is intact.
 *
 * <p>Pure logic so it can be tested on a host: the caller supplies the chain hashes,
 * the anchors it found in the database, and the anchor state that lives outside the
 * database. A missing anchor inside the anchored range is as suspicious as a wrong one.
 */
public final class AuditAnchorVerifier {
    private AuditAnchorVerifier() {}

    /** Returns null when everything checks out, otherwise a short machine-readable reason. */
    public static String check(int rowCount, List<String> hashes, List<AnchorMac.Entry> anchors,
                               Integer stateCount, String stateHead) {
        for (AnchorMac.Entry anchor : anchors) {
            if (anchor.index < 0 || anchor.index >= hashes.size()) return "anchor_out_of_range_" + anchor.index;
            if (!anchor.hash.equals(hashes.get(anchor.index))) return "anchor_hash_mismatch_" + anchor.index;
        }
        if (stateCount == null) return null;
        if (rowCount < stateCount) return "chain_truncated";
        if (stateCount <= 0) return null;
        if (stateHead == null || stateHead.isEmpty()) return "state_head_missing";
        if (!stateHead.equals(hashes.get(stateCount - 1))) return "head_mismatch";
        Set<Integer> anchored = new HashSet<>();
        for (AnchorMac.Entry anchor : anchors) anchored.add(anchor.index);
        for (int index = 0; index < stateCount; index++) {
            if (!anchored.contains(index)) return "anchor_missing_" + index;
        }
        return null;
    }
}
