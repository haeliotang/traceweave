package com.inceptless.traceweave;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import android.util.Base64;
import org.json.JSONObject;

/** Synthetic-only checks in an isolated app UID. */
public final class DeviceIdentityValidationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                if ("SIGN_BINDING_FIXTURE".equals(getIntent().getAction())) {
                    byte[] sender = Base64.decode(getIntent().getStringExtra("sender"), Base64.NO_WRAP);
                    byte[] receiver = Base64.decode(getIntent().getStringExtra("receiver"), Base64.NO_WRAP);
                    byte[] challenge = Base64.decode(getIntent().getStringExtra("challenge"), Base64.NO_WRAP);
                    DeviceIdentity.create();
                    JSONObject proof = new JSONObject().put("public_key", Base64.encodeToString(DeviceIdentity.publicKey(), Base64.NO_WRAP))
                        .put("signature", Base64.encodeToString(DeviceIdentity.signTransportBinding(sender, receiver, challenge), Base64.NO_WRAP));
                    try (java.io.FileOutputStream output = openFileOutput("synthetic-binding.json", MODE_PRIVATE)) {
                        output.write(proof.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    Log.i("AgentOsDeviceIdentity", "PASS synthetic_binding_fixture");
                    return;
                }
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
                byte[] sender = new byte[]{1, 2, 3}, receiver = new byte[]{4, 5, 6};
                byte[] nonce = new byte[32]; Arrays.fill(nonce, (byte) 7);
                byte[] binding = DeviceIdentity.signTransportBinding(sender, receiver, nonce);
                verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKey)));
                verifier.update(DeviceIdentity.transportBindingMessage(sender, receiver, nonce));
                if (!verifier.verify(binding)) throw new IllegalStateException("binding signature failed");
                verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKey)));
                verifier.update(DeviceIdentity.transportBindingMessage(receiver, sender, nonce));
                if (verifier.verify(binding)) throw new IllegalStateException("reversed transport identities accepted");
                nonce[0] ^= 1;
                verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKey)));
                verifier.update(DeviceIdentity.transportBindingMessage(sender, receiver, nonce));
                if (verifier.verify(binding)) throw new IllegalStateException("altered binding challenge accepted");
                verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKey)));
                verifier.update(DeviceIdentity.challengeMessage(nonce));
                if (verifier.verify(binding)) throw new IllegalStateException("binding signature accepted in another context");
                try { DeviceIdentity.signTransportBinding(new byte[0], receiver, nonce); throw new IllegalStateException("empty identity accepted"); }
                catch (IllegalArgumentException expected) { }
                try { DeviceIdentity.signTransportBinding(sender, new byte[129], nonce); throw new IllegalStateException("oversized identity accepted"); }
                catch (IllegalArgumentException expected) { }
                try { DeviceIdentity.signTransportBinding(sender, sender, nonce); throw new IllegalStateException("same identity accepted"); }
                catch (IllegalArgumentException expected) { }
                if (Arrays.equals(DeviceIdentity.transportBindingMessage(new byte[]{1}, new byte[]{2, 3}, nonce),
                        DeviceIdentity.transportBindingMessage(new byte[]{1, 2}, new byte[]{3}, nonce)))
                    throw new IllegalStateException("ambiguous identity encoding");
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
            } finally {
                finish();
            }
        }, "synthetic-device-identity").start();
    }
}
