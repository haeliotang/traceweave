package org.traceform.agentos;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
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
    private AgentStore store;
    private boolean en;
    private boolean busy;
    private EditText goal;
    private String taskId;
    private CloudRequest pending;
    private final List<AgentStore.Memory> memories = new ArrayList<>();
    private final List<CheckBox> choices = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AgentStore(this);
        en = "en".equals(getSharedPreferences("agent_os_prefs", MODE_PRIVATE).getString("app_lang", "zh"));
        taskId = state == null ? null : state.getString("taskId");
        try { selection(state); } catch (Exception error) { fail(error); finish(); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("taskId", taskId);
        state.putString("goal", goal.getText().toString());
        ArrayList<String> selected = new ArrayList<>();
        for (int index = 0; index < choices.size(); index++) if (choices.get(index).isChecked()) selected.add(memories.get(index).id);
        state.putStringArrayList("selected", selected);
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
        memories.clear();
        choices.clear();
        memories.addAll(store.listPlanningMemories());
        for (AgentStore.Memory memory : memories) {
            CheckBox box = new CheckBox(this);
            box.setText(memory.content + "\n" + memory.source + " · " + memory.id);
            box.setChecked(state != null && state.getStringArrayList("selected") != null
                && state.getStringArrayList("selected").contains(memory.id));
            choices.add(box);
            root.addView(box);
        }
        button(root, en ? "Plan locally · keep this task local" : "本地规划 · 此任务保持本地", this::planLocally);
        button(root, en ? "Preview one cloud planning request" : "预览本次云端规划请求", () -> {
            try {
                AgentStore.Task task = prepareTask(false);
                CloudModelConfig config = CloudModelConfig.load(this);
                pending = new CloudModelClient().preparePlan(task, new CapabilityBroker(this, store).listAvailableCapabilities(task.id), config);
                cloudPreview();
            } catch (Exception error) { fail(error); }
        });
        button(root, en ? "Back" : "返回", this::finish);
    }

    private AgentStore.Task prepareTask(boolean localOnly) throws Exception {
        String title = goal.getText().toString().trim();
        if (title.isEmpty() || title.length() > 2000) throw new IllegalArgumentException(en ? "Enter a task goal (up to 2000 characters)" : "请输入 1–2000 字的任务目标");
        ArrayList<AgentStore.Memory> selected = new ArrayList<>();
        for (int index = 0; index < choices.size(); index++) if (choices.get(index).isChecked()) selected.add(memories.get(index));
        String snapshot = TaskContext.selected(selected, localOnly);
        AgentStore.Task existing = taskId == null ? null : store.getTask(taskId);
        if (existing == null || !title.equals(existing.title)) {
            existing = store.createTask(title);
            taskId = existing.id;
        }
        if (!AgentStore.QUEUED.equals(existing.status)) throw new IllegalStateException(en ? "Task already started" : "任务已开始，不能替换上下文");
        ExecutionPlan placeholder = new ExecutionPlan(taskId, title, java.util.Collections.emptyList(), System.currentTimeMillis(), 0)
            .withContext(snapshot);
        store.savePlan(taskId, placeholder);
        return store.getTask(taskId);
    }

    private void planLocally() {
        try {
            if (pending != null) pending.revoke();
            AgentStore.Task task = prepareTask(true);
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
                page(en ? "Sending the approved request…" : "正在发送已授权的本次请求…");
                new Thread(() -> {
                    Exception failure = null;
                    try {
                        new CloudModelClient().requestApprovedPlan(request, store);
                    } catch (Exception error) { failure = error; }
                    Exception error = failure;
                    busy = false;
                    runOnUiThread(() -> {
                        if (isDestroyed()) { store.close(); return; }
                        if (error == null) finish();
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
                ArrayList<String> ids = new ArrayList<>();
                for (int index = 0; index < choices.size(); index++) if (choices.get(index).isChecked()) ids.add(memories.get(index).id);
                selected.putStringArrayList("selected", ids);
                selection(selected);
            } catch (Exception error) { fail(error); }
        });
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
