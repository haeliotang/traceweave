package org.traceform.agentos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** Compact task list: one row per task, secondary actions behind a single menu. */
public final class TaskCenterActivity extends Activity {
    private AgentStore store;
    private boolean en;
    private LinearLayout rows;
    private ScrollView scroll;
    private Spinner filter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AgentStore(this);
        en = "en".equals(getSharedPreferences("agent_os_prefs", 0).getString("app_lang", "zh"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(Ui.dp(this, 12), Ui.dp(this, 12) + insets.getSystemWindowInsetTop(),
                Ui.dp(this, 12), Ui.dp(this, 12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);

        TextView heading = Ui.title(this, T.s("taskcenter.heading", en));
        heading.setTextSize(15);
        root.addView(heading);
        TextView note = Ui.meta(this, T.s("taskcenter.note", en));
        note.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        root.addView(note);

        String[] labels = new String[TaskCenterState.GROUPS.length + 1];
        labels[0] = T.s("taskcenter.all", en);
        for (int index = 0; index < TaskCenterState.GROUPS.length; index++)
            labels[index + 1] = TaskCenterState.label(TaskCenterState.GROUPS[index], en);
        filter = new Spinner(this);
        filter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        root.addView(filter, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));
        if (state != null) filter.setSelection(state.getInt("filter", 0));
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { refresh(); }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, Ui.dp(this, 8), 0, 0);
        Button refresh = new Button(this);
        refresh.setText(T.s("taskcenter.refresh", en));
        refresh.setAllCaps(false);
        refresh.setTextSize(12);
        refresh.setOnClickListener(view -> refresh());
        footer.addView(refresh, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        Button back = new Button(this);
        back.setText(T.s("taskcenter.back", en));
        back.setAllCaps(false);
        back.setTextSize(12);
        back.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f);
        backLp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        footer.addView(back, backLp);
        root.addView(footer);
    }

