package com.inceptless.traceweave;

import android.app.Activity;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;

/** Debug-only probe with a fixed public model and synthetic prompt. */
public final class LocalModelProbe extends Activity {
    private static final String MODEL_SHA = "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed";
    static { System.loadLibrary("traceweave_local_model"); }
    static native void prepare();
    static native void cancel();
    static native byte[] generate(String path, byte[] prompt, int maxTokens, String grammar);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                File model = new File(getFilesDir(), "synthetic-evaluation-model.gguf");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileInputStream input = new FileInputStream(model)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
                StringBuilder hash = new StringBuilder();
                for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                if (!MODEL_SHA.equals(hash.toString())) throw new SecurityException("Model digest mismatch");
                byte[] prompt = "List three items for a synthetic camping checklist.".getBytes(StandardCharsets.UTF_8);
                prepare(); cancel();
                try { generate(model.getPath(), prompt, 32, null); throw new IllegalStateException("Pre-start cancellation ignored"); }
                catch (IllegalStateException expected) {
                    if (!expected.getMessage().contains("cancelled")) throw expected;
                }
                prepare();
                try { generate(model.getPath(), new byte[4097], 32, null); throw new IllegalStateException("Oversized input accepted"); }
                catch (IllegalStateException expected) {
                    if (!expected.getMessage().contains("limit")) throw expected;
                }
                prepare();
                long started = android.os.SystemClock.elapsedRealtime();
                byte[] raw = generate(model.getPath(), prompt, 48, null);
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
                if (text.trim().isEmpty() || text.length() > 32768) throw new IllegalStateException("Invalid generated text");
                long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                prepare();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread worker = new Thread(() -> {
                    try { generate(model.getPath(), prompt, 128, null); }
                    catch (Throwable error) { failure.set(error); }
                });
                worker.start(); Thread.sleep(40); cancel(); worker.join(15000);
                if (worker.isAlive() || failure.get() == null || failure.get().getMessage() == null
                        || !failure.get().getMessage().contains("cancelled"))
                    throw new IllegalStateException("In-flight cancellation not confirmed");
                android.util.Log.i("AgentOsLocalModelProbe", "PASS real_model_utf8_and_cancellation elapsed_ms=" + elapsed
                    + " output_bytes=" + raw.length);
            } catch (Throwable error) {
                android.util.Log.e("AgentOsLocalModelProbe", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }, "synthetic-local-model").start();
    }
}
