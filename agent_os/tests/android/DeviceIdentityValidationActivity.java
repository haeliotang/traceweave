package org.traceform.agentos;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/** Synthetic-only checks in an isolated app UID. */
public final class DeviceIdentityValidationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                String previous = getSharedPreferences("identity_validation", 0).getString("fingerprint", "");
                if (previous.isEmpty()) {
                    try { DeviceIdentity.publicKey(); throw new IllegalStateException("identity created without request"); }
                    catch (IllegalStateException expected) {
                        if (!"Device identity is not initialized".equals(expected.getMessage())) throw expected;
                    }
                }
                DeviceIdentity.create();
                byte[] publicKey = DeviceIdentity.publicKey();
                byte[] challenge = new byte[32]; Arrays.fill(challenge, (byte) 42);
                byte[] signed = DeviceIdentity.signChallenge(challenge);
                Signature verifier = Signature.getInstance("SHA256withECDSA");
                verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKey)));
                verifier.update(DeviceIdentity.challengeMessage(challenge));
                if (!verifier.verify(signed)) throw new IllegalStateException("signature failed");
                challenge[0] ^= 1;
                verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKey)));
                verifier.update(DeviceIdentity.challengeMessage(challenge));
                if (verifier.verify(signed)) throw new IllegalStateException("altered challenge accepted");
                if (!Arrays.equals(publicKey, DeviceIdentity.publicKey())) throw new IllegalStateException("identity changed");
                java.security.KeyStore keys = java.security.KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
                if (keys.getKey(DeviceIdentity.ALIAS, null).getEncoded() != null) throw new IllegalStateException("private key exportable");
                try { DeviceIdentity.signChallenge(new byte[31]); throw new IllegalStateException("short challenge accepted"); }
                catch (IllegalArgumentException expected) { }
                String fingerprint = DeviceIdentity.fingerprint();
                if (!previous.isEmpty() && !previous.equals(fingerprint)) throw new IllegalStateException("identity changed after restart");
                getSharedPreferences("identity_validation", 0).edit().putString("fingerprint", fingerprint).commit();
                Log.i("AgentOsDeviceIdentity", "PASS synthetic_identity_sign_verify_restart");
            } catch (Throwable error) {
                Log.e("AgentOsDeviceIdentity", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
            finish();
        }, "synthetic-device-identity").start();
    }
}
