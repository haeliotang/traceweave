package com.inceptless.traceweave;

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
        if (task == null || task.plan == null) throw new IllegalStateException(T.s("memoryreviewactivity.task_no_longer_exists", en));
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
        text(root, T.s("memoryreviewactivity.review_memory_suggestions", en));
        text(root, (T.s("memoryreviewactivity.source_task", en)) + task.title + "\n" + task.id);
        text(root, T.s("memoryreviewactivity.suggestions_are_not_facts_edit_and_acc", en));
        boolean en = "en".equals(getSharedPreferences("agent_os_prefs", 0).getString("app_lang", "zh"));
        List<MemoryConsolidator.ConsolidatedItem> candidates = MemoryConsolidator.extractItems(task, en);
        JSONObject decisions = new JSONObject(task.plan.memoryDecisions);
        for (int index = 0; index < candidates.size(); index++) {
            final int item = index;
            MemoryConsolidator.ConsolidatedItem candidate = candidates.get(index);
            text(root, (T.s("memoryreviewactivity.suggestion", en)) + (index + 1) + " · " + candidate.kind);
            if (decisions.has(String.valueOf(index))) {
                JSONObject decision = decisions.getJSONObject(String.valueOf(index));
                text(root, "accepted".equals(decision.optString("decision"))
                    ? (T.s("memoryreviewactivity.accepted", en)) + decision.optString("content") : (T.s("memoryreviewactivity.rejected", en)));
                continue;
            }
            if (!"pending".equals(task.plan.memoryReview)) {
                text(root, (T.s("memoryreviewactivity.previously_reviewed", en)) + task.plan.memoryReview);
                continue;
            }
            EditText content = new EditText(this);
            content.setMinLines(3);
            content.setText(candidate.content);
            root.addView(content);
            button(root, T.s("memoryreviewactivity.confirm_this_edited_memory", en), () -> review(item, true, content.getText().toString()));
            button(root, T.s("memoryreviewactivity.reject_this_suggestion", en), () -> review(item, false, ""));
        }
        button(root, T.s("memoryreviewactivity.back_to_tasks", en), this::finish);
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
