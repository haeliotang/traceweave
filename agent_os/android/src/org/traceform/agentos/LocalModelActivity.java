package org.traceform.agentos;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-selected public model; never downloads a file or accesses user memories. */
public final class LocalModelActivity extends Activity {
    private static final int SELECT_MODEL = 71;
    private boolean en;
    private boolean busy;
    private AtomicBoolean cancelled;
    private TextView status;
    private Button select;
    private Button delete;
    private Button cancel;

    static File modelFile(Context context) {
        return new File(new File(context.getFilesDir(), "local_model"), "qwen-1.5b-q4.gguf");
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        en = "en".equals(getSharedPreferences("agent_os_prefs", 0).getString("app_lang", "zh"));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32, 48, 32, 32);
        setContentView(root);
        TextView title = new TextView(this); title.setText(en ? "On-device draft model" : "端侧文稿模型"); root.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText(en ? "Choose Qwen2.5-1.5B-Instruct Q4_0 GGUF (about 1 GB). It remains on this device. Drafts require your review and separate file confirmation."
            : "选择 Qwen2.5-1.5B-Instruct Q4_0 GGUF 模型文件（约 1 GB），文件保存在本机。生成的文稿需要你审核，并单独确认保存。");
        root.addView(explanation);
        status = new TextView(this); root.addView(status);
        select = button(root, en ? "Choose model file" : "选择模型文件", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(intent, SELECT_MODEL); }
            catch (Exception error) { status.setText(en ? "No file picker available" : "无法打开文件选择器"); }
        });
        delete = button(root, en ? "Delete local model" : "删除本地模型", () -> new android.app.AlertDialog.Builder(this)
            .setMessage(en ? "Delete the local model file?" : "删除本机模型文件？")
            .setNegativeButton(en ? "Cancel" : "取消", null).setPositiveButton(en ? "Delete" : "删除", (dialog, which) -> remove()).show());
        cancel = button(root, en ? "Cancel import" : "取消导入", () -> {
            if (cancelled != null) LocalModelFiles.cancelImport(cancelled);
            status.setText(en ? "Cancelling import…" : "正在取消导入…");
        });
        button(root, en ? "Back" : "返回", this::finish);
        refresh();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SELECT_MODEL || result != RESULT_OK || data == null || data.getData() == null || busy) return;
        Uri uri = data.getData();
        busy = true; cancelled = new AtomicBoolean(); refresh();
        status.setText(en ? "Importing and verifying the selected file…" : "正在导入并核验所选文件…");
        AtomicBoolean token = cancelled;
        new Thread(() -> {
            Exception failure = null;
            try {
                File model = modelFile(this);
                if (!model.getParentFile().isDirectory() && !model.getParentFile().mkdirs()) throw new java.io.IOException("Model directory unavailable");
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    LocalModelFiles.installQwen15(input, model, token);
                }
            } catch (Exception error) { failure = error; }
            Exception resultError = failure;
            runOnUiThread(() -> {
                busy = false; cancelled = null;
                if (isFinishing() || isDestroyed()) return;
                refresh();
                status.setText(resultError == null ? (en ? "Model imported and verified." : "模型已导入并核验。")
                    : (en ? "Import failed or cancelled; the previous model was retained." : "导入失败或已取消；原有模型未被替换。"));
            });
        }, "local-model-import").start();
    }

    private void remove() {
        busy = true; refresh();
        new Thread(() -> {
            Exception failure = null;
            try { LocalModelFiles.deleteModel(modelFile(this)); } catch (Exception error) { failure = error; }
            Exception resultError = failure;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                refresh();
                if (resultError != null) status.setText(en ? "Could not delete the model." : "无法删除模型。");
            });
        }, "local-model-delete").start();
    }

    @Override protected void onDestroy() {
        if (cancelled != null) LocalModelFiles.cancelImport(cancelled);
        super.onDestroy();
    }

    private void refresh() {
        select.setEnabled(!busy); delete.setEnabled(!busy && modelFile(this).isFile()); cancel.setEnabled(busy && cancelled != null);
        status.setText(modelFile(this).isFile() ? (en ? "Model file present." : "本机已有模型文件。")
            : (en ? "No model installed." : "尚未导入模型。"));
        if (!LocalModelRuntime.isAvailable()) status.append(en ? " Local runtime is unavailable on this device or build." : " 此设备或构建上的端侧运行库不可用。");
    }

    private Button button(LinearLayout root, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        button.setOnClickListener(view -> action.run()); root.addView(button); return button;
    }
}
