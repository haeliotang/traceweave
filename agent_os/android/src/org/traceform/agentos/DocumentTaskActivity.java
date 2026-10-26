package org.traceform.agentos;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class DocumentTaskActivity extends Activity {
    static final String EXTRA_TASK_ID = "documentTaskId";
    private static final int CREATE_DOCUMENT = 61;
    private static final int VERIFY_DOCUMENT = 62;
    private AgentStore store;
    private String taskId;
    private EditText goal;
    private EditText markdown;
    private boolean en;
    private boolean busy;
    private final List<AgentStore.Memory> memories = new ArrayList<>();
    private final List<CheckBox> selections = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AgentStore(this);
        en = "en".equals(getSharedPreferences("agent_os_prefs", MODE_PRIVATE).getString("app_lang", "zh"));
        taskId = state == null ? getIntent().getStringExtra(EXTRA_TASK_ID) : state.getString(EXTRA_TASK_ID);
        try { show(state); } catch (Exception error) { fail(error); finish(); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(EXTRA_TASK_ID, taskId);
        if (goal != null) state.putString("goal", goal.getText().toString());
        if (markdown != null) state.putString("markdown", markdown.getText().toString());
        ArrayList<String> selected = new ArrayList<>();
        for (int index = 0; index < selections.size(); index++) if (selections.get(index).isChecked()) selected.add(memories.get(index).id);
        state.putStringArrayList("selected", selected);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (!busy) store.close();
        super.onDestroy();
    }

    private void show(Bundle state) throws Exception {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(32 + insets.getSystemWindowInsetLeft(), 48 + insets.getSystemWindowInsetTop(),
                32 + insets.getSystemWindowInsetRight(), 32 + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        label(root, en ? "Verified Markdown task" : "可验证的 Markdown 任务");
        if (taskId == null) {
            goal = new EditText(this);
            goal.setHint(en ? "Task goal" : "任务目标");
            if (state != null) goal.setText(state.getString("goal", ""));
            root.addView(goal);
            label(root, en ? "Select the memories to use. Nothing is selected automatically. This editor works locally."
                : "选择本次使用的记忆，默认均不选。此编辑器在本地生成草稿。");
            memories.clear();
            selections.clear();
            memories.addAll(store.listPlanningMemories());
            for (AgentStore.Memory memory : memories) {
                CheckBox box = new CheckBox(this);
                box.setText(memory.content + "\n" + memory.source + " · " + memory.id);
                box.setChecked(state != null && state.getStringArrayList("selected") != null
                    && state.getStringArrayList("selected").contains(memory.id));
                selections.add(box);
                root.addView(box);
            }
            button(root, en ? "Review context and preview model document request" : "审核上下文并预览模型文稿请求", () -> {
                ArrayList<String> selected = new ArrayList<>();
                for (int index = 0; index < selections.size(); index++)
                    if (selections.get(index).isChecked()) selected.add(memories.get(index).id);
                startActivity(new Intent(this, TaskReviewActivity.class).putExtra(TaskReviewActivity.EXTRA_DOCUMENT, true)
                    .putExtra(TaskReviewActivity.EXTRA_GOAL, goal.getText().toString())
                    .putStringArrayListExtra(TaskReviewActivity.EXTRA_SELECTED, selected));
                finish();
            });
            button(root, en ? "Generate local Markdown draft" : "生成本地 Markdown 草稿", () -> {
                try {
                    ArrayList<AgentStore.Memory> chosen = new ArrayList<>();
                    JSONArray sources = new JSONArray();
                    for (int index = 0; index < selections.size(); index++) if (selections.get(index).isChecked()) {
                        AgentStore.Memory memory = memories.get(index);
                        chosen.add(memory);
                        sources.put(new JSONObject().put("id", memory.id).put("kind", memory.kind)
                            .put("source", memory.source).put("sourceReference", memory.sourceReference).put("updatedAt", memory.updatedAt));
                    }
                    AgentStore.Task task = store.createDocumentTask(goal.getText().toString(),
                        DocumentAction.markdown(goal.getText().toString(), chosen), sources);
                    taskId = task.id;
                    store.savePlan(taskId, task.plan.withContext(TaskContext.selected(chosen)));
                    show(null);
                } catch (Exception error) { fail(error); }
            });
        } else {
            AgentStore.Task task = store.getTask(taskId);
            if (task == null) throw new IllegalStateException(en ? "Task no longer exists" : "任务已不存在");
            ExecutionPlan.Step step = task.plan.steps.get(0);
            JSONObject value = new JSONObject(step.observation);
            label(root, task.title);
            if (ExecutionPlan.STEP_DRAFT_READY.equals(step.status) && AgentStore.WAITING_FOR_USER.equals(task.status)) {
                if ("model".equals(value.optString("draftOrigin"))) label(root, en ? "Model proposal · review before saving"
                    : "模型建议文稿 · 请审核后再保存");
                markdown = new EditText(this);
                markdown.setMinLines(8);
                markdown.setText(state == null ? value.getString("content") : state.getString("markdown", value.getString("content")));
                root.addView(markdown);
                label(root, en ? "Review the full text, then choose a local file. The selected document will be written once and read back. Cloud-drive providers handle their own uploads."
                    : "请核对全文后选择本机保存位置。只写入选中的文件一次，再读回核验；云盘提供者自行处理上传。");
                button(root, en ? "Confirm text and choose file" : "确认内容并选择保存文件", () -> {
                    try {
                        JSONObject edited = new JSONObject(value.toString()).put("content", markdown.getText().toString());
                        Intent picker = ExternalAction.intent(DocumentAction.CAPABILITY, edited);
                        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        String attempt = store.claimExternalAction(taskId, 0, value.getString("draftId"), edited);
                        try { startActivityForResult(picker, CREATE_DOCUMENT); }
                        catch (Exception error) {
                            store.recordExternalOutcome(taskId, 0, attempt, true, "document_picker_launch_failed");
                            throw error;
                        }
                    } catch (Exception error) { fail(error); }
                });
                button(root, en ? "Cancel before saving" : "保存前取消", () -> {
                    try { store.transition(taskId, AgentStore.CANCELLED); show(null); }
                    catch (Exception error) { fail(error); }
                });
            } else {
                label(root, AgentStore.COMPLETED.equals(task.status)
                    ? (value.optBoolean("localAccess") ? (en ? "Content verified by readback" : "内容已通过读回核验")
                        : (en ? "Historical readback receipt" : "历史读回核验回执"))
                    : (en ? "Task state: " : "任务状态：") + task.status);
                label(root, "SHA-256: " + value.optString("sha256", "—"));
                if (value.has("verifiedAt")) label(root, (en ? "Last verified: " : "最近核验时间：")
                    + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(value.getLong("verifiedAt"))));
                if (!value.optBoolean("localAccess")) label(root, en ? "Historical receipt only; select a file here to verify. File access is not transferred in an archive."
                    : "仅保留历史回执；请在此设备重新选择文件核验，档案不携带文件访问权限。");
                if ((AgentStore.OUTCOME_UNKNOWN.equals(task.status) || AgentStore.COMPLETED.equals(task.status))
                        && !value.optString("attemptId").isEmpty()) {
                    if (value.optBoolean("localAccess") && value.has("uri")) {
                        button(root, en ? "Verify existing file (read only)" : "只读核验现有文件", () -> {
                            try {
                                store.selectDocumentForVerification(taskId, value.getString("uri"));
                                verifyInBackground(false);
                            } catch (Exception error) { fail(error); }
                        });
                    }
                    button(root, en ? "Select file to verify (read only)" : "重新选择文件核验（只读）", () ->
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), VERIFY_DOCUMENT));
                }
            }
        }
        button(root, en ? "Back to tasks" : "返回任务", this::finish);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CREATE_DOCUMENT && request != VERIFY_DOCUMENT) return;
        try {
            AgentStore.Task task = store.getTask(taskId);
            JSONObject value = new JSONObject(task.plan.steps.get(0).observation);
            if (result != RESULT_OK || data == null || data.getData() == null) {
                if (request == CREATE_DOCUMENT) store.cancelDocumentPicker(taskId, value.getString("attemptId"));
                show(null);
                return;
            }
            Uri uri = DocumentAction.contentUri(data.getData().toString());
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | (request == CREATE_DOCUMENT ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
            if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                try { getContentResolver().takePersistableUriPermission(uri, flags); }
                catch (SecurityException ignored) { /* A transient grant can still support this attempt. */ }
            }
            if (request == VERIFY_DOCUMENT) {
                // A picker can return a transient write grant even for a read-only request.
                revokeUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            if (request == CREATE_DOCUMENT) store.bindDocumentTarget(taskId, value.getString("attemptId"), uri.toString());
            else store.selectDocumentForVerification(taskId, uri.toString());
            verifyInBackground(request == CREATE_DOCUMENT);
        } catch (Exception error) { fail(error); try { show(null); } catch (Exception ignored) {} }
    }

    private void verifyInBackground(boolean write) {
        if (busy) return;
        busy = true;
        TextView progress = new TextView(this);
        progress.setText(en ? "Checking the selected file…" : "正在核验选中的文件…");
        progress.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(32, 48 + insets.getSystemWindowInsetTop(), 32, 32 + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(progress);
        new Thread(() -> {
            Exception failure = null;
            try {
                if (write) DocumentAction.writeOnce(getContentResolver(), store, taskId);
                else {
                    JSONObject value = new JSONObject(store.getTask(taskId).plan.steps.get(0).observation);
                    DocumentAction.verify(getContentResolver(), store, taskId, value.getString("attemptId"),
                        DocumentAction.contentUri(value.getString("uri")));
                }
            } catch (Exception error) { failure = error; }
            Exception error = failure;
            busy = false;
            runOnUiThread(() -> {
                if (isDestroyed()) { store.close(); return; }
                if (error != null) fail(error);
                try { show(null); } catch (Exception problem) { fail(problem); }
            });
        }, "verified-document").start();
    }

    private void label(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextIsSelectable(true);
        parent.addView(view);
    }

    private void button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(view -> { if (!busy) action.run(); });
        parent.addView(button);
    }

    private void fail(Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
}
