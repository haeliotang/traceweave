package org.traceform.agentos;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

public final class MemoryReviewActivity extends Activity {
    static final String EXTRA_TASK_ID = "memoryReviewTaskId";
    private AgentStore store;
    private String taskId;
    private boolean en;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AgentStore(this);
        taskId = getIntent().getStringExtra(EXTRA_TASK_ID);
        en = "en".equals(getSharedPreferences("agent_os_prefs", MODE_PRIVATE).getString("app_lang", "zh"));
        try { show(); } catch (Exception error) { fail(error); finish(); }
    }

    @Override protected void onDestroy() { store.close(); super.onDestroy(); }

    private void show() throws Exception {
        AgentStore.Task task = store.getTask(taskId);
        if (task == null || task.plan == null) throw new IllegalStateException(en ? "Task no longer exists" : "任务已不存在");
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
        text(root, en ? "Review memory suggestions" : "逐条审核候选记忆");
        text(root, (en ? "Source task: " : "来源任务：") + task.title + "\n" + task.id);
        text(root, en ? "Suggestions are not facts. Edit and accept or reject each item. Leaving a suggestion pending saves no memory."
            : "候选建议尚不是事实。可逐条修改后确认或拒绝；未处理项不会写入记忆。");
        List<MemoryConsolidator.ConsolidatedItem> candidates = MemoryConsolidator.extractItems(task);
        JSONObject decisions = new JSONObject(task.plan.memoryDecisions);
        for (int index = 0; index < candidates.size(); index++) {
            final int item = index;
            MemoryConsolidator.ConsolidatedItem candidate = candidates.get(index);
            text(root, (en ? "Suggestion " : "候选 ") + (index + 1) + " · " + candidate.kind);
            if (decisions.has(String.valueOf(index))) {
                JSONObject decision = decisions.getJSONObject(String.valueOf(index));
                text(root, "accepted".equals(decision.optString("decision"))
                    ? (en ? "Accepted: " : "已确认：") + decision.optString("content") : (en ? "Rejected" : "已拒绝"));
                continue;
            }
            if (!"pending".equals(task.plan.memoryReview)) {
                text(root, (en ? "Previously reviewed: " : "历史审核结果：") + task.plan.memoryReview);
                continue;
            }
            EditText content = new EditText(this);
            content.setMinLines(3);
            content.setText(candidate.content);
            root.addView(content);
            button(root, en ? "Confirm this edited memory" : "确认本条修改后的记忆", () -> review(item, true, content.getText().toString()));
            button(root, en ? "Reject this suggestion" : "拒绝本条候选", () -> review(item, false, ""));
        }
        button(root, en ? "Back to tasks" : "返回任务", this::finish);
    }

    private void review(int index, boolean accept, String content) {
        try { store.reviewMemoryCandidate(taskId, index, accept, content); show(); }
        catch (Exception error) { fail(error); }
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
        button.setOnClickListener(view -> action.run());
        root.addView(button);
    }
    private void fail(Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
}
