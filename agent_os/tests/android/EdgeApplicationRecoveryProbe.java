// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.util.Log;

/** Exercises copied Edge code with only synthetic state in an isolated package. */
public final class EdgeApplicationRecoveryProbe extends Activity {
    @Override public void onCreate(Bundle state) { super.onCreate(state); check(getIntent()); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); check(intent); }
    private void check(Intent intent) {
        boolean debug = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String mode = intent.getStringExtra("mode");
        if (!(debug && ("create".equals(mode) || "verify-debug".equals(mode)))
                && !(!debug && "verify-nondebug".equals(mode))) return;
        new Thread(() -> {
            try {
                try (SQLiteDatabase db = openOrCreateDatabase("fixture.sqlite", 0, null)) {
                    if ("create".equals(mode)) {
                        db.execSQL("CREATE TABLE fixture (value TEXT NOT NULL)");
                        db.execSQL("INSERT INTO fixture VALUES ('synthetic value')");
                        if (!getSharedPreferences("fixture", 0).edit().putString("value", "synthetic preference").commit())
                            throw new IllegalStateException("fixture preference write failed");
                    } else {
                        try (Cursor rows = db.rawQuery("SELECT value FROM fixture", null)) {
                            if (!rows.moveToFirst() || !"synthetic value".equals(rows.getString(0)) || rows.moveToNext())
                                throw new IllegalStateException("fixture database changed");
                        }
                        if (!"synthetic preference".equals(getSharedPreferences("fixture", 0).getString("value", "")))
                            throw new IllegalStateException("fixture preference changed");
                    }
                }
                ModelEngine model = new ModelEngine(this);
                if (!NativeRegistryFixture.verify(model, getCacheDir()).getBoolean("passed"))
                    throw new IllegalStateException("synthetic model registry test failed");
                if (!HistoryForecastFixture.verify(model).getBoolean("passed"))
                    throw new IllegalStateException("synthetic forecast test failed");
                Log.i("EdgeAppRecoveryProbe", "PASS " + mode);
            } catch (Throwable error) {
                Log.e("EdgeAppRecoveryProbe", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }, "edge-app-synthetic-recovery").start();
    }
}
