package org.traceform.agentos;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Explicit context selection and an exact, single-request cloud preview. */
public final class TaskReviewActivity extends Activity {
    static final String EXTRA_GOAL = "reviewTaskGoal";
    static final String EXTRA_DOCUMENT = "reviewDocumentGoal";
    static final String EXTRA_SELECTED = "reviewSelectedMemories";
    static final String EXTRA_TASK_ID = "reviewTaskId";
    private AgentStore store;
    private boolean en;
    private boolean busy;
    private boolean document;
    private EditText goal;
    private String taskId;
    private CloudRequest pending;
    private MemorySelectionView memorySelection;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AgentStore(this);
        en = "en".equals(getSharedPreferences("agent_os_prefs", MODE_PRIVATE).getString("app_lang", "zh"));
        document = state == null ? getIntent().getBooleanExtra(EXTRA_DOCUMENT, false) : state.getBoolean("document", false);
        taskId = state == null ? getIntent().getStringExtra(EXTRA_TASK_ID) : state.getString("taskId");
        try {
            if (state == null && taskId != null) {
                AgentStore.Task task = store.getTask(taskId);
                if (task == null || !AgentStore.QUEUED.equals(task.status)) throw new IllegalStateException("Task is no longer awaiting planning");
                state = new Bundle(); state.putString("goal", task.title);
                ArrayList<String> ids = new ArrayList<>();
                for (AgentStore.Memory memory : TaskContext.memories(task.plan)) ids.add(memory.id);
                state.putStringArrayList("selected", ids);
                document = TaskContext.isDocument(task.plan);
            }
            selection(state);
        } catch (Exception error) { fail(error); finish(); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("taskId", taskId);
        state.putBoolean("document", document);
        state.putString("goal", goal.getText().toString());
        state.putStringArrayList("selected", memorySelection.selectedIds());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (pending != null) pending.revoke();
        if (!busy) store.close();
        super.onDestroy();
    }

    private LinearLayout page(String heading) {
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
        text(root, heading);
        return root;
    }

    private void selection(Bundle state) throws Exception {
        LinearLayout root = page(en ? "Review task context" : "审核本次任务上下文");
        goal = new EditText(this);
        goal.setHint(en ? "Task goal" : "任务目标");
        goal.setText(state == null ? getIntent().getStringExtra(EXTRA_GOAL) : state.getString("goal", ""));
        root.addView(goal);
        text(root, en ? "Select memories for this task. Nothing is selected automatically. Stored snapshots remain with its history."
            : "选择本次任务允许使用的记忆，默认不选；选定快照随任务历史保留。");
        ArrayList<String> ids = state == null ? getIntent().getStringArrayListExtra(EXTRA_SELECTED) : state.getStringArrayList("selected");
        memorySelection = new MemorySelectionView(this, store, goal, en, ids);
        root.addView(memorySelection);
        button(root, en ? "Plan locally · keep this task local" : "本地规划 · 此任务保持本地", this::planLocally);
        button(root, en ? "Preview one cloud planning request" : "预览本次云端规划请求", () -> {
            try {
                AgentStore.Task task = prepareTask(false);
                CloudModelConfig config = CloudModelConfig.load(this);
                pending = document ? new CloudModelClient().prepareDocument(task, config)
                    : new CloudModelClient().preparePlan(task, new CapabilityBroker(this, store).listAvailableCapabilities(task.id), config);
                cloudPreview();
            } catch (Exception error) { fail(error); }
        });
        button(root, en ? "Back" : "返回", this::finish);
    }

    private AgentStore.Task prepareTask(boolean localOnly) throws Exception {
        String title = goal.getText().toString().trim();
        if (title.isEmpty() || title.length() > 2000) throw new IllegalArgumentException(en ? "Enter a task goal (up to 2000 characters)" : "请输入 1–2000 字的任务目标");
        List<AgentStore.Memory> selected = memorySelection.chosen();
        String snapshot = TaskContext.selected(selected, localOnly);
        AgentStore.Task existing = taskId == null ? null : store.getTask(taskId);
        org.json.JSONObject previousContext = existing == null || existing.plan == null || existing.plan.context.isEmpty()
            ? new org.json.JSONObject() : new org.json.JSONObject(existing.plan.context);
        if (existing == null || !title.equals(existing.title)) {
            existing = store.createTask(title);
            taskId = existing.id;
        }
        if (!AgentStore.QUEUED.equals(existing.status)) throw new IllegalStateException(en ? "Task already started" : "任务已开始，不能替换上下文");
        org.json.JSONObject context = new org.json.JSONObject(snapshot);
        if (document) context.put("intent", "verified_document");
        if (previousContext.has("workflow")) context.put("workflow", previousContext.getJSONObject("workflow"));
        snapshot = context.toString();
        ExecutionPlan placeholder = new ExecutionPlan(taskId, title, java.util.Collections.emptyList(), System.currentTimeMillis(), 0)
            .withContext(snapshot);
        store.savePlan(taskId, placeholder);
        return store.getTask(taskId);
    }

    private void planLocally() {
        try {
            if (pending != null) pending.revoke();
            AgentStore.Task task = prepareTask(true);
            if (document) {
                store.prepareReviewedDocument(task.id, DocumentAction.markdown(task.title, TaskContext.memories(task.plan)),
                    TaskContext.fingerprint(task), "");
                openDocument();
                return;
            }
            ExecutionPlan plan = new HybridReasoningEngine(this, store).plan(task.id, task.title,
                new CapabilityBroker(this, store).listAvailableCapabilities(task.id), TaskContext.memories(task.plan));
            store.savePlan(task.id, plan.withContext(task.plan.context));
            finish();
        } catch (Exception error) { fail(error); }
    }

