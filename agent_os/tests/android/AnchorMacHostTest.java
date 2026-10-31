import com.inceptless.traceweave.AnchorMac;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Host check for the anchor MAC rules, run by test_anchor_mac.py. */
public final class AnchorMacHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("FAIL " + message);
        checks++;
    }

    public static void main(String[] args) {
        javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(
            "device-held-key".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        javax.crypto.SecretKey other = new javax.crypto.spec.SecretKeySpec(
            "attacker-key".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        String hash = "abc123";
        String mac = AnchorMac.compute(key, 0, hash, 1000);
        check(mac.length() == 64, "mac is a sha256-sized hex string");
        check(mac.equals(AnchorMac.compute(key, 0, hash, 1000)), "mac is deterministic");
        check(!mac.equals(AnchorMac.compute(key, 1, hash, 1000)), "index is covered");
        check(!mac.equals(AnchorMac.compute(key, 0, "abc124", 1000)), "hash is covered");
        check(!mac.equals(AnchorMac.compute(key, 0, hash, 1001)), "timestamp is covered");
        check(mac.equals(AnchorMac.compute(key, 0, hash, 1000)), "same inputs give the same mac");
        check(!mac.equals(AnchorMac.compute(other, 0, hash, 1000)), "another key cannot reproduce it");

        List<AnchorMac.Entry> anchors = new ArrayList<>();
        anchors.add(new AnchorMac.Entry(0, hash, 1000, mac));
        check(AnchorMac.allValid(key, anchors), "honest anchor verifies");
        List<AnchorMac.Entry> forged = new ArrayList<>();
        forged.add(new AnchorMac.Entry(0, "rewritten", 1000, mac));
        check(!AnchorMac.allValid(key, forged), "rewritten hash no longer verifies");

        List<String> chain = new ArrayList<>(Arrays.asList(hash, "def456"));
        check(AnchorMac.firstMismatched(anchors, chain) == -1, "anchor matches the chain row");
        List<String> rewrittenChain = new ArrayList<>(Arrays.asList("zzz", "def456"));
        check(AnchorMac.firstMismatched(anchors, rewrittenChain) == 0, "rewritten chain is detected");
        System.out.println("PASS anchor_mac checks=" + checks);
    }
}
