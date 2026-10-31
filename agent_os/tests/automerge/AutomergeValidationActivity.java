package com.inceptless.traceweave;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Isolated, synthetic-only Android compatibility probe. */
public final class AutomergeValidationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                boolean editVisible = AutomergeConflictProbe.evaluate();
                Log.i("AgentOsAutomerge", "PASS synthetic conflict, resolution, replay; concurrent_edit_visible_after_delete=" + editVisible);
            } catch (Throwable error) {
                Log.e("AgentOsAutomerge", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }, "synthetic-automerge-probe").start();
    }
}
