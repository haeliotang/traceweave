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
import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkflowActivity extends Activity {
    static final String EXTRA_GOAL = "workflowGoal";
    private AgentStore store;
    private boolean en;
    private Bundle restored;
    private EditText nameInput;
    private EditText patternInput;
    private CheckBox calendarFollowup;
    private CheckBox shareFollowup;
    private final Map<String, EditText> runInputs = new LinkedHashMap<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AgentStore(this);
        en = "en".equals(getSharedPreferences("agent_os_prefs", 0).getString("app_lang", "zh"));
        restored = state;
        show();
    }

    @Override protected void onDestroy() { store.close(); super.onDestroy(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("name", nameInput.getText().toString()); state.putString("pattern", patternInput.getText().toString());
        state.putBoolean("calendarFollowup", calendarFollowup.isChecked());
        state.putBoolean("shareFollowup", shareFollowup.isChecked());
        Bundle inputs = new Bundle(); for (Map.Entry<String, EditText> entry : runInputs.entrySet()) inputs.putString(entry.getKey(), entry.getValue().getText().toString());
        state.putBundle("inputs", inputs); super.onSaveInstanceState(state);
    }

    private void show() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this); scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(32, 40 + insets.getSystemWindowInsetTop(), 32, 32 + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        text(root, en ? "Reusable document workflows" : "可复用文稿工作流");
        text(root, en ? "Each run creates a new task. Choose memories and approve cloud/file access again. Templates carry no grants."
            : "每次运行创建独立任务，重新选择记忆并批准云端或文件访问；模板不携带授权。");
        EditText name = new EditText(this); nameInput = name; name.setHint(en ? "Workflow name" : "工作流名称"); root.addView(name);
        EditText pattern = new EditText(this); patternInput = pattern; pattern.setHint(en ? "Goal template containing {{input}}" : "包含 {{input}} 的目标模板");
        name.setText(restored == null ? "" : restored.getString("name", ""));
        pattern.setText(restored == null ? getIntent().getStringExtra(EXTRA_GOAL) : restored.getString("pattern", "")); root.addView(pattern);
        calendarFollowup = new CheckBox(this);
        calendarFollowup.setText(en ? "After verified file, offer a separately approved calendar draft" : "文件核验后，继续创建需单独确认的日历草稿");
        calendarFollowup.setChecked(restored != null && restored.getBoolean("calendarFollowup"));
        root.addView(calendarFollowup);
        shareFollowup = new CheckBox(this);
        shareFollowup.setText(en ? "After verified file, offer a separately approved share draft" : "文件核验后，继续创建需单独确认的分享草稿");
        shareFollowup.setChecked(restored != null && restored.getBoolean("shareFollowup"));
        calendarFollowup.setOnCheckedChangeListener((view, checked) -> { if (checked) shareFollowup.setChecked(false); });
        shareFollowup.setOnCheckedChangeListener((view, checked) -> { if (checked) calendarFollowup.setChecked(false); });
        root.addView(shareFollowup);
        runInputs.clear();
        button(root, en ? "Save workflow template" : "保存工作流模板", () -> {
            try {
                WorkflowTemplate recipe = new WorkflowTemplate(name.getText().toString(), pattern.getText().toString(), calendarFollowup.isChecked(), shareFollowup.isChecked());
                store.createMemory(AgentStore.MEMORY_WORKFLOW, recipe.encode());
                getIntent().removeExtra(EXTRA_GOAL); show();
            } catch (Exception error) { fail(error); }
        });
        try {
            for (AgentStore.Memory memory : store.listMemories()) {
                if (!WorkflowTemplate.isTemplate(memory)) continue;
                WorkflowTemplate recipe;
                try { recipe = WorkflowTemplate.decode(memory); }
                catch (Exception error) {
                    text(root, en ? "Unsupported workflow · inspect or delete it in Memory Vault" : "不支持的工作流 · 可在记忆库检查或删除");
                    continue;
                }
                if (memory.isExpired(System.currentTimeMillis())) {
                    text(root, recipe.name + (en ? " · Expired; renew in Memory Vault" : " · 已到期，请在记忆库更新有效期"));
                    continue;
                }
                text(root, recipe.name + "\n" + recipe.goalTemplate + (recipe.calendarFollowup
                    ? (en ? "\nSteps: verified file → calendar draft" : "\n步骤：核验文件 → 日历草稿")
                    : recipe.shareFollowup ? (en ? "\nSteps: verified file → share draft" : "\n步骤：核验文件 → 分享草稿") : ""));
                EditText input = new EditText(this); input.setHint(en ? "Input for this run" : "本次运行的输入参数"); root.addView(input);
                runInputs.put(memory.id, input);
                if (restored != null && restored.getBundle("inputs") != null) input.setText(restored.getBundle("inputs").getString(memory.id, ""));
                button(root, en ? "Review a new run" : "审核新的一次运行", () -> {
                    try {
                        AgentStore.Task run = WorkflowTemplate.createRun(store, memory.id, input.getText().toString());
                        input.setText("");
                        startActivity(new Intent(this, TaskReviewActivity.class).putExtra(TaskReviewActivity.EXTRA_TASK_ID, run.id));
                    } catch (Exception error) { fail(error); }
                });
                button(root, en ? "Delete template · keep run history" : "删除模板 · 保留运行历史", () -> {
                    new android.app.AlertDialog.Builder(this).setMessage(en ? "Delete this template? Previous runs and files remain."
                        : "删除此模板？已有运行历史和文件仍保留。")
                        .setNegativeButton(en ? "Cancel" : "取消", null)
                        .setPositiveButton(en ? "Delete" : "删除", (dialog, which) -> {
                            try { store.deleteMemory(memory.id); show(); } catch (Exception error) { fail(error); }
                        }).show();
                });
            }
        } catch (Exception error) { fail(error); }
        restored = null;
        button(root, en ? "Back" : "返回", this::finish);
    }
    private void text(LinearLayout root, String text) { TextView view = new TextView(this); view.setText(text); root.addView(view); }
    private void button(LinearLayout root, String text, Runnable action) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false); view.setOnClickListener(v -> action.run()); root.addView(view);
    }
    private void fail(Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
}
