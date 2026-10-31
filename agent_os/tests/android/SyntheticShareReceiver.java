package com.inceptless.traceweave;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Test-only receiver that records a shared text payload and exits. */
public final class SyntheticShareReceiver extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        String text = intent == null ? null : intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) text = intent == null ? null : intent.getStringExtra(Intent.EXTRA_SUBJECT);
        try {
            File file = new File(getFilesDir(), "share-received.txt");
            if (text != null) {
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(text.getBytes(StandardCharsets.UTF_8));
                }
            }
            Log.i("AgentOsShareReceiver", "RECEIVED " + (text == null ? -1 : text.length()) + " chars");
        } catch (Exception error) {
            Log.e("AgentOsShareReceiver", "FAIL", error);
        }
        finish();
    }
}
