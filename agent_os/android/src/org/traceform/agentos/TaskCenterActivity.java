package org.traceform.agentos;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public final class TaskCenterActivity extends Activity {
    private AgentStore store;
    private boolean en;
    private LinearLayout rows;
    private ScrollView scroll;
    private Spinner filter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store = new AgentStore(this);
        en = "en".equals(getSharedPreferences("agent_os_prefs", 0).getString("app_lang", "zh"));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(this); scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(32, 40 + insets.getSystemWindowInsetTop(), 32, 32 + insets.getSystemWindowInsetBottom()); return insets;
        }); setContentView(scroll);
        text(root, en ? "Task center" : "任务中心");
        text(root, en ? "Cloud approvals expire with the process. Resume by reviewing a new request; file recovery never repeats a write."
            : "云请求授权不会跨进程保留，恢复时需重新预览；文件恢复只核验，不重复写入。");
        String[] labels = new String[TaskCenterState.GROUPS.length + 1]; labels[0] = en ? "All tasks" : "全部任务";
        for (int i = 0; i < TaskCenterState.GROUPS.length; i++) labels[i + 1] = TaskCenterState.label(TaskCenterState.GROUPS[i], en);
        filter = new Spinner(this); filter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels)); root.addView(filter);
        if (state != null) filter.setSelection(state.getInt("filter", 0));
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) { refresh(); }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        button(root, en ? "Refresh" : "刷新", this::refresh);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); root.addView(rows);
        button(root, en ? "Back" : "返回", this::finish);
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
            int y = scroll.getScrollY(); rows.removeAllViews(); List<AgentStore.Task> tasks = store.listTasks();
            boolean active = false;
            for (String group : TaskCenterState.GROUPS) {
                int position = filter.getSelectedItemPosition();
                if (position > 0 && !group.equals(TaskCenterState.GROUPS[position - 1])) continue;
                int count = 0; for (AgentStore.Task task : tasks) if (group.equals(TaskCenterState.group(task))) count++;
                text(rows, TaskCenterState.label(group, en) + " (" + count + ")");
                for (AgentStore.Task task : tasks) {
                    active |= AgentStore.ACTIVE.equals(task.status);
                    if (!group.equals(TaskCenterState.group(task))) continue;
                    text(rows, task.title + "\n" + task.status);
                    button(rows, en ? "Open task / continue review" : "打开任务 / 继续审核", () -> open(task.id));
                    if (AgentStore.ACTIVE.equals(task.status)) button(rows, en ? "Pause" : "暂停", () -> change(task.id, AgentStore.PAUSED));
                    if (AgentStore.PAUSED.equals(task.status)) button(rows, en ? "Resume local execution" : "恢复本地执行", () -> change(task.id, AgentStore.ACTIVE));
                    if (!AgentStore.COMPLETED.equals(task.status) && !AgentStore.CANCELLED.equals(task.status) && !AgentStore.FAILED.equals(task.status))
                        button(rows, en ? "Cancel / stop tracking" : "取消 / 停止跟踪", () -> new android.app.AlertDialog.Builder(this)
                            .setMessage(en ? "Cancel this task? A dispatched external effect cannot be undone here." : "取消此任务？已发出的外部动作无法在此撤回。")
                            .setNegativeButton(en ? "Keep" : "保留", null)
                            .setPositiveButton(en ? "Cancel task" : "取消任务", (dialog, which) -> change(task.id, AgentStore.CANCELLED)).show());
                }
            }
            scroll.post(() -> scroll.scrollTo(0, y));
            if (active) handler.postDelayed(poll, 5000);
        } catch (Exception error) { fail(error); }
    }
    private void open(String id) {
        try {
            AgentStore.Task task = store.getTask(id); if (task == null) throw new IllegalStateException("Task no longer exists");
            String route = TaskCenterState.destination(task); Intent intent;
            if ("document".equals(route)) intent = new Intent(this, DocumentTaskActivity.class).putExtra(DocumentTaskActivity.EXTRA_TASK_ID, id);
            else if ("review".equals(route)) intent = new Intent(this, TaskReviewActivity.class).putExtra(TaskReviewActivity.EXTRA_TASK_ID, id);
            else if ("external".equals(route)) intent = new Intent(this, ExternalActionActivity.class).putExtra(ExternalActionActivity.EXTRA_TASK_ID, id);
            else intent = new Intent(this, MainActivity.class).putExtra("focusTaskId", id);
            startActivity(intent);
        } catch (Exception error) { fail(error); }
    }
    private void change(String id, String status) {
        try {
            AgentStore.Task task = store.getTask(id); if (task == null) throw new IllegalStateException("Task no longer exists");
            store.transition(id, status);
            if (AgentStore.ACTIVE.equals(status)) {
                try { AgentScheduler.scheduleTask(this, store.getTask(id)); }
                catch (Exception error) { store.transition(id, task.status); throw error; }
            } else AgentScheduler.cancelTask(this, task.jobId);
            refresh();
        } catch (Exception error) { fail(error); }
    }
    private void text(LinearLayout root, String text) { TextView view = new TextView(this); view.setText(text); root.addView(view); }
    private void button(LinearLayout root, String text, Runnable action) { Button view = new Button(this); view.setText(text); view.setAllCaps(false); view.setOnClickListener(v -> action.run()); root.addView(view); }
    private void fail(Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
}
