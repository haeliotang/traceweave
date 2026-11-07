import com.inceptless.traceweave.TransportBinding;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

/** Host check for the P-256 channel binding, run by test_transport_binding.py. */
public final class TransportBindingHostTest {
    private static int checks = 0;
    private static final String LOCAL = "aa".repeat(32);
    private static final String PEER = "bb".repeat(32);

    private static void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("FAIL " + label);
        checks++;
    }

    private static void expectReject(String reason, byte[] envelope, String peerFp,
                                     TransportBinding.NonceCache cache, String label) {
        try {
            TransportBinding.open(envelope, peerFp, PEER, cache, System.currentTimeMillis());
            throw new IllegalStateException("FAIL accepted: " + label);
        } catch (IllegalStateException error) {
            check(reason.equals(error.getMessage()), label + " -> " + reason + " (got " + error.getMessage() + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        byte[] spki = pair.getPublic().getEncoded();
        String fingerprint = TransportBinding.fingerprint(spki);
        TransportBinding.Signer signer = message -> {
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withECDSA");
            signature.initSign(pair.getPrivate());
            signature.update(message);
            return signature.sign();
        };

        byte[] body = "{\"records\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] envelope = TransportBinding.wrap(fingerprint, PEER, spki, body, signer);
        TransportBinding.NonceCache cache = new TransportBinding.NonceCache();
        byte[] opened = TransportBinding.open(envelope, fingerprint, PEER, cache, System.currentTimeMillis());
        check(Arrays.equals(body, opened), "valid envelope opens");

        // A different device key must not pass as the paired peer.
        KeyPair other = generator.generateKeyPair();
        byte[] otherEnvelope = TransportBinding.wrap(TransportBinding.fingerprint(other.getPublic().getEncoded()),
            PEER, other.getPublic().getEncoded(), body, message -> {
                java.security.Signature signature = java.security.Signature.getInstance("SHA256withECDSA");
                signature.initSign(other.getPrivate());
                signature.update(message);
                return signature.sign();
            });
        expectReject("fingerprint_mismatch", otherEnvelope, fingerprint, cache, "other key rejected");

        // The claimed fingerprint cannot be faked by sending a different SPKI.
        expectReject("fingerprint_mismatch", envelope, TransportBinding.fingerprint(other.getPublic().getEncoded()), cache,
            "wrong expected fingerprint rejected");

        // Any change to the body invalidates the signature.
        byte[] tampered = envelope.clone();
        tampered[tampered.length - 3] ^= 0x01;
        expectReject("signature_invalid", tampered, fingerprint, cache, "tampered body rejected");

        // A nonce is accepted once.
        TransportBinding.NonceCache replayCache = new TransportBinding.NonceCache();
        TransportBinding.open(envelope, fingerprint, PEER, replayCache, System.currentTimeMillis());
        expectReject("nonce_replayed", envelope, fingerprint, replayCache, "replayed envelope rejected");

        // A valid signature for the other direction does not transfer.
        // Signed with the roles swapped: the receiver recomputes its own direction.
        byte[] reversed = TransportBinding.wrap(PEER, fingerprint, spki, body, message -> {
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withECDSA");
            signature.initSign(pair.getPrivate());
            signature.update(message);
            return signature.sign();
        });
        expectReject("signature_invalid", reversed, fingerprint, new TransportBinding.NonceCache(),
            "other direction rejected");

        // The old (unbound) payload format is refused instead of being treated as raw data.
        expectReject("not_bound", body, fingerprint, cache, "legacy payload rejected");
        expectReject("bad_envelope", new byte[]{'T', 'W', 'X', '2', 0}, fingerprint, cache, "truncated envelope rejected");

        System.out.println("PASS transport_binding checks=" + checks);
    }
}