    private void cloudPreview() {
        LinearLayout root = page(en ? "Exact request preview" : "本次实际请求预览");
        text(root, (en ? "Endpoint: " : "目标端点：") + pending.endpoint);
        text(root, (en ? "Model: " : "模型：") + pending.config.modelName);
        text(root, en ? "The request below includes the goal, selected memories and capability descriptions. Pattern redaction cannot guarantee removal of all sensitive information. Approval is for this planning request only; later observations stay local."
            : "下方为将发送的目标、所选记忆及能力描述。规则脱敏不能保证移除所有敏感信息。授权仅限本次规划，后续步骤观察保持本地。");
        text(root, pending.body);
        if (document) text(root, en ? "The model proposes text only. Review and edit its draft before separately confirming a file save."
            : "模型仅提出文稿。返回后先审核、编辑全文，再单独确认文件保存；发送请求不会授权写文件。");
        button(root, en ? "Allow this planning request once" : "仅允许本次规划发送", () -> {
            try {
                CloudRequest request = pending;
                CloudModelConfig current = CloudModelConfig.load(this);
                if (!current.isConfigured() || !request.endpoint.equals(CloudModelClient.normalizeEndpointUrl(current.endpointUrl))
                        || !request.config.modelName.equals(current.modelName) || !request.config.apiKey.equals(current.apiKey)) {
                    request.revoke();
                    throw new SecurityException(en ? "Cloud settings changed; review a new request" : "云端设置已改变，请重新预览请求");
                }
                request.approveOnce();
                busy = true;
                LinearLayout progress = page(en ? "Sending the approved request…" : "正在发送已授权的本次请求…");
                text(progress, en ? "Cancellation discards the result; a request already sent cannot be recalled."
                    : "取消将丢弃返回结果；已发出的请求无法收回。");
                Button cancel = new Button(this);
                cancel.setText(en ? "Cancel task and discard response" : "取消任务并丢弃返回结果");
                cancel.setOnClickListener(view -> cancelPlanning());
                progress.addView(cancel);
                new Thread(() -> {
                    Exception failure = null;
                    try {
                        if (document) new CloudModelClient().requestApprovedDocument(request, store);
                        else new CloudModelClient().requestApprovedPlan(request, store);
                    } catch (Exception error) { failure = error; }
                    Exception error = failure;
                    busy = false;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) { store.close(); return; }
                        if (error == null) {
                            if (document) openDocument();
                            else finish();
                        }
                        else {
                            fail(error);
                            LinearLayout fallback = page(en ? "Request did not produce a usable plan" : "本次请求未获得可用计划");
                            text(fallback, en ? "No automatic retry will be sent. You can plan locally or review a new request."
                                : "不会自动重发。可以改为本地规划，或返回重新预览新请求。");
                            button(fallback, en ? "Plan locally" : "改为本地规划", this::planLocally);
                            button(fallback, en ? "Back" : "返回", this::finish);
                        }
                    });
                }, "consented-cloud-planning").start();
            } catch (Exception error) { busy = false; fail(error); }
        });
        button(root, en ? "Keep local · do not send" : "保持本地 · 不发送", this::planLocally);
        button(root, en ? "Revoke preview and edit context" : "撤销预览并修改上下文", () -> {
            try {
                pending.revoke();
                Bundle selected = new Bundle();
                selected.putString("goal", goal.getText().toString());
                selected.putStringArrayList("selected", memorySelection.selectedIds());
                selection(selected);
            } catch (Exception error) { fail(error); }
        });
    }

    private void openDocument() {
        startActivity(new Intent(this, DocumentTaskActivity.class).putExtra(DocumentTaskActivity.EXTRA_TASK_ID, taskId));
        finish();
    }

    private void cancelPlanning() {
        try {
            if (pending != null) cancelRequest(store, pending);
            finish();
        } catch (Exception error) { fail(error); }
    }

    static void cancelRequest(AgentStore store, CloudRequest request) throws Exception {
        request.revoke();
        AgentStore.Task task = store.getTask(request.taskId);
        if (task == null || !request.contextFingerprint.equals(TaskContext.fingerprint(task))) return;
        boolean readyDocument = request.documentDraft && AgentStore.WAITING_FOR_USER.equals(task.status)
            && task.plan != null && task.plan.totalSteps() == 1
            && DocumentAction.CAPABILITY.equals(task.plan.steps.get(0).capability)
            && ExecutionPlan.STEP_DRAFT_READY.equals(task.plan.steps.get(0).status)
            && request.sha256.equals(new org.json.JSONObject(task.plan.steps.get(0).observation).optString("requestSha256"));
        if (AgentStore.QUEUED.equals(task.status) || readyDocument) store.transition(task.id, AgentStore.CANCELLED);
    }

    @Override public void onBackPressed() {
        if (busy) cancelPlanning();
        else super.onBackPressed();
    }

    private void text(LinearLayout root, String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextIsSelectable(true);
        root.addView(view);
    }
    private void button(LinearLayout root, String value, Runnable action) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setOnClickListener(view -> { if (!busy) action.run(); });
        root.addView(button);
    }
    private void fail(Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
}
