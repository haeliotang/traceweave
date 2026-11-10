// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Test-only private provider: synthetic files and durable access counters. */
public final class DocumentFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) {
        String id = uri.getLastPathSegment();
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid fixture ID");
        return new File(getContext().getFilesDir(), "synthetic-document-" + id);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("document_fixture", 0);
        if (prefs.getBoolean("deny", false)) throw new SecurityException("Synthetic grant revoked");
        String counter = mode.contains("w") ? "writes" : "reads";
        prefs.edit().putInt(counter, prefs.getInt(counter, 0) + 1).commit();
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"_display_name", "_size"});
        cursor.addRow(new Object[]{"SYNTHETIC.md", file(uri).length()});
        return cursor;
    }
    @Override public String getType(Uri uri) { return "text/markdown"; }
    @Override public int delete(Uri uri, String selection, String[] args) { return file(uri).delete() ? 1 : 0; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
