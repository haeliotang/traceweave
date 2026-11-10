// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import org.json.JSONObject;

/** Recomputes the aggregate collection receipt from the stored frames of a finished run. Exports no frames. */
public final class SyntheticCollectionSummaryReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context context, Intent intent) {
        final String nonce = intent.getStringExtra("nonce");
        java.util.UUID.fromString(nonce);
        final PendingResult pending = goAsync();
        new Thread(new Runnable() { @Override public void run() {
            // The broadcast is released once the frame-level receipt exists; the audit stage may outlast 60 s.
            try { CollectionSummary.summarize(context, nonce, false, false, new Runnable() { @Override public void run() { pending.finish(); } }); }
            catch (Throwable error) {
                try { NativeVerificationJob.write(new File(context.getFilesDir(), "collection_probe.json"),
                    new JSONObject().put("nonce", nonce).put("passed", false).put("error", error.toString())); }
                catch (Exception ignored) { }
            } finally { try { pending.finish(); } catch (RuntimeException alreadyFinished) { } }
        }}).start();
    }
}
