// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class SyntheticCapabilityPickerActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(48, 48, 48, 48);

        TextView description = new TextView(this);
        description.setTextSize(20);
        description.setText("合成状态来源\n\n只向 Agent OS 分享一条固定合成状态。可取消分享，或选择一次性 Provider 错误测试。分享后将授予此 URI 临时读取权限。");
        content.addView(description);

        Button share = new Button(this);
        share.setText("分享这条合成状态");
        share.setOnClickListener(view -> shareSyntheticStatus(false));
        content.addView(share);

        Button failNextRead = new Button(this);
        failNextRead.setText("模拟一次读取错误并分享");
        failNextRead.setOnClickListener(view -> shareSyntheticStatus(true));
        content.addView(failNextRead);

        Button cancel = new Button(this);
        cancel.setText("取消分享");
        cancel.setOnClickListener(view -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        content.addView(cancel);
        setContentView(content);
    }

    private void shareSyntheticStatus(boolean failNextRead) {
        if (failNextRead) {
            getSharedPreferences("validation", MODE_PRIVATE).edit()
                .putBoolean("fail_next_query", true).apply();
        }
        Uri uri = AndroidRemoteStatusProvider.CONTENT_URI;
        Intent result = new Intent().setData(uri);
        result.setClipData(ClipData.newRawUri("synthetic status", uri));
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        setResult(RESULT_OK, result);
        finish();
    }
}
