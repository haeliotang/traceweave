package com.inceptless.traceweave;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

public final class RemoteUriPickerActivity extends Activity {
    private static final int SELECT_STATUS_REQUEST = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = new Intent(AndroidRemoteStatusProvider.ACTION_SELECT)
            .setType(AndroidRemoteStatusProvider.MIME_TYPE)
            .setPackage(AndroidRemoteStatusProvider.PROVIDER_PACKAGE);
        try {
            startActivityForResult(intent, SELECT_STATUS_REQUEST);
        } catch (ActivityNotFoundException error) {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SELECT_STATUS_REQUEST || resultCode != RESULT_OK
                || data == null || !AndroidRemoteStatusProvider.CONTENT_URI.equals(data.getData())
                || (data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        Uri uri = data.getData();
        Intent result = new Intent().setData(uri);
        result.setClipData(data.getClipData() == null
            ? ClipData.newRawUri("selected status", uri) : data.getClipData());
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        setResult(RESULT_OK, result);
        finish();
    }
}
