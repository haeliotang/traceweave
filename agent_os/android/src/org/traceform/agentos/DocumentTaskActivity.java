package org.traceform.agentos;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private MemorySelectionView memorySelection;

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
        if (memorySelection != null) state.putStringArrayList("selected", memorySelection.selectedIds());
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
        label(root, T.s("documenttask.verified_markdown_task", en));
        if (taskId == null) {
            goal = new EditText(this);
            goal.setHint(T.s("documenttask.task_goal", en));
            if (state != null) goal.setText(state.getString("goal", ""));
            root.addView(goal);
            label(root, T.s("documenttask.select_the_memories_to_use_nothing_is", en));
            memorySelection = new MemorySelectionView(this, store, goal, en, state == null ? null : state.getStringArrayList("selected"));
            root.addView(memorySelection);
            button(root, T.s("documenttask.review_context_and_preview_model_docum", en), () -> {
                startActivity(new Intent(this, TaskReviewActivity.class).putExtra(TaskReviewActivity.EXTRA_DOCUMENT, true)
                    .putExtra(TaskReviewActivity.EXTRA_GOAL, goal.getText().toString())
                    .putStringArrayListExtra(TaskReviewActivity.EXTRA_SELECTED, memorySelection.selectedIds()));
                finish();
            });
            button(root, T.s("documenttask.generate_local_markdown_draft", en), () -> {
                try {
                    List<AgentStore.Memory> chosen = memorySelection.chosen();
                    JSONArray sources = new JSONArray();
                    for (AgentStore.Memory memory : chosen) {
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
            if (task == null) throw new IllegalStateException(T.s("documenttask.task_no_longer_exists", en));
            ExecutionPlan.Step step = task.plan.steps.get(0);
            JSONObject value = new JSONObject(step.observation);
            label(root, task.title);
            button(root, T.s("documenttask.save_as_workflow_template", en), () ->
                startActivity(new Intent(this, WorkflowActivity.class).putExtra(WorkflowActivity.EXTRA_GOAL, task.title)));
            if (ExecutionPlan.STEP_DRAFT_READY.equals(step.status) && AgentStore.WAITING_FOR_USER.equals(task.status)) {
                if ("model".equals(value.optString("draftOrigin"))) label(root, T.s("documenttask.model_proposal_review_before_saving", en));
                if ("on_device_model".equals(value.optString("draftOrigin"))) label(root, T.s("documenttask.on_device_model_proposal_review_before", en));
                markdown = new EditText(this);
                markdown.setMinLines(8);
                markdown.setText(state == null ? value.getString("content") : state.getString("markdown", value.getString("content")));
                root.addView(markdown);
                label(root, T.s("documenttask.review_the_full_text_then_choose_a_loc", en));
                button(root, T.s("documenttask.confirm_text_and_choose_file", en), () -> {
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
                button(root, T.s("documenttask.cancel_before_saving", en), () -> {
                    try { store.transition(taskId, AgentStore.CANCELLED); show(null); }
                    catch (Exception error) { fail(error); }
                });
            } else {
                label(root, AgentStore.COMPLETED.equals(task.status)
                    ? (value.optBoolean("localAccess") ? (T.s("documenttask.content_verified_by_readback", en))
                        : (T.s("documenttask.historical_readback_receipt", en)))
                    : (T.s("documenttask.task_state", en)) + task.status);
                label(root, "SHA-256: " + value.optString("sha256", "—"));
                if (value.has("verifiedAt")) label(root, (T.s("documenttask.last_verified", en))
                    + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(value.getLong("verifiedAt"))));
                if (!value.optBoolean("localAccess")) label(root, T.s("documenttask.historical_receipt_only_select_a_file", en));
                if (DocumentAction.locallyVerified(task)) {
                    JSONObject context = task.plan.context.isEmpty() ? new JSONObject() : new JSONObject(task.plan.context);
                    JSONObject workflow = context.optJSONObject("workflow");
                    JSONArray steps = workflow == null ? null : workflow.optJSONArray("steps");
                    if (steps != null && steps.length() == 2 && "calendar_draft".equals(steps.optString(1)))
                        label(root, T.s("documenttask.workflow_next_step_review_a_calendar_d", en));
                    if (steps != null && steps.length() == 2 && "share_draft".equals(steps.optString(1))) {
                        label(root, T.s("documenttask.workflow_next_step_review_a_share_draf", en));
                        button(root, T.s("documenttask.continue_to_share_draft", en), this::shareInBackground);
                    }
                    label(root, T.s("documenttask.continue_to_a_separate_calendar_draft", en));
                    button(root, T.s("documenttask.continue_to_calendar_draft", en), () -> {
                        try {
                            AgentStore.Task child = store.createCalendarFollowup(taskId);
                            startActivity(new Intent(this, ExternalActionActivity.class)
                                .putExtra(ExternalActionActivity.EXTRA_TASK_ID, child.id));
                        } catch (Exception error) { fail(error); }
                    });
                }
                if ((AgentStore.OUTCOME_UNKNOWN.equals(task.status) || AgentStore.COMPLETED.equals(task.status))
                        && !value.optString("attemptId").isEmpty()) {
                    if (value.optBoolean("localAccess") && value.has("uri")) {
                        button(root, T.s("documenttask.verify_existing_file_read_only", en), () -> {
                            try {
                                store.selectDocumentForVerification(taskId, value.getString("uri"));
                                verifyInBackground(false);
                            } catch (Exception error) { fail(error); }
                        });
                    }
                    button(root, T.s("documenttask.select_file_to_verify_read_only", en), () ->
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), VERIFY_DOCUMENT));
                }
            }
        }
        button(root, T.s("documenttask.back_to_tasks", en), this::finish);
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

    private void shareInBackground() {
        if (busy) return;
        busy = true;
        TextView progress = new TextView(this);
        progress.setText(T.s("documenttask.checking_the_verified_file_before_shar", en));
        setContentView(progress);
        new Thread(() -> {
            Exception failure = null;
            AgentStore.Task child = null;
            try {
                AgentStore.Task parent = store.getTask(taskId);
                JSONObject receipt = new JSONObject(parent.plan.steps.get(0).observation);
                String content = DocumentAction.readVerifiedContent(getContentResolver(), parent);
                child = store.createShareFollowup(taskId, content, receipt.getString("uri"));
            } catch (Exception error) { failure = error; }
            Exception error = failure;
            AgentStore.Task result = child;
            busy = false;
            runOnUiThread(() -> {
                if (isDestroyed()) { store.close(); return; }
                if (error != null) fail(error);
                try { show(null); } catch (Exception problem) { fail(problem); }
                if (result != null) startActivity(new Intent(this, ExternalActionActivity.class)
                    .putExtra(ExternalActionActivity.EXTRA_TASK_ID, result.id));
            });
        }, "verified-share-draft").start();
    }

    private void verifyInBackground(boolean write) {
        if (busy) return;
        busy = true;
        TextView progress = new TextView(this);
        progress.setText(T.s("documenttask.checking_the_selected_file", en));
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