    @Override protected void onResume() {
        super.onResume();
        try { store.recoverExternalActions(); AgentScheduler.reconcileActiveTasks(this, store); }
        catch (Exception error) { fail(error); }
        refresh();
    }
    @Override protected void onPause() { handler.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { store.close(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("filter", filter.getSelectedItemPosition()); super.onSaveInstanceState(state); }

    private void refresh() {
        if (rows == null) return;
        handler.removeCallbacks(poll);
        try {
            int offset = scroll.getScrollY();
            rows.removeAllViews();
            List<AgentStore.Task> tasks = store.listTasks();
            boolean active = false;
            int position = filter.getSelectedItemPosition();
            for (String group : TaskCenterState.GROUPS) {
                if (position > 0 && !group.equals(TaskCenterState.GROUPS[position - 1])) continue;
                int count = 0;
                for (AgentStore.Task task : tasks) if (group.equals(TaskCenterState.group(task))) count++;
                if (count == 0) continue;
                TextView header = Ui.section(this, TaskCenterState.label(group, en) + " · " + count);
                rows.addView(header);
                boolean any = false;
                for (AgentStore.Task task : tasks) {
                    if (!group.equals(TaskCenterState.group(task))) continue;
                    any = true;
                    active |= AgentStore.ACTIVE.equals(task.status);
                    rows.addView(taskRow(task));
                }
                if (!any) {
                    TextView empty = Ui.meta(this, T.s("taskcenter.empty", en));
                    empty.setPadding(0, 0, 0, Ui.dp(this, 4));
                    rows.addView(empty);
                }
            }
            scroll.post(() -> scroll.scrollTo(0, offset));
            if (active) handler.postDelayed(poll, 5000);
        } catch (Exception error) { fail(error); }
    }

    private LinearLayout taskRow(AgentStore.Task task) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(task.title);
        title.setTextSize(13);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(Ui.COLOR_TEXT);
        textColumn.addView(title);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, Ui.dp(this, 2), 0, 0);
        metaRow.addView(Ui.chip(this, task.status, statusBackground(task.status), statusForeground(task.status)));
        TextView date = Ui.meta(this, shortDate(task));
        date.setPadding(Ui.dp(this, 6), 0, 0, 0);
        metaRow.addView(date);
        textColumn.addView(metaRow);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button open = new Button(this);
        open.setText(T.s("taskcenter.open", en));
        open.setAllCaps(false);
        open.setTextSize(11);
        open.setMinWidth(0);
        open.setMinimumWidth(0);
        open.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        open.setOnClickListener(view -> open(task.id));
        row.addView(open, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40)));

        Button more = new Button(this);
        more.setText("⋯");
        more.setAllCaps(false);
        more.setTextSize(14);
        more.setMinWidth(0);
        more.setMinimumWidth(0);
        more.setPadding(0, 0, 0, 0);
        more.setOnClickListener(view -> showActions(task));
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        moreLp.setMargins(Ui.dp(this, 4), 0, 0, 0);
        row.addView(more, moreLp);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE2E8F0);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row);
        wrapper.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return wrapper;
    }

    private void showActions(AgentStore.Task task) {
        final java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        final java.util.ArrayList<String> actions = new java.util.ArrayList<>();
        labels.add(T.s("taskcenter.open", en)); actions.add("open");
        if (AgentStore.ACTIVE.equals(task.status)) { labels.add(T.s("taskcenter.pause", en)); actions.add("pause"); }
        if (AgentStore.PAUSED.equals(task.status)) { labels.add(T.s("taskcenter.resume", en)); actions.add("resume"); }
        boolean cancellable = !AgentStore.COMPLETED.equals(task.status) && !AgentStore.CANCELLED.equals(task.status)
            && !AgentStore.FAILED.equals(task.status);
        if (cancellable) { labels.add(T.s("taskcenter.cancel", en)); actions.add("cancel"); }
        new AlertDialog.Builder(this)
            .setTitle(task.title)
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                String action = actions.get(which);
                if ("open".equals(action)) open(task.id);
                else if ("pause".equals(action)) change(task.id, AgentStore.PAUSED);
                else if ("resume".equals(action)) change(task.id, AgentStore.ACTIVE);
                else if ("cancel".equals(action)) new AlertDialog.Builder(this)
                    .setMessage(en ? "Cancel this task? A dispatched external effect cannot be undone here."
                                   : "取消此任务？已发出的外部动作无法在此撤回。")
                    .setNegativeButton(en ? "Keep" : "保留", null)
                    .setPositiveButton(T.s("taskcenter.cancel", en), (confirm, ignored) -> change(task.id, AgentStore.CANCELLED))
                    .show();
            })
            .show();
    }

    private int statusBackground(String status) {
        if (AgentStore.COMPLETED.equals(status)) return 0xFFDCFCE7;
        if (AgentStore.FAILED.equals(status) || AgentStore.CANCELLED.equals(status)) return 0xFFFEE2E2;
        if (AgentStore.ACTIVE.equals(status)) return 0xFFDBEAFE;
        return 0xFFF1F5F9;
    }

    private int statusForeground(String status) {
        if (AgentStore.COMPLETED.equals(status)) return 0xFF166534;
        if (AgentStore.FAILED.equals(status) || AgentStore.CANCELLED.equals(status)) return 0xFF991B1B;
        if (AgentStore.ACTIVE.equals(status)) return 0xFF1D4ED8;
        return 0xFF475569;
    }

    private String shortDate(AgentStore.Task task) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
            .format(new java.util.Date(task.createdAt));
    }

    private void open(String id) {
        try {
            AgentStore.Task task = store.getTask(id);
            if (task == null) throw new IllegalStateException("Task no longer exists");
            String route = TaskCenterState.destination(task);
            Intent intent;
            if ("document".equals(route)) intent = new Intent(this, DocumentTaskActivity.class).putExtra(DocumentTaskActivity.EXTRA_TASK_ID, id);
            else if ("review".equals(route)) intent = new Intent(this, TaskReviewActivity.class).putExtra(TaskReviewActivity.EXTRA_TASK_ID, id);
            else if ("external".equals(route)) intent = new Intent(this, ExternalActionActivity.class).putExtra(ExternalActionActivity.EXTRA_TASK_ID, id);
            else intent = new Intent(this, MainActivity.class).putExtra("focusTaskId", id);
            startActivity(intent);
        } catch (Exception error) { fail(error); }
    }

    private void change(String id, String status) {
        try {
            AgentStore.Task task = store.getTask(id);
            if (task == null) throw new IllegalStateException("Task no longer exists");
            store.transition(id, status);
            if (AgentStore.ACTIVE.equals(status)) {
                try { AgentScheduler.scheduleTask(this, store.getTask(id)); }
                catch (Exception error) { store.transition(id, task.status); throw error; }
            } else AgentScheduler.cancelTask(this, task.jobId);
            refresh();
        } catch (Exception error) { fail(error); }
    }

    private void fail(Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
}
