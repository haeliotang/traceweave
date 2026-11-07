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

    public static void main(String[] args) {
        byte[] c1 = challenge(1);
        byte[] c2 = challenge(2);
        byte[] c3 = challenge(3);

        // Bootstrap: no expected challenge yet.
        ChallengeChain.Decision bootstrap = ChallengeChain.decide("", "", ChallengeChain.decode(""), c1);
        check(bootstrap.accepted && bootstrap.advance && "bootstrap".equals(bootstrap.reason), "bootstrap");
        String history = ChallengeChain.nextHistory("", "");
        check(history.isEmpty(), "bootstrap history is empty");
        String expected = ChallengeChain.encode(c1);

        // Normal exchange advances and records the previous challenge.
        String previous = ChallengeChain.nextHistory("", ChallengeChain.encode(ChallengeChain.decode("")).isEmpty() ? "" : "");
        ChallengeChain.Decision normal = ChallengeChain.decide(expected, previous, c1, c2);
        check(normal.accepted && normal.advance && "normal".equals(normal.reason), "normal advance");
        String nextPrevious = ChallengeChain.nextHistory(expected, previous);
        check(("normal".equals(normal.reason)) && nextPrevious.equals(expected), "history records the old expected");
        expected = ChallengeChain.encode(c2);

        // The response carrying c2 is lost. After a restart the requester retries with c1.
        // The responder must accept in recovery mode and re-issue c2 without advancing.
        ChallengeChain.Decision recovery = ChallengeChain.decide(expected, nextPrevious, c1, c3);
        check(recovery.accepted && !recovery.advance && "recovery".equals(recovery.reason), "recovery accept");
        check(ChallengeChain.encode(recovery.responseChallenge).equals(expected), "recovery re-issues the current challenge");

        // A second retry with the recovered challenge advances normally.
        ChallengeChain.Decision afterRecovery = ChallengeChain.decide(expected, nextPrevious, c2, c3);
        check(afterRecovery.accepted && afterRecovery.advance, "converged retry advances");

        // An unknown challenge is still rejected; recovery must not become a reset.
        ChallengeChain.Decision unknown = ChallengeChain.decide(expected, nextPrevious, challenge(9), c3);
        check(!unknown.accepted && "challenge_mismatch".equals(unknown.reason), "unknown challenge rejected");

        // History is bounded to three entries.
        String bounded = ChallengeChain.nextHistory("a", "b,c,d,e");
        check(bounded.equals("a,b,c,d,e"), "history is trimmed by the store, not here");
        System.out.println("PASS challenge_chain checks=" + checks);
    }
}
