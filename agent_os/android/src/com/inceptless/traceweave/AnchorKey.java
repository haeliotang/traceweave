package com.inceptless.traceweave;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Device-held HMAC key for audit anchors; non-exportable when the Keystore provides it. */
final class AnchorKey {
    private static final String ALIAS = "traceweave-audit-anchor-v1";
    private static final String PROVIDER = "AndroidKeyStore";

    private AnchorKey() {}

    /** Keystore keys are not exportable, so callers sign with the key itself. */
    static SecretKey get() {
        try {
            KeyStore store = KeyStore.getInstance(PROVIDER);
            store.load(null);
            if (!store.containsAlias(ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER);
                generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build());
                generator.generateKey();
            }
            return (SecretKey) store.getKey(ALIAS, null);
        } catch (Exception error) {
            android.util.Log.w("AgentOsAnchor", "anchor key unavailable: " + error);
            return null;
        }
    }
}
