// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.util.Log;
import java.io.File;

/** Reports integrity only, without logging database contents. Isolated test package only. */
public final class EdgeRecoveryProbe extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        check(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        check(intent);
    }

    private void check(Intent intent) {
        boolean expectDebug = "debug".equals(intent.getStringExtra("expected"));
        boolean debug = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        // Android can restore the old activity intent immediately after an upgrade.
        if (debug != expectDebug) return;
        new Thread(() -> {
            try {
                File file = getDatabasePath("edge.sqlite");
                if (!file.isFile() || file.length() == 0) throw new IllegalStateException("Database absent");
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                     Cursor result = db.rawQuery("PRAGMA integrity_check", null)) {
                    if (!result.moveToFirst() || !"ok".equals(result.getString(0)) || result.moveToNext())
                        throw new IllegalStateException("Database integrity check failed");
                }
                Log.i("EdgeRecoveryProbe", "PASS database_integrity " + (debug ? "debug" : "nondebug"));
            } catch (Throwable error) {
                Log.e("EdgeRecoveryProbe", "FAIL " + error.getClass().getSimpleName());
            }
        }, "edge-backup-integrity").start();
    }
}
