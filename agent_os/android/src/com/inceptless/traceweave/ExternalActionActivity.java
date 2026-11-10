// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;

/** Visible, user-confirmed handoff; callbacks never assert an external effect succeeded. */
public final class ExternalActionActivity extends Activity {
    static final String EXTRA_TASK_ID = "externalTaskId";
    private static final int HANDOFF_REQUEST = 40;
    private AgentStore store;
    private CapabilityBroker broker;
    private String taskId;
    private long startTime;
    private long endTime;
    private EditText title;
    private EditText content;
    private Button start;
    private Button end;
    private boolean en;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AgentStore(this);
        broker = new CapabilityBroker(this, store);
        en = "en".equals(getSharedPreferences("agent_os_prefs", MODE_PRIVATE).getString("app_lang", "zh"));
        taskId = state == null ? getIntent().getStringExtra(EXTRA_TASK_ID) : state.getString(EXTRA_TASK_ID);
        try {
            if (taskId == null) {
                showEditor(null, state);
            } else {
                AgentStore.Task task = store.getTask(taskId);
                if (task == null) throw new IllegalStateException(T.s("externalactionactivity.task_no_longer_exists", en));
                ExecutionPlan.Step step = task.plan.steps.get(task.executionStep);
                if (ExecutionPlan.STEP_HANDED_OFF.equals(step.status))
                    store.recordExternalOutcome(task.id, task.executionStep,
                        new JSONObject(step.observation).getString("attemptId"), false, "activity_recreated");
                showEditor(store.getTask(taskId), state);
            }
        } catch (Exception error) { showFailure(error); finish(); }
    }

    @Override protected void onResume() {
        super.onResume();
        if (taskId == null) return;
        try {
            AgentStore.Task task = store.getTask(taskId);
            if (task != null && task.plan != null && task.executionStep < task.plan.totalSteps()) {
                ExecutionPlan.Step step = task.plan.steps.get(task.executionStep);
                if (ExecutionPlan.STEP_HANDED_OFF.equals(step.status)) {
                    store.recordExternalOutcome(task.id, task.executionStep,
                        new JSONObject(step.observation).getString("attemptId"), false, "returned_without_receipt");
                    showEditor(store.getTask(taskId), null);
                }
            }
        } catch (Exception error) { showFailure(error); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(EXTRA_TASK_ID, taskId);
        if (title != null) state.putString("title", title.getText().toString());
        if (content != null) state.putString("content", content.getText().toString());
        state.putLong("start", startTime);
        state.putLong("end", endTime);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (store != null) store.close();
        super.onDestroy();
    }

    private void showEditor(AgentStore.Task task, Bundle state) throws Exception {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        TextView heading = new TextView(this);
        heading.setTextSize(22);
        heading.setText(T.s("externalactionactivity.review_external_action", en));
        root.addView(heading);

        ExecutionPlan.Step step = task == null ? null : task.plan.steps.get(task.executionStep);
        if (task != null && (!AgentStore.WAITING_FOR_USER.equals(task.status)
                || !ExecutionPlan.STEP_DRAFT_READY.equals(step.status))) {
            TextView result = new TextView(this);
            result.setText(AgentStore.OUTCOME_UNKNOWN.equals(task.status)
                ? (T.s("externalactionactivity.handed_to_another_app_saving_or_sendin", en))
                : (T.s("externalactionactivity.action_state", en)) + task.status);
            root.addView(result);
            addButton(root, T.s("externalactionactivity.back_to_tasks", en), this::finish);
            return;
        }

        boolean calendar = step == null || LocalCalendarProvider.CAPABILITY.equals(step.capability);
        JSONObject draft = step == null ? ExternalAction.calendarDraft("", 0, 0) : new JSONObject(step.observation);
        title = new EditText(this);
        title.setHint(T.s("externalactionactivity.title", en));
        title.setSingleLine(true);
        title.setText(state != null ? state.getString("title", "") : draft.optString(calendar ? "title" : "subject", ""));
        root.addView(title);
        if (calendar) {
            startTime = state == null ? draft.optLong("startTime", 0) : state.getLong("start");
            endTime = state == null ? draft.optLong("endTime", 0) : state.getLong("end");
            start = addButton(root, "", () -> chooseTime(true));
            end = addButton(root, "", () -> chooseTime(false));
            updateTimeButtons();
        } else {
            content = new EditText(this);
            content.setHint(T.s("externalactionactivity.text_to_share", en));
            content.setMinLines(4);
            content.setText(state != null ? state.getString("content", "") : draft.optString("content", ""));
            root.addView(content);
        }
        TextView explanation = new TextView(this);
        explanation.setText(en
            ? "The next confirmation authorizes only this preview. The receiving app handles saving or sending; its return code does not prove success. No calendar read permission is requested."
            : "下一次确认只授权本次预览内容。保存或发送由目标应用处理，返回码不能证明成功。Agent OS 不申请读取日历权限。");
        root.addView(explanation);
        if (task == null) {
            addButton(root, T.s("externalactionactivity.create_draft_and_preview", en), () -> {
                try {
                    JSONObject input = editedDraft(true);
                    ExternalAction.intent(LocalCalendarProvider.CAPABILITY, input);
                    AgentStore.Task created = store.createTask(input.getString("title"));
                    taskId = created.id;
                    ExecutionPlan.Step calendarStep = new ExecutionPlan.Step(0, T.s("externalactionactivity.prepare_calendar_event", en),
                        LocalCalendarProvider.CAPABILITY, LocalCalendarProvider.SCOPE, input.toString(),
                        ExecutionPlan.STEP_PENDING, "", 0);
                    store.savePlan(taskId, new ExecutionPlan(taskId, created.title, Collections.singletonList(calendarStep),
                        System.currentTimeMillis(), 0));
                    store.transition(taskId, AgentStore.ACTIVE);
                    AgentStore.Task current = store.getTask(taskId);
                    store.prepareExternalDraft(taskId, 0, ExternalAction.prepare(current, calendarStep).toString());
                    showEditor(store.getTask(taskId), null);
                } catch (Exception error) { showFailure(error); }
            });
        } else {
            addButton(root, calendar ? (T.s("externalactionactivity.authorize_and_open_calendar", en))
                : (T.s("externalactionactivity.authorize_and_open_share_chooser", en)), () -> launch(calendar, draft.optString("draftId")));
            addButton(root, T.s("externalactionactivity.cancel_before_handoff", en), () -> {
                try { store.transition(taskId, AgentStore.CANCELLED); finish(); }
                catch (Exception error) { showFailure(error); }
            });
        }
        addButton(root, T.s("externalactionactivity.back", en), this::finish);
    }

    private JSONObject editedDraft(boolean calendar) throws Exception {
        if (calendar) return ExternalAction.calendarDraft(title.getText().toString(), startTime, endTime);
        return new JSONObject().put("subject", title.getText().toString()).put("content", content.getText().toString());
    }

    private void launch(boolean calendar, String draftId) {
        String attempt = null;
        int stepIndex = -1;
        boolean dispatched = false;
        try {
            AgentStore.Task task = store.getTask(taskId);
            stepIndex = task.executionStep;
            ExecutionPlan.Step step = task.plan.steps.get(stepIndex);
            JSONObject draft = editedDraft(calendar);
            Intent intent = ExternalAction.intent(step.capability, draft);
            broker.grant("local_agent", taskId, step.capability, step.scope, 0);
            broker.invoke(taskId, step.capability, step.scope);
            attempt = store.claimExternalAction(taskId, stepIndex, draftId, draft);
            if (intent.resolveActivity(getPackageManager()) == null)
                throw new ActivityNotFoundException(T.s("externalactionactivity.install_a_calendar_or_share_target_fir", en));
            startActivityForResult(calendar ? intent : Intent.createChooser(intent, T.s("externalactionactivity.share", en)), HANDOFF_REQUEST);
            dispatched = true;
            store.appendTaskEvent(taskId, "action_launch_accepted:step_" + (stepIndex + 1));
        } catch (Exception error) {
            if (attempt != null) {
                try { store.recordExternalOutcome(taskId, stepIndex, attempt, !dispatched, dispatched ? "post_launch_journal_error" : "launch_exception"); }
                catch (Exception storeError) { showFailure(storeError); }
            }
            showFailure(error);
            try { if (taskId != null) showEditor(store.getTask(taskId), null); }
            catch (Exception storeError) { showFailure(storeError); }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != HANDOFF_REQUEST || taskId == null) return;
        try {
            AgentStore.Task task = store.getTask(taskId);
            ExecutionPlan.Step step = task.plan.steps.get(task.executionStep);
            store.recordExternalOutcome(taskId, task.executionStep,
                new JSONObject(step.observation).getString("attemptId"), false, "activity_result_" + resultCode);
            showEditor(store.getTask(taskId), null);
        } catch (Exception error) { showFailure(error); }
    }

    private void chooseTime(boolean beginning) {
        Calendar value = Calendar.getInstance();
        long existing = beginning ? startTime : endTime;
        if (existing > 0) value.setTimeInMillis(existing);
        new DatePickerDialog(this, (picker, year, month, day) ->
            new TimePickerDialog(this, (clock, hour, minute) -> {
                try {
                    value.setLenient(false);
                    value.set(year, month, day, hour, minute, 0);
                    value.set(Calendar.MILLISECOND, 0);
                    if (beginning) startTime = value.getTimeInMillis(); else endTime = value.getTimeInMillis();
                    updateTimeButtons();
                } catch (Exception error) { showFailure(error); }
            }, value.get(Calendar.HOUR_OF_DAY), value.get(Calendar.MINUTE), true).show(),
            value.get(Calendar.YEAR), value.get(Calendar.MONTH), value.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateTimeButtons() {
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        start.setText((T.s("externalactionactivity.start", en)) + (startTime == 0 ? (T.s("externalactionactivity.choose_date_and_time", en)) : format.format(new Date(startTime)))
            + " (" + TimeZone.getDefault().getID() + ")");
        end.setText((T.s("externalactionactivity.end", en)) + (endTime == 0 ? (T.s("externalactionactivity.choose_date_and_time", en)) : format.format(new Date(endTime))));
    }

    private Button addButton(LinearLayout root, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        root.addView(button);
        return button;
    }

    private void showFailure(Exception error) {
        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
    }
}
