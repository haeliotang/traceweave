package com.inceptless.traceweave;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Synthetic fixtures and a fixed public model, only in the isolated test app. */
public final class LocalModelFilesValidationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                LocalModelFilesHostTest.main(new String[]{getCacheDir().getPath()});
                if ("PUBLIC_MODEL".equals(getIntent().getAction())) {
                    File source = new File(getFilesDir(), "synthetic-evaluation-model.gguf");
                    File installed = new File(getFilesDir(), "installed-model.gguf");
                    try (FileInputStream input = new FileInputStream(source)) {
                        LocalModelFiles.installQwen15(input, installed, new AtomicBoolean());
                    }
                    if (!LocalModelFiles.verifyQwen15(installed, new AtomicBoolean()))
                        throw new IllegalStateException("Public model readback did not verify");
                }
                Log.i("AgentOsModelFiles", "PASS model import checks mode=" + getIntent().getAction());
            } catch (Throwable error) {
                Log.e("AgentOsModelFiles", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }, "synthetic-model-import").start();
    }
}
