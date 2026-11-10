// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.json.JSONObject;

final class NativeTrialDelivery {
    static void complete(File path, String id, JSONObject receipt) throws Exception {
        boolean delivered = Boolean.TRUE.equals(receipt.opt("delivered"));
        boolean eligible = Boolean.TRUE.equals(receipt.opt("eligible_for_outcome"));
        String status = !delivered ? "delivery_failed" : eligible ? "observing" : "censored_delivery";
        try (SQLiteDatabase database=SQLiteDatabase.openDatabase(path.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            database.execSQL("UPDATE trials SET status=?,receipt=? WHERE id=? AND status='queued'",
                new Object[]{status,receipt.toString(),id});
        }
    }
    static void censorStop(File path) throws Exception {
        if (!path.exists()) return;
        try (SQLiteDatabase database=SQLiteDatabase.openDatabase(path.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            JSONObject outcome=new JSONObject().put("status","censored").put("reason","collection_stopped");
            database.execSQL("UPDATE trials SET status='censored',outcome=? WHERE status IN ('queued','observing')",
                new Object[]{outcome.toString()});
        }
    }
}
