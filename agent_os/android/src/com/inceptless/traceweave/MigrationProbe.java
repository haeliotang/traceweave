// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;

/**
 * Track B acceptance probe: builds a synthetic schema-20 database, then opens it through the
 * version-21 helper so the 20 -> 21 migration runs on a real Android device. It never touches the
 * frozen 0.1.5 app or its data directory.
 */
final class MigrationProbe {
    private MigrationProbe() {}

    static String run(Context context) {
        try {
            String name = "agent_os.db";
            context.deleteDatabase(name);
            File path = context.getDatabasePath(name);
            File parent = path.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return "FAIL schema_migration reason=cannot-create-dir";
            }
            SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(path, null);
            legacy.execSQL("CREATE TABLE migration_probe (marker TEXT NOT NULL)");
            legacy.execSQL("INSERT INTO migration_probe VALUES ('schema20')");
            legacy.setVersion(20);
            legacy.close();

            try (AgentStore store = new AgentStore(context)) {
                store.exportPolicies(); // forces getReadableDatabase -> onUpgrade(20, 21)
            }

            SQLiteDatabase check = SQLiteDatabase.openDatabase(path.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try {
                int version = check.getVersion();
                String marker = null;
                try (Cursor cursor = check.rawQuery("SELECT marker FROM migration_probe", null)) {
                    if (cursor.moveToFirst()) marker = cursor.getString(0);
                }
                boolean policies = false;
                try (Cursor cursor = check.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='policies'", null)) {
                    policies = cursor.moveToFirst();
                }
                if (version != 21 || !"schema20".equals(marker) || !policies) {
                    return "FAIL schema_migration version=" + version + " marker=" + marker + " policies=" + policies;
                }
                return "PASS schema_migration from=20 to=21 version=21 marker=schema20 policies=true";
            } finally {
                check.close();
            }
        } catch (Throwable error) {
            return "FAIL schema_migration reason=" + error;
        }
    }
}
