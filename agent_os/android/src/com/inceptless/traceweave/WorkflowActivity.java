// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

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
        text(root, T.s("workflow.reusable_document_workflows", en));
        text(root, T.s("workflow.each_run_creates_a_new_task_choose_mem", en));
        EditText name = new EditText(this); nameInput = name; name.setHint(T.s("workflow.workflow_name", en)); root.addView(name);
        EditText pattern = new EditText(this); patternInput = pattern; pattern.setHint(T.s("workflow.goal_template_containing_input", en));
        name.setText(restored == null ? "" : restored.getString("name", ""));
        pattern.setText(restored == null ? getIntent().getStringExtra(EXTRA_GOAL) : restored.getString("pattern", "")); root.addView(pattern);
        calendarFollowup = new CheckBox(this);
        calendarFollowup.setText(T.s("workflow.after_verified_file_offer_a_separately", en));
        calendarFollowup.setChecked(restored != null && restored.getBoolean("calendarFollowup"));
        root.addView(calendarFollowup);
        shareFollowup = new CheckBox(this);
        shareFollowup.setText(T.s("workflow.after_verified_file_offer_a_separately_2", en));
        shareFollowup.setChecked(restored != null && restored.getBoolean("shareFollowup"));
        calendarFollowup.setOnCheckedChangeListener((view, checked) -> { if (checked) shareFollowup.setChecked(false); });
        shareFollowup.setOnCheckedChangeListener((view, checked) -> { if (checked) calendarFollowup.setChecked(false); });
        root.addView(shareFollowup);
        runInputs.clear();
        button(root, T.s("workflow.save_workflow_template", en), () -> {
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
                    text(root, T.s("workflow.unsupported_workflow_inspect_or_delete", en));
                    continue;
                }
                if (memory.isExpired(System.currentTimeMillis())) {
                    text(root, recipe.name + (T.s("workflow.expired_renew_in_memory_vault", en)));
                    continue;
                }
                text(root, recipe.name + "\n" + recipe.goalTemplate + (recipe.calendarFollowup
                    ? (T.s("workflow.nsteps_verified_file_calendar_draft", en))
                    : recipe.shareFollowup ? (T.s("workflow.nsteps_verified_file_share_draft", en)) : ""));
                EditText input = new EditText(this); input.setHint(T.s("workflow.input_for_this_run", en)); root.addView(input);
                runInputs.put(memory.id, input);
                if (restored != null && restored.getBundle("inputs") != null) input.setText(restored.getBundle("inputs").getString(memory.id, ""));
                button(root, T.s("workflow.review_a_new_run", en), () -> {
                    try {
                        AgentStore.Task run = WorkflowTemplate.createRun(store, memory.id, input.getText().toString());
                        input.setText("");
                        startActivity(new Intent(this, TaskReviewActivity.class).putExtra(TaskReviewActivity.EXTRA_TASK_ID, run.id));
                    } catch (Exception error) { fail(error); }
                });
                button(root, T.s("workflow.delete_template_keep_run_history", en), () -> {
                    new android.app.AlertDialog.Builder(this).setMessage(T.s("workflow.delete_this_template_previous_runs_and", en))
                        .setNegativeButton(T.s("workflow.cancel", en), null)
                        .setPositiveButton(T.s("workflow.delete", en), (dialog, which) -> {
                            try { store.deleteMemory(memory.id); show(); } catch (Exception error) { fail(error); }
                        }).show();
                });
            }
        } catch (Exception error) { fail(error); }
        restored = null;
        button(root, T.s("workflow.back", en), this::finish);
    }
    private void text(LinearLayout root, String text) { TextView view = new TextView(this); view.setText(text); root.addView(view); }
    private void button(LinearLayout root, String text, Runnable action) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false); view.setOnClickListener(v -> action.run()); root.addView(view);
    }
    private void fail(Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
}
