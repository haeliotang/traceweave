// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Proof H v2: build a tiny canonical portable-substrate state, sign it with the device's
 * hardware P-256 key, and write a signed handoff envelope for the macOS reference node.
 *
 * Synthetic data only: the frozen 0.1.5 production app has no exporter, so this activity lives in
 * the validation package. It proves the device-key binding, not real user-data export.
 */
public final class SignedHandoffActivity extends Activity {
    private static final byte[] DOMAIN = "traceweave-portable-handoff-v1".getBytes(StandardCharsets.UTF_8);
    private static final String OUTPUT = "signed-handoff.bin";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView output = new TextView(this);
        setContentView(output);
        new Thread(() -> {
            String result;
            try {
                DeviceIdentity.create();
                long signedAt = System.currentTimeMillis();
                byte[] canonical = canonicalState();
                byte[] message = ByteBuffer.allocate(DOMAIN.length + 8 + canonical.length)
                    .put(DOMAIN)
                    .putLong(signedAt)
                    .put(canonical)
                    .array();
                byte[] signature = DeviceIdentity.sign(message);
                byte[] spki = DeviceIdentity.publicKey();
                byte[] envelope = ByteBuffer.allocate(5 + 8 + 4 + spki.length + 4 + canonical.length + 4 + signature.length)
                    .put("TWPH1".getBytes(StandardCharsets.UTF_8))
                    .putLong(signedAt)
                    .putInt(spki.length).put(spki)
                    .putInt(canonical.length).put(canonical)
                    .putInt(signature.length).put(signature)
                    .array();
                File file = new File(getFilesDir(), OUTPUT);
                try (FileOutputStream stream = new FileOutputStream(file)) {
                    stream.write(envelope);
                }
                result = "PASS signed_handoff bytes=" + envelope.length
                    + " state_sha256=" + sha256(canonical)
                    + " signed_at=" + signedAt
                    + " fingerprint=" + DeviceIdentity.fingerprint();
                Log.i("AgentOsSignedHandoff", result);
            } catch (Throwable error) {
                result = "FAIL signed_handoff " + error;
                Log.e("AgentOsSignedHandoff", result);
            }
            String text = result;
            runOnUiThread(() -> output.setText(text));
        }).start();
    }

    /** Compact canonical v2 JSON; field order must match the Rust serde layout exactly. */
    private static byte[] canonicalState() {
        StringBuilder json = new StringBuilder(512);
        json.append("{\"version\":2,\"memories\":{");
        json.append("\"mem-1\":[{\"clock\":{\"android\":1},\"value\":\"synthetic handoff memory\"}]");
        json.append("},\"tasks\":{");
        json.append("\"task-1\":{\"task_id\":\"task-1\",\"goal\":\"continue on the mac\",\"status\":\"waiting_for_user\",");
        json.append("\"steps\":[{\"index\":0,\"capability\":\"calendar\",\"scope\":\"write\",\"status\":\"pending\",\"evidence\":[]}],");
        json.append("\"effects\":{},\"updated_at\":0,\"needs_authorization\":true}");
        json.append("},\"grants\":[],\"audit\":{\"entries\":[]}}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }
}
