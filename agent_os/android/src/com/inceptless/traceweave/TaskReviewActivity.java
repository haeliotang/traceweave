// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

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
    private Spinner outputLimit;
    private java.util.concurrent.atomic.AtomicBoolean localRequest;
    private Button modelAction;

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
        state.putInt("outputLimit", outputLimit.getSelectedItemPosition());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (pending != null) pending.revoke();
        if (busy && localRequest != null) {
            try { cancelLocalRequest(); } catch (Exception ignored) { LocalModelDraft.cancel(localRequest); }
        }
        if (!busy) store.close();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        updateModelAction();
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
        LinearLayout root = page(T.s("taskreview.review_task_context", en));
        goal = new EditText(this);
        goal.setHint(T.s("taskreview.task_goal", en));
        goal.setText(state == null ? getIntent().getStringExtra(EXTRA_GOAL) : state.getString("goal", ""));
        root.addView(goal);
        text(root, T.s("taskreview.select_memories_for_this_task_nothing", en));
        ArrayList<String> ids = state == null ? getIntent().getStringArrayListExtra(EXTRA_SELECTED) : state.getStringArrayList("selected");
        memorySelection = new MemorySelectionView(this, store, goal, en, ids);
        root.addView(memorySelection);
        text(root, T.s("taskreview.maximum_output_tokens_for_this_request", en));
        outputLimit = new Spinner(this);
        outputLimit.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"256", "512", "1024"}));
        outputLimit.setSelection(state == null ? 2 : state.getInt("outputLimit", 2));
        root.addView(outputLimit);
        button(root, T.s("taskreview.plan_locally_keep_this_task_local", en), this::planLocally);
        modelAction = null;
        if (document) {
            modelAction = button(root, "", () -> {
                if (LocalModelRuntime.isAvailable() && LocalModelActivity.modelFile(this).isFile()) draftLocally();
                else startActivity(new Intent(this, LocalModelActivity.class));
            });
            updateModelAction();
        }
        button(root, T.s("taskreview.preview_one_cloud_planning_request", en), () -> {
            try {
                AgentStore.Task task = prepareTask(false);
                CloudModelConfig config = CloudModelConfig.load(this);
                int limit = Integer.parseInt(outputLimit.getSelectedItem().toString());
                pending = document ? new CloudModelClient().prepareDocument(task, config, limit)
                    : new CloudModelClient().preparePlan(task, new CapabilityBroker(this, store).listAvailableCapabilities(task.id), config, limit);
                cloudPreview();
            } catch (Exception error) { fail(error); }
        });
        button(root, T.s("taskreview.back", en), this::finish);
    }

    private AgentStore.Task prepareTask(boolean localOnly) throws Exception {
        String title = goal.getText().toString().trim();
        if (title.isEmpty() || title.length() > 2000) throw new IllegalArgumentException(T.s("taskreview.enter_a_task_goal_up_to_2000_character", en));
        List<AgentStore.Memory> selected = memorySelection.chosen();
        String snapshot = TaskContext.selected(selected, localOnly);
        AgentStore.Task existing = taskId == null ? null : store.getTask(taskId);
        org.json.JSONObject previousContext = existing == null || existing.plan == null || existing.plan.context.isEmpty()
            ? new org.json.JSONObject() : new org.json.JSONObject(existing.plan.context);
        if (existing == null || !title.equals(existing.title)) {
            existing = store.createTask(title);
            taskId = existing.id;
        }
        if (!AgentStore.QUEUED.equals(existing.status)) throw new IllegalStateException(T.s("taskreview.task_already_started", en));
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

    private void draftLocally() {
        try {
            AgentStore.Task task = prepareTask(true);
            String fingerprint = TaskContext.fingerprint(task);
            java.util.concurrent.atomic.AtomicBoolean token = new java.util.concurrent.atomic.AtomicBoolean();
            localRequest = token; busy = true;
            LinearLayout progress = page(T.s("taskreview.generating_on_device", en));
            text(progress, T.s("taskreview.the_goal_and_selected_memories_stay_on", en));
            Button cancel = new Button(this); cancel.setText(T.s("taskreview.cancel_generation", en));
            cancel.setOnClickListener(view -> {
                try { cancelLocalRequest(); finish(); } catch (Exception error) { fail(error); }
            });
            progress.addView(cancel);
            new Thread(() -> {
                Throwable failure = null;
                try {
                    String content = LocalModelDraft.generate(task, LocalModelActivity.modelFile(this), token);
                    synchronized (token) {
                        if (token.get()) throw new IllegalStateException("Local model request cancelled");
                        store.prepareLocalModelDocument(task.id, content, fingerprint);
                    }
                } catch (Exception | LinkageError error) { failure = error; }
                Throwable resultError = failure;
                busy = false;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) { store.close(); return; }
                    localRequest = null;
                    if (resultError == null) openDocument();
                    else {
                        LinearLayout fallback = page(T.s("taskreview.on_device_draft_unavailable", en));
                        text(fallback, T.s("taskreview.check_the_model_and_selected_context_t", en));
                        button(fallback, T.s("taskreview.review_context_and_retry", en), () -> {
                            try {
                                Bundle state = new Bundle();
                                state.putString("goal", goal.getText().toString());
                                state.putStringArrayList("selected", memorySelection.selectedIds());
                                state.putInt("outputLimit", outputLimit.getSelectedItemPosition());
                                selection(state);
                            } catch (Exception error) { fail(error); }
                        });
                        button(fallback, T.s("taskreview.use_local_template", en), this::planLocally);
                        button(fallback, T.s("taskreview.back", en), this::finish);
                    }
                });
            }, "local-model-draft").start();
        } catch (Exception error) { busy = false; localRequest = null; fail(error); }
    }

    private void updateModelAction() {
        if (modelAction == null) return;
        boolean ready = LocalModelRuntime.isAvailable() && LocalModelActivity.modelFile(this).isFile();
        modelAction.setText(ready ? (T.s("taskreview.draft_with_on_device_model", en))
            : (T.s("taskreview.set_up_on_device_model", en)));
    }

    private void cancelLocalRequest() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean token = localRequest;
        if (token == null) return;
        synchronized (token) {
            LocalModelDraft.cancel(token);
            AgentStore.Task task = store.getTask(taskId);
            if (task == null) return;
            boolean ready = AgentStore.WAITING_FOR_USER.equals(task.status) && task.plan != null
                && task.plan.totalSteps() == 1 && ExecutionPlan.STEP_DRAFT_READY.equals(task.plan.steps.get(0).status)
                && "on_device_model".equals(new org.json.JSONObject(task.plan.steps.get(0).observation).optString("draftOrigin"));
            if (AgentStore.QUEUED.equals(task.status) || ready) store.transition(task.id, AgentStore.CANCELLED);
        }
    }

    private void cloudPreview() {
        LinearLayout root = page(T.s("taskreview.exact_request_preview", en));
        text(root, (T.s("taskreview.endpoint", en)) + pending.endpoint);
        text(root, (T.s("taskreview.model", en)) + pending.config.modelName);
        text(root, (T.s("taskreview.request_bytes", en)) + pending.body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        text(root, T.s("taskreview.the_provider_must_support_max_tokens_t", en));
        text(root, T.s("taskreview.the_request_below_includes_the_goal_se", en));
        text(root, pending.body);
        if (document) text(root, T.s("taskreview.the_model_proposes_text_only_review_an", en));
        button(root, T.s("taskreview.allow_this_planning_request_once", en), () -> {
            try {
                CloudRequest request = pending;
                CloudModelConfig current = CloudModelConfig.load(this);
                if (!current.isConfigured() || !request.endpoint.equals(CloudModelClient.normalizeEndpointUrl(current.endpointUrl))
                        || !request.config.modelName.equals(current.modelName) || !request.config.apiKey.equals(current.apiKey)) {
                    request.revoke();
                    throw new SecurityException(T.s("taskreview.cloud_settings_changed_review_a_new_re", en));
                }
                request.approveOnce();
                busy = true;
                LinearLayout progress = page(T.s("taskreview.sending_the_approved_request", en));
                text(progress, T.s("taskreview.cancellation_discards_the_result_a_req", en));
                Button cancel = new Button(this);
                cancel.setText(T.s("taskreview.cancel_task_and_discard_response", en));
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
                            LinearLayout fallback = page(T.s("taskreview.request_did_not_produce_a_usable_plan", en));
                            text(fallback, CloudFailure.describe(error, en));
                            text(fallback, T.s("taskreview.no_automatic_retry_will_be_sent_you_ca", en));
                            button(fallback, T.s("taskreview.plan_locally", en), this::planLocally);
                            button(fallback, T.s("taskreview.review_a_new_request", en), () -> {
                                try {
                                    Bundle selected = new Bundle();
                                    selected.putString("goal", goal.getText().toString());
                                    selected.putStringArrayList("selected", memorySelection.selectedIds());
                                    selected.putInt("outputLimit", outputLimit.getSelectedItemPosition());
                                    selection(selected);
                                } catch (Exception problem) { fail(problem); }
                            });
                            button(fallback, T.s("taskreview.back", en), this::finish);
                        }
                    });
                }, "consented-cloud-planning").start();
            } catch (Exception error) { busy = false; fail(error); }
        });
        button(root, T.s("taskreview.keep_local_do_not_send", en), this::planLocally);
        button(root, T.s("taskreview.revoke_preview_and_edit_context", en), () -> {
            try {
                pending.revoke();
                Bundle selected = new Bundle();
                selected.putString("goal", goal.getText().toString());
                selected.putStringArrayList("selected", memorySelection.selectedIds());
                selected.putInt("outputLimit", outputLimit.getSelectedItemPosition());
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
        if (busy && localRequest != null) {
            try { cancelLocalRequest(); finish(); } catch (Exception error) { fail(error); }
        } else if (busy) cancelPlanning();
        else super.onBackPressed();
    }

    private void text(LinearLayout root, String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextIsSelectable(true);
        root.addView(view);
    }
    private Button button(LinearLayout root, String value, Runnable action) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setOnClickListener(view -> { if (!busy) action.run(); });
        root.addView(button);
        return button;
    }
    private void fail(Exception error) {
        android.util.Log.e("AgentOsTaskReview", "review failed: " + error);
        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
    }
}
