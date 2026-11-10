// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * Anchors bind a chain head to a key the device holds outside the database. A
 * database rewritten by hand cannot produce new anchors without that key.
 *
 * <p>Pure logic lives here so it can be tested on a plain JVM; the Keystore-backed
 * key is supplied by the caller.
 */
public final class AnchorMac {
    private AnchorMac() {}

    public static String compute(SecretKey key, int index, String hash, long anchoredAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            String payload = index + "|" + hash + "|" + anchoredAt;
            byte[] signed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(signed.length * 2);
            for (byte value : signed) hex.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("HmacSHA256 unavailable", error);
        }
    }

    /** True when every anchor carries a valid MAC for its own index and hash. */
    public static boolean allValid(SecretKey key, List<Entry> anchors) {
        for (Entry anchor : anchors) {
            if (!compute(key, anchor.index, anchor.hash, anchor.anchoredAt).equals(anchor.mac)) return false;
        }
        return true;
    }

    /** The chain row at an anchor's index must still hash to that anchor's value. */
    public static int firstMismatched(List<Entry> anchors, List<String> chainHashes) {
        for (Entry anchor : anchors) {
            if (anchor.index < 0 || anchor.index >= chainHashes.size()) return anchor.index;
            if (!anchor.hash.equals(chainHashes.get(anchor.index))) return anchor.index;
        }
        return -1;
    }

    public static final class Entry {
        public final int index;
        public final String hash;
        public final long anchoredAt;
        public final String mac;

        public Entry(int index, String hash, long anchoredAt, String mac) {
            this.index = index;
            this.hash = hash;
            this.anchoredAt = anchoredAt;
            this.mac = mac == null ? "" : mac;
        }
    }
}
