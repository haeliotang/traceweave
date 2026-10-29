package org.traceform.agentos;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Locale;

/** Local device identity only; this does not establish trust or pair devices. */
final class DeviceIdentity {
    static final String ALIAS = "org.traceform.agentos.device-identity.v1";
    private static final byte[] DOMAIN = "traceform-device-identity-challenge-v1\0".getBytes(StandardCharsets.UTF_8);

    private DeviceIdentity() {}

    private static synchronized KeyStore keys(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            if (!create) throw new IllegalStateException("Device identity is not initialized");
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
        }
        if (store.getCertificate(ALIAS) == null || !(store.getKey(ALIAS, null) instanceof PrivateKey))
            throw new IllegalStateException("Device identity unavailable");
        return store;
    }

    static void create() throws Exception { keys(true); }

    static byte[] publicKey() throws Exception { return keys(false).getCertificate(ALIAS).getPublicKey().getEncoded(); }

    static String fingerprint() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey());
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    static byte[] challengeMessage(byte[] challenge) {
        if (challenge == null || challenge.length != 32) throw new IllegalArgumentException("A 32-byte challenge is required");
        byte[] message = new byte[DOMAIN.length + challenge.length];
        System.arraycopy(DOMAIN, 0, message, 0, DOMAIN.length);
        System.arraycopy(challenge, 0, message, DOMAIN.length, challenge.length);
        return message;
    }

    static byte[] signChallenge(byte[] challenge) throws Exception {
        byte[] message = challengeMessage(challenge);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign((PrivateKey) keys(false).getKey(ALIAS, null));
        signature.update(message);
        return signature.sign();
    }
}
