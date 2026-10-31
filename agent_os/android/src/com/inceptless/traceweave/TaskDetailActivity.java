package com.inceptless.traceweave;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** One task, in full: steps, their observations and the audit entries for this task. */
public final class TaskDetailActivity extends Activity {
    static final String EXTRA_TASK_ID = "taskId";

    private AgentStore store;
    private String taskId;
    private boolean en;
    private LinearLayout content;
    private LinearLayout actions;
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private String lastSignature = "";
    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            renderIfChanged();
            AgentStore.Task task = currentTask();
            if (task != null && (AgentStore.ACTIVE.equals(task.status) || AgentStore.WAITING_FOR_USER.equals(task.status))) {
                ticker.postDelayed(this, 1500);
            }
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AgentStore(this);
        taskId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TASK_ID);
        en = "en".equals(getSharedPreferences("agent_os_prefs", MODE_PRIVATE).getString("app_lang", "zh"));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.background(this));
        int statusBar = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int topInset = statusBar > 0 ? getResources().getDimensionPixelSize(statusBar) : dp(24);
        page.setPadding(dp(16), topInset + dp(8), dp(16), dp(12));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroller = new ScrollView(this);
        scroller.addView(content);
        page.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(actions);

        Button back = new Button(this);
        back.setText(T.s("taskdetail.back", en));
        back.setAllCaps(false);
        back.setBackgroundColor(Ui.secondaryBg(this));
        back.setTextColor(Ui.secondaryFg(this));
        back.setOnClickListener(view -> finish());
        page.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        setContentView(page);
        render();
        ticker.postDelayed(refreshLoop, 1500);
    }

    private AgentStore.Task currentTask() {
        try {
            return store.getTask(taskId);
        } catch (Exception error) {
            return null;
        }
    }

    private void renderIfChanged() {
        AgentStore.Task task = currentTask();
        String signature = task == null ? "missing" : task.status + "/" + task.executionStep + "/" + task.updatedAt;
        if (!signature.equals(lastSignature)) render();
    }

    private void render() {
        AgentStore.Task snapshot = currentTask();
        lastSignature = snapshot == null ? "missing"
            : snapshot.status + "/" + snapshot.executionStep + "/" + snapshot.updatedAt;
        content.removeAllViews();
        AgentStore.Task task = null;
        try {
            task = store.getTask(taskId);
        } catch (Exception ignored) {
        }
        if (task == null) {
            TextView missing = Ui.meta(this, T.s("taskdetail.missing", en));
            content.addView(missing);
            return;
        }
        TextView title = Ui.title(this, task.title);
        title.setTextSize(20);
        content.addView(title);

        TextView status = Ui.meta(this, statusLabel(task.status) + " · "
            + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(new java.util.Date(task.updatedAt)));
        status.setPadding(0, dp(4), 0, dp(8));
        content.addView(status);

        int total = task.plan == null ? 0 : task.plan.totalSteps();
        int done = 0;
        if (task.plan != null) {
            for (ExecutionPlan.Step step : task.plan.steps) {
                if (ExecutionPlan.STEP_DONE.equals(step.status)) done++;
            }
        }
        if (total > 0) {
            TextView progress = new TextView(this);
            progress.setText(T.format("task.progress_line", en, done, total, total == 0 ? 0 : Math.round(done * 100f / total)));
            progress.setTextSize(13);
            progress.setTextColor(Ui.text(this));
            content.addView(progress);
        }

        if (task.plan != null) {
            content.addView(Ui.section(this, T.s("taskdetail.steps", en)));
            for (ExecutionPlan.Step step : task.plan.steps) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                GradientDrawable background = new GradientDrawable();
                background.setColor(Ui.card(this));
                background.setCornerRadius(dp(8));
                background.setStroke(dp(1), step.index == task.executionStep
                    && (AgentStore.ACTIVE.equals(task.status) || AgentStore.WAITING_FOR_USER.equals(task.status))
                    ? Ui.cardAccent(this) : Ui.stroke(this));
                row.setBackground(background);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));

                TextView heading = new TextView(this);
                heading.setText((step.index + 1) + ". " + step.title);
                heading.setTextSize(14);
                heading.setTextColor(Ui.text(this));
                heading.setTypeface(null, android.graphics.Typeface.BOLD);
                row.addView(heading);

                TextView detail = Ui.meta(this, step.status + (step.capability.isEmpty() ? "" : " · " + step.capability));
                detail.setPadding(0, dp(2), 0, 0);
                row.addView(detail);

                if (step.observation != null && !step.observation.isEmpty()) {
                    TextView observation = new TextView(this);
                    observation.setText(step.observation);
                    observation.setTextSize(12);
                    observation.setTextColor(Ui.body(this));
                    observation.setPadding(0, dp(4), 0, 0);
                    row.addView(observation);
                }
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, dp(4), 0, dp(4));
                content.addView(row, rowLp);
            }
        }

        content.addView(Ui.section(this, T.s("taskdetail.audit", en)));
        java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT);
        int shown = 0;
        try {
            for (AgentStore.AuditEvent event : store.listRecentAuditEvents(200)) {
                if (!taskId.equals(event.taskId)) continue;
                content.addView(Ui.meta(this, clock.format(new java.util.Date(event.occurredAt)) + " · " + event.event));
                if (++shown >= 20) break;
            }
        } catch (Exception ignored) {
        }
        if (shown == 0) content.addView(Ui.meta(this, T.s("taskdetail.no_audit", en)));
        renderActions(task);
    }

    /** Start, pause, cancel and delete, so the page is not read-only. */
    private void renderActions(AgentStore.Task task) {
        actions.removeAllViews();
        boolean terminal = AgentStore.COMPLETED.equals(task.status) || AgentStore.CANCELLED.equals(task.status)
            || AgentStore.OUTCOME_UNKNOWN.equals(task.status);
        if (!terminal) {
            String label = AgentStore.ACTIVE.equals(task.status) ? T.s("ui.pause", en)
                : AgentStore.FAILED.equals(task.status) ? T.s("ui.retry", en)
                : AgentStore.PAUSED.equals(task.status) ? T.s("ui.resume", en) : T.s("ui.start", en);
            String target = AgentStore.ACTIVE.equals(task.status) ? AgentStore.PAUSED : AgentStore.ACTIVE;
            addAction(label, true, () -> transition(task, target));
            if (!AgentStore.ACTIVE.equals(task.status)) addAction(T.s("ui.cancel", en), false, () -> transition(task, AgentStore.CANCELLED));
        }
        addAction(T.s("ui.delete", en), false, () -> confirmDelete(task));
    }

    private void addAction(String label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setBackgroundColor(primary ? 0xFF0F172A : Ui.secondaryBg(this));
        button.setTextColor(primary ? 0xFFFFFFFF : Ui.secondaryFg(this));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(0, 0, dp(6), 0);
        actions.addView(button, params);
    }

    private void transition(AgentStore.Task task, String target) {
        try {
            store.transition(task.id, target);
            if (AgentStore.ACTIVE.equals(target)) {
                AgentScheduler.scheduleTask(this, store.getTask(task.id));
            } else {
                AgentScheduler.cancelTask(this, task.jobId);
            }
            render();
        } catch (Exception error) {
            android.widget.Toast.makeText(this, error.getMessage() == null ? T.s("common.operation_failed", en) : error.getMessage(),
                android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(AgentStore.Task task) {
        new android.app.AlertDialog.Builder(this)
            .setMessage(T.s("task.delete_body", en))
            .setNegativeButton(T.s("task.delete_keep", en), null)
            .setPositiveButton(T.s("task.delete_confirm", en), (dialog, which) -> {
                store.deleteTask(task.id);
                finish();
            })
            .show();
    }

    private String statusLabel(String status) {
        if (AgentStore.COMPLETED.equals(status)) return T.s("ui.completed", en);
        if (AgentStore.FAILED.equals(status)) return T.s("ui.failed_2", en);
        if (AgentStore.CANCELLED.equals(status)) return T.s("ui.cancelled", en);
        if (AgentStore.ACTIVE.equals(status)) return T.s("ui.scheduled_active", en);
        if (AgentStore.WAITING_FOR_USER.equals(status)) return T.s("ui.awaiting_user", en);
        if (AgentStore.PAUSED.equals(status)) return T.s("ui.paused", en);
        if (AgentStore.OUTCOME_UNKNOWN.equals(status)) return T.s("ui.outcome_unknown", en);
        return T.s("ui.pending_2", en);
    }

    @Override protected void onDestroy() {
        ticker.removeCallbacks(refreshLoop);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
