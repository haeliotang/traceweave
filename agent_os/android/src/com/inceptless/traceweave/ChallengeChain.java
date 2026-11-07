package com.inceptless.traceweave;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Recovery rules for the rolling challenge used by the P-256 channel binding.
 *
 * <p>The responder advances its challenge after accepting a request. If the response is lost,
 * the requester still holds the previous challenge and would be rejected forever. The responder
 * therefore keeps a short history of challenges it issued: a request that echoes one of those
 * older values is accepted in <em>recovery</em> mode, which re-issues the current challenge
 * instead of advancing. Both sides converge after one successful round trip without resetting
 * the chain (which would reopen the replay window).
 */
public final class ChallengeChain {
    private ChallengeChain() {}

    public static final class Decision {
        public final boolean accepted;
        /** True when the responder should issue a new challenge and record it. */
        public final boolean advance;
        /** The challenge to put in the response. */
        public final byte[] responseChallenge;
        public final String reason;

        Decision(boolean accepted, boolean advance, byte[] responseChallenge, String reason) {
            this.accepted = accepted;
            this.advance = advance;
            this.responseChallenge = responseChallenge;
            this.reason = reason;
        }
    }

    /**
     * @param expectedHex the challenge this device issued last (empty before the first exchange)
     * @param previousHex comma-separated older challenges, newest first
     * @param received the challenge carried by the request
     * @param next a fresh challenge for the normal path
     */
    public static Decision decide(String expectedHex, String previousHex, byte[] received, byte[] next) {
        String expected = expectedHex == null ? "" : expectedHex;
        String actual = encode(received);
        if (expected.isEmpty()) {
            // First request after pairing: accept the empty challenge and start the chain.
            return new Decision(true, true, next, "bootstrap");
        }
        if (constantTimeEquals(expected, actual)) return new Decision(true, true, next, "normal");
        if (listed(previousHex, actual)) {
            // Lost response: do not advance, re-issue the challenge the peer is missing.
            return new Decision(true, false, decode(expected), "recovery");
        }
        return new Decision(false, false, null, "challenge_mismatch");
    }

    /** The history to persist after a normal advance (newest first, at most three). */
    public static String nextHistory(String expectedHex, String previousHex) {
        String expected = expectedHex == null ? "" : expectedHex;
        if (expected.isEmpty()) return "";
        return previousHex == null || previousHex.isEmpty() ? expected : expected + "," + previousHex;
    }

    public static boolean listed(String csv, String challengeHex) {
        if (csv == null || csv.isEmpty() || challengeHex == null || challengeHex.isEmpty()) return false;
        for (String item : csv.split(",")) if (constantTimeEquals(item.trim(), challengeHex)) return true;
        return false;
    }

    public static String encode(byte[] value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }

    public static byte[] decode(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        byte[] value = new byte[hex.length() / 2];
        for (int index = 0; index < value.length; index++)
            value[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        return value;
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
