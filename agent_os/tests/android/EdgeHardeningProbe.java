// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.util.Log;

/** Synthetic database fixture for a debug-to-non-debug in-place upgrade. */
public final class EdgeHardeningProbe extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                boolean debug = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                boolean create = "create".equals(getIntent().getStringExtra("mode"));
                if (create != debug) throw new IllegalStateException("Unexpected debug state");
                try (SQLiteDatabase db = openOrCreateDatabase("synthetic.db", 0, null)) {
                    if (create) {
                        db.execSQL("CREATE TABLE fixture (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
                        db.execSQL("INSERT INTO fixture VALUES (1, 'synthetic retained record')");
                        getSharedPreferences("fixture", 0).edit().putString("value", "synthetic retained preference").commit();
                    } else {
                        try (Cursor c = db.rawQuery("SELECT value FROM fixture WHERE id = 1", null)) {
                            if (!c.moveToFirst() || !"synthetic retained record".equals(c.getString(0)) || c.moveToNext())
                                throw new IllegalStateException("Synthetic database was not retained");
                        }
                        if (!"synthetic retained preference".equals(getSharedPreferences("fixture", 0).getString("value", "")))
                            throw new IllegalStateException("Synthetic preference was not retained");
                    }
                }
                Log.i("EdgeHardeningProbe", "PASS " + (create ? "created_debug_fixture" : "retained_after_nondebug_upgrade"));
            } catch (Throwable error) {
                Log.e("EdgeHardeningProbe", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }, "synthetic-edge-upgrade").start();
    }
}
