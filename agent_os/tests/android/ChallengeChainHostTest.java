import com.inceptless.traceweave.ChallengeChain;

/** Host check for the challenge recovery rules, run by test_challenge_chain.py. */
public final class ChallengeChainHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("FAIL " + label);
        checks++;
    }

    private static byte[] challenge(int fill) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) fill);
        return value;
    }

    private static byte[] zero() {
        return new byte[32];
    }

    public static void main(String[] args) {
        byte[] c1 = challenge(1);
        byte[] c2 = challenge(2);
        byte[] c3 = challenge(3);
        String c1Hex = ChallengeChain.encode(c1);
        String c2Hex = ChallengeChain.encode(c2);
        String zeroHex = ChallengeChain.encode(zero());
        check(zeroHex.equals(ChallengeChain.ZERO_HEX), "zero challenge is 32 zero bytes");

        // Bootstrap: only the predefined all-zero challenge may start the chain.
        ChallengeChain.Decision bootstrap = ChallengeChain.decide("", "", zero(), c1);
        check(bootstrap.accepted && bootstrap.advance && "bootstrap".equals(bootstrap.reason), "bootstrap");
        ChallengeChain.Decision wrongBootstrap = ChallengeChain.decide("", "", challenge(9), c1);
        check(!wrongBootstrap.accepted && "bootstrap_challenge_invalid".equals(wrongBootstrap.reason),
            "non-zero bootstrap challenge rejected");

        // The bootstrap round records the zero challenge, so the first response is recoverable.
        String history = ChallengeChain.nextHistory("", "");
        check(zeroHex.equals(history), "bootstrap history records the zero challenge");
        String expected = c1Hex;

        // First response is lost: the retry still carries the zero challenge.
        ChallengeChain.Decision firstLoss = ChallengeChain.decide(expected, history, zero(), c2);
        check(firstLoss.accepted && !firstLoss.advance && "recovery".equals(firstLoss.reason),
            "lost first response recovers");
        check(ChallengeChain.encode(firstLoss.responseChallenge).equals(expected),
            "recovery re-issues the first challenge");

        // After the retry succeeds the next request advances normally.
        ChallengeChain.Decision afterFirstRecovery = ChallengeChain.decide(expected, history, c1, c2);
        check(afterFirstRecovery.accepted && afterFirstRecovery.advance, "converged first retry advances");
        String nextHistory = ChallengeChain.nextHistory(expected, history);
        check(nextHistory.equals(c1Hex + "," + zeroHex), "history keeps the zero challenge behind the current one, got " + nextHistory);
        expected = c2Hex;

        // Ordinary lost response after the chain exists.
        ChallengeChain.Decision recovery = ChallengeChain.decide(expected, nextHistory, c1, c3);
        check(recovery.accepted && !recovery.advance && "recovery".equals(recovery.reason), "ordinary recovery");
        ChallengeChain.Decision afterRecovery = ChallengeChain.decide(expected, nextHistory, c2, c3);
        check(afterRecovery.accepted && afterRecovery.advance, "converged ordinary retry advances");

        // Unknown challenges are rejected and never reset the chain.
        ChallengeChain.Decision unknown = ChallengeChain.decide(expected, nextHistory, challenge(9), c3);
        check(!unknown.accepted && "challenge_mismatch".equals(unknown.reason), "unknown challenge rejected");

        System.out.println("PASS challenge_chain checks=" + checks);
    }
}
