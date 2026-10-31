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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 40), Ui.dp(this, 12), Ui.dp(this, 12));
        root.setBackgroundColor(Ui.background(this));
        setContentView(root);

        TextView title = Ui.title(this, T.s("localmodel.on_device_draft_model", en));
        title.setTextSize(15);
        root.addView(title);
        TextView explanation = Ui.meta(this, en
            ? "Supported: Qwen2.5-1.5B Q4_0 (~1.1 GB, faster) or MiniCPM5-2B Q4_K_M (~1.6 GB, stronger). The file stays on this device; drafts still need review and a separate save confirmation."
            : "支持两种模型：Qwen2.5-1.5B Q4_0（约 1.1 GB，更快）或 MiniCPM5-2B Q4_K_M（约 1.6 GB，更强）。文件保存在本机；草稿仍需你审核，并单独确认保存。");
        explanation.setPadding(0, Ui.dp(this, 2), 0, 0);
        root.addView(explanation);

        root.addView(Ui.section(this, T.s("localmodel.status", en)));
        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(Ui.COLOR_TEXT);
        root.addView(status);

        root.addView(Ui.section(this, T.s("localmodel.import", en)));
        select = button(root, T.s("localmodel.choose_model_file", en), () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(intent, SELECT_MODEL); }
            catch (Exception error) { status.setText(T.s("localmodel.no_file_picker_available", en)); }
        });
        select.setAllCaps(false);
        select.setTextSize(12);
        cancel = button(root, T.s("localmodel.cancel_import", en), () -> {
            if (cancelled != null) LocalModelFiles.cancelImport(cancelled);
            status.setText(T.s("localmodel.cancelling_import", en));
        });
        cancel.setAllCaps(false);
        cancel.setTextSize(12);

        root.addView(Ui.section(this, T.s("localmodel.actions", en)));
        delete = button(root, T.s("localmodel.delete_local_model", en), () -> new android.app.AlertDialog.Builder(this)
            .setMessage(T.s("localmodel.delete_the_local_model_file", en))
            .setNegativeButton(T.s("localmodel.cancel", en), null).setPositiveButton(T.s("localmodel.delete", en), (dialog, which) -> remove()).show());
        delete.setAllCaps(false);
        delete.setTextSize(12);
        Button back = button(root, T.s("localmodel.back", en), this::finish);
        back.setAllCaps(false);
        back.setTextSize(12);
        refresh();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SELECT_MODEL || result != RESULT_OK || data == null || data.getData() == null || busy) return;
        Uri uri = data.getData();
        busy = true; cancelled = new AtomicBoolean(); refresh();
        status.setText(T.s("localmodel.importing_and_verifying_the_selected_f", en));
        AtomicBoolean token = cancelled;
        new Thread(() -> {
            Exception failure = null;
            try {
                File model = modelFile(this);
                if (!model.getParentFile().isDirectory() && !model.getParentFile().mkdirs()) throw new java.io.IOException("Model directory unavailable");
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    LocalModelFiles.installSupported(input, model, token);
                }
            } catch (Exception error) { failure = error; }
            Exception resultError = failure;
            runOnUiThread(() -> {
                busy = false; cancelled = null;
                if (isFinishing() || isDestroyed()) return;
                refresh();
                status.setText(resultError == null ? (T.s("localmodel.model_imported_and_verified", en))
                    : (T.s("localmodel.import_failed_or_cancelled_the_previou", en)));
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
                if (resultError != null) status.setText(T.s("localmodel.could_not_delete_the_model", en));
            });
        }, "local-model-delete").start();
    }

    @Override protected void onDestroy() {
        if (cancelled != null) LocalModelFiles.cancelImport(cancelled);
        super.onDestroy();
    }

    private void refresh() {
        select.setEnabled(!busy); delete.setEnabled(!busy && modelFile(this).isFile()); cancel.setEnabled(busy && cancelled != null);
        LocalModelCatalog.Entry entry = null;
        try {
            if (modelFile(this).isFile() && !busy)
                entry = LocalModelFiles.verifySupported(modelFile(this), new AtomicBoolean());
        } catch (Exception ignored) {
        }
        final LocalModelCatalog.Entry verified = entry;
        status.setText(verified != null
            ? T.format("model.verified_on", en, verified.display)
            : modelFile(this).isFile() ? (T.s("localmodel.model_file_present", en))
            : (T.s("localmodel.no_model_installed", en)));
        if (!LocalModelRuntime.isAvailable()) status.append(T.s("localmodel.local_runtime_is_unavailable_on_this_d", en));
    }

    private Button button(LinearLayout root, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 40));
        params.setMargins(0, Ui.dp(this, 4), 0, 0);
        root.addView(button, params);
        return button;
    }
}
