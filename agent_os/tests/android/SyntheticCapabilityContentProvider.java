package org.traceform.agentos;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public final class SyntheticCapabilityContentProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!AndroidRemoteStatusProvider.CONTENT_URI.equals(uri)) {
            throw new IllegalArgumentException("Unsupported synthetic provider URI");
        }
        android.content.SharedPreferences preferences = getContext().getSharedPreferences("validation", android.content.Context.MODE_PRIVATE);
        if (preferences.getBoolean("fail_next_query", false)) {
            preferences.edit().remove("fail_next_query").apply();
            throw new IllegalStateException("Synthetic provider failure requested");
        }
        MatrixCursor result = new MatrixCursor(new String[]{"value"});
        result.addRow(new Object[]{"合成跨应用状态：受限 Provider 调用成功。"});
        return result;
    }

    @Override
    public String getType(Uri uri) {
        if (!AndroidRemoteStatusProvider.CONTENT_URI.equals(uri)) return null;
        return "vnd.android.cursor.item/vnd.org.traceform.agentos.validation.status";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("The synthetic provider is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("The synthetic provider is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("The synthetic provider is read-only");
    }
}
