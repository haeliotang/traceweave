package org.traceform.agentos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int CREATE_ARCHIVE_REQUEST = 1;
    private static final int OPEN_ARCHIVE_REQUEST = 2;
    private static final int SELECT_REMOTE_STATUS_REQUEST = 3;
    private static final String STATE_REMOTE_URI_EXPIRES_AT = "remoteUriExpiresAtElapsed";
    private static final long REMOTE_URI_LIFETIME_MILLIS = 5 * 60 * 1000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.ArrayList<Button> persistentButtons = new java.util.ArrayList<>();
    private final java.util.ArrayList<Button> taskButtons = new java.util.ArrayList<>();
    private final java.util.ArrayList<Button> memoryButtons = new java.util.ArrayList<>();
    private AgentStore store;
    private CapabilityBroker capabilityBroker;
    private EditText taskInput;
    private EditText memoryInput;
    private Spinner memoryKind;
    private LinearLayout taskList;
    private LinearLayout memoryList;
    private TextView activityStatus;
    private char[] pendingExportPassphrase;
    private boolean busy;
    private boolean destroyed;
    private String pendingRemoteStatusTaskId;
    private long remoteUriExpiresAtElapsed;
    private final Runnable remoteUriExpiry = this::expireRemoteUriGrant;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) remoteUriExpiresAtElapsed = state.getLong(STATE_REMOTE_URI_EXPIRES_AT);
        store = new AgentStore(this);
        capabilityBroker = new CapabilityBroker(this, store);
        buildScreen();
        refreshTasks();
        refreshMemories();
        scheduleRemoteUriExpiry();
    }

    @Override
    protected void onResume() {
        super.onResume();
        expireRemoteUriGrant();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putLong(STATE_REMOTE_URI_EXPIRES_AT, remoteUriExpiresAtElapsed);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(remoteUriExpiry);
        clearPendingExportPassphrase();
        io.execute(() -> store.close());
        io.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_ARCHIVE_REQUEST) {
            char[] passphrase = pendingExportPassphrase;
            pendingExportPassphrase = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                clear(passphrase);
                setBusy(false, "导出已取消");
                return;
            }
            writeArchive(data.getData(), passphrase);
        } else if (requestCode == OPEN_ARCHIVE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                promptImportPassphrase(data.getData());
            } else {
                setBusy(false, "导入已取消");
            }
        } else if (requestCode == SELECT_REMOTE_STATUS_REQUEST) {
            String taskId = pendingRemoteStatusTaskId;
            pendingRemoteStatusTaskId = null;
            Uri selectedUri = data == null ? null : data.getData();
            if (resultCode == RESULT_OK && selectedUri != null && taskId != null
                    && (data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                    && capabilityBroker.acceptRemoteUri(taskId, selectedUri)) {
                if (remoteUriExpiresAtElapsed == 0) {
                    remoteUriExpiresAtElapsed = SystemClock.elapsedRealtime() + REMOTE_URI_LIFETIME_MILLIS;
                    scheduleRemoteUriExpiry();
                }
                showMessage("已为此任务接收单条合成内容的临时读取权");
            } else {
                if (taskId != null) {
                    capabilityBroker.deny(taskId, AndroidRemoteStatusProvider.CAPABILITY,
                        AndroidRemoteStatusProvider.SCOPE);
                    capabilityBroker.releaseRemoteUri(taskId);
                }
                showMessage("未接收跨应用内容授权");
                if (selectedUri != null) {
                    restartMainActivityAfterRemoteGrantRelease();
                    return;
                }
            }
            refreshTasks();
        }
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(32), dp(24), dp(24));
        scroll.addView(page);

        TextView title = new TextView(this);
        title.setText("Agent OS 开发原型");
        title.setTextSize(28);
        page.addView(title);

        TextView note = new TextView(this);
        note.setText("本地任务与用户记忆原型。内容在本机加密保存；导出档案使用独立口令加密，便于迁移。当前不运行模型、不访问网络，也不调用 Traceform。\n\n本地能力只读取固定合成内容。跨应用能力必须先由你选择来源并接收单条内容的临时 URI 读取权，再为具体任务授权；能力授权最多 5 分钟有效，进程重启后清除。\n\n任务“进行中”只表示记录状态，不代表后台 Agent 正在执行。请先使用合成内容验证导入、导出和删除流程。");
        note.setTextSize(15);
        note.setPadding(0, 16, 0, 16);
        page.addView(note);

        LinearLayout archiveActions = new LinearLayout(this);
        archiveActions.setOrientation(LinearLayout.HORIZONTAL);
        addActionButton(archiveActions, "加密导出", view -> promptExportPassphrase());
        addActionButton(archiveActions, "导入档案", view -> chooseArchiveToImport());
        page.addView(archiveActions);

        activityStatus = new TextView(this);
        activityStatus.setText("本机数据仅在你选择时导出。导入会保留同 ID 的本地内容，删除墓碑优先。");
        activityStatus.setPadding(0, 4, 0, 8);
        page.addView(activityStatus);

        taskInput = new EditText(this);
        taskInput.setHint("输入一条合成任务，例如：整理本地示例笔记");
        taskInput.setMinLines(2);
        taskInput.setMaxLines(4);
        page.addView(taskInput);

        addPageButton(page, "新增本地任务", view -> createTask());

        TextView taskHeading = new TextView(this);
        taskHeading.setText("任务记录");
        taskHeading.setTextSize(20);
        taskHeading.setPadding(0, 24, 0, 8);
        page.addView(taskHeading);

        taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        page.addView(taskList);

        TextView memoryHeading = new TextView(this);
        memoryHeading.setText("用户记忆");
        memoryHeading.setTextSize(20);
        memoryHeading.setPadding(0, 24, 0, 8);
        page.addView(memoryHeading);

        memoryKind = new Spinner(this);
        memoryKind.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"偏好", "事实", "工作流"}));
        page.addView(memoryKind);

        memoryInput = new EditText(this);
        memoryInput.setHint("写一条由你提供的记忆");
        memoryInput.setMinLines(2);
        memoryInput.setMaxLines(5);
        page.addView(memoryInput);
        addPageButton(page, "保存记忆", view -> createMemory());

        memoryList = new LinearLayout(this);
        memoryList.setOrientation(LinearLayout.VERTICAL);
        page.addView(memoryList);
        setContentView(scroll);
    }

    private void createTask() {
        if (busy) return;
        try {
            store.createTask(taskInput.getText().toString());
            taskInput.setText("");
            refreshTasks();
        } catch (Exception error) {
            showError(error);
        }
    }

    private void createMemory() {
        if (busy) return;
        try {
            store.createMemory(memoryKindValue(), memoryInput.getText().toString());
            memoryInput.setText("");
            refreshMemories();
        } catch (Exception error) {
            showError(error);
        }
    }

    private String memoryKindValue() {
        int position = memoryKind.getSelectedItemPosition();
        if (position == 0) return AgentStore.MEMORY_PREFERENCE;
        if (position == 1) return AgentStore.MEMORY_FACT;
        return AgentStore.MEMORY_WORKFLOW;
    }

    private void refreshTasks() {
        if (taskList == null || store == null) return;
        try {
            taskButtons.clear();
            taskList.removeAllViews();
            List<AgentStore.Task> tasks = store.listTasks();
            if (tasks.isEmpty()) {
                addEmptyMessage(taskList, "还没有任务");
                return;
            }
            for (AgentStore.Task task : tasks) addTaskCard(task);
        } catch (Exception error) {
            showError(error);
        }
    }

    private void refreshMemories() {
        if (memoryList == null || store == null) return;
        try {
            memoryButtons.clear();
            memoryList.removeAllViews();
            List<AgentStore.Memory> memories = store.listMemories();
            if (memories.isEmpty()) {
                addEmptyMessage(memoryList, "还没有记忆");
                return;
            }
            for (AgentStore.Memory memory : memories) addMemoryCard(memory);
        } catch (Exception error) {
            showError(error);
        }
    }

    private void addTaskCard(AgentStore.Task task) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 14, 0, 14);

        TextView description = new TextView(this);
        description.setText(task.title);
        description.setTextSize(17);
        card.addView(description);

        TextView metadata = new TextView(this);
        metadata.setText(statusLabel(task.status) + " · "
            + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(task.updatedAt)));
        metadata.setPadding(0, 4, 0, 4);
        card.addView(metadata);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (AgentStore.QUEUED.equals(task.status) || AgentStore.PAUSED.equals(task.status)) {
            addTaskButton(actions, AgentStore.QUEUED.equals(task.status) ? "开始" : "恢复", task, AgentStore.ACTIVE);
        }
        if (AgentStore.ACTIVE.equals(task.status)) addTaskButton(actions, "暂停", task, AgentStore.PAUSED);
        if (AgentStore.ACTIVE.equals(task.status) || AgentStore.PAUSED.equals(task.status)) {
            addTaskButton(actions, "完成", task, AgentStore.COMPLETED);
        }
        if (AgentStore.QUEUED.equals(task.status) || AgentStore.ACTIVE.equals(task.status) || AgentStore.PAUSED.equals(task.status))
            addTaskButton(actions, "取消", task, AgentStore.CANCELLED);
        addCardActionButton(actions, "删除", view -> confirmDeleteTask(task), taskButtons);
        card.addView(actions);

        if (!AgentStore.COMPLETED.equals(task.status) && !AgentStore.CANCELLED.equals(task.status)) {
            addCapabilityControls(card, task);
        }

        addDivider(taskList, card);
    }

    private void addMemoryCard(AgentStore.Memory memory) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 14, 0, 14);

        TextView kind = new TextView(this);
        kind.setText(memoryKindLabel(memory.kind));
        kind.setTextSize(14);
        card.addView(kind);

        TextView content = new TextView(this);
        content.setText(memory.content);
        content.setTextSize(17);
        content.setPadding(0, 4, 0, 4);
        card.addView(content);

        TextView metadata = new TextView(this);
        metadata.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(memory.updatedAt)));
        card.addView(metadata);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(actions, "删除记忆", view -> confirmDeleteMemory(memory), memoryButtons);
        card.addView(actions);
        addDivider(memoryList, card);
    }

    private void addTaskButton(LinearLayout parent, String label, AgentStore.Task task, String status) {
        addCardActionButton(parent, label, view -> {
            if (busy) return;
            try {
                store.transition(task.id, status);
                refreshTasks();
            } catch (Exception error) {
                showError(error);
            }
        }, taskButtons);
    }

    private void addCapabilityControls(LinearLayout card, AgentStore.Task task) {
        addProviderControls(card, task, "示例便笺", CapabilityBroker.DEMO_NOTE_CAPABILITY,
            CapabilityBroker.DEMO_NOTE_SCOPE);
        addProviderControls(card, task, "合成状态", CapabilityBroker.DEMO_STATUS_CAPABILITY,
            CapabilityBroker.DEMO_STATUS_SCOPE);
        LinearLayout remoteActions = new LinearLayout(this);
        remoteActions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(remoteActions, "选择跨应用状态来源", view -> selectRemoteStatus(task), taskButtons);
        card.addView(remoteActions);
        if (capabilityBroker.hasRemoteResource(task.id)) {
            addProviderControls(card, task, "跨应用状态", AndroidRemoteStatusProvider.CAPABILITY,
                AndroidRemoteStatusProvider.SCOPE);
        }
    }

    private void selectRemoteStatus(AgentStore.Task task) {
        Intent intent = new Intent(this, RemoteUriPickerActivity.class);
        pendingRemoteStatusTaskId = task.id;
        try {
            startActivityForResult(intent, SELECT_REMOTE_STATUS_REQUEST);
        } catch (ActivityNotFoundException error) {
            pendingRemoteStatusTaskId = null;
            showMessage("没有可用的合成内容来源");
        }
    }

    private void addProviderControls(LinearLayout card, AgentStore.Task task, String label,
            String capability, String scope) {
        long grantRemaining = capabilityBroker.remainingMillis(task.id, capability, scope);
        TextView capabilityStatus = new TextView(this);
        capabilityStatus.setText(grantRemaining > 0
            ? label + "授权剩余约 " + ((grantRemaining + 59_999) / 60_000) + " 分钟"
            : label + "：未授权");
        card.addView(capabilityStatus);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(actions, "授权" + label, view -> confirmCapabilityGrant(task, label, capability, scope), taskButtons);
        addCardActionButton(actions, "读取" + label, view -> invokeCapability(task, capability, scope), taskButtons);
        card.addView(actions);

        LinearLayout revokeActions = new LinearLayout(this);
        revokeActions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(revokeActions, "撤销" + label + "授权",
            view -> revokeCapability(task, label, capability, scope), taskButtons);
        card.addView(revokeActions);
    }

    private void confirmCapabilityGrant(AgentStore.Task task, String label, String capability, String scope) {
        new AlertDialog.Builder(this)
            .setTitle("授权" + label + "？")
            .setMessage("允许此任务读取固定的" + label + "。授权仅限本任务和此能力范围，最多 5 分钟有效；进程重启后失效，也可随时撤销。不会读取用户记忆或 Traceform 数据。")
            .setNegativeButton("拒绝", (dialog, which) -> {
                try {
                    capabilityBroker.deny(task.id, capability, scope);
                    showMessage("已拒绝" + label);
                } catch (Exception error) {
                    showError(error);
                }
            })
            .setPositiveButton("授权 5 分钟", (dialog, which) -> {
                try {
                    capabilityBroker.grant(task.id, capability, scope);
                    showMessage("已授权此任务读取" + label);
                } catch (Exception error) {
                    showError(error);
                }
                refreshTasks();
            })
            .show();
    }

    private void invokeCapability(AgentStore.Task task, String capability, String scope) {
        try {
            showMessage(capabilityBroker.invoke(task.id, capability, scope));
        } catch (Exception error) {
            showError(error);
        }
        refreshTasks();
    }

    private void revokeCapability(AgentStore.Task task, String label, String capability, String scope) {
        try {
            showMessage(capabilityBroker.revoke(task.id, capability, scope)
                ? label + "授权已撤销" : "当前没有有效的" + label + "授权");
        } catch (Exception error) {
            showError(error);
        }
        if (AndroidRemoteStatusProvider.CAPABILITY.equals(capability)) {
            restartMainActivityAfterRemoteGrantRelease();
            return;
        }
        refreshTasks();
    }

    private void restartMainActivityAfterRemoteGrantRelease() {
        mainHandler.removeCallbacks(remoteUriExpiry);
        remoteUriExpiresAtElapsed = 0;
        Intent restart = new Intent(this, MainActivity.class);
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(restart);
        finish();
    }

    private void scheduleRemoteUriExpiry() {
        if (remoteUriExpiresAtElapsed == 0 || destroyed) return;
        long delay = remoteUriExpiresAtElapsed - SystemClock.elapsedRealtime();
        if (delay <= 0) {
            expireRemoteUriGrant();
        } else {
            mainHandler.postDelayed(remoteUriExpiry, delay);
        }
    }

    private void expireRemoteUriGrant() {
        if (remoteUriExpiresAtElapsed > 0
                && SystemClock.elapsedRealtime() >= remoteUriExpiresAtElapsed && !destroyed) {
            showMessage("跨应用临时读取权已过期，请重新打开 Agent OS");
            finishAndRemoveTask();
        }
    }

    private void confirmDeleteTask(AgentStore.Task task) {
        new AlertDialog.Builder(this)
            .setTitle("删除任务记录？")
            .setMessage("删除会清除任务和对应的本机审计记录，并保存一个删除标记，阻止旧档案重新导入这条任务。")
            .setNegativeButton("保留", null)
            .setPositiveButton("删除", (dialog, which) -> {
                try {
                    store.deleteTask(task.id);
                    refreshTasks();
                } catch (Exception error) {
                    showError(error);
                }
            })
            .show();
    }

    private void confirmDeleteMemory(AgentStore.Memory memory) {
        new AlertDialog.Builder(this)
            .setTitle("删除这条记忆？")
            .setMessage("删除标记会随加密档案迁移，旧档案不能恢复这条记忆。")
            .setNegativeButton("保留", null)
            .setPositiveButton("删除", (dialog, which) -> {
                try {
                    store.deleteMemory(memory.id);
                    refreshMemories();
                } catch (Exception error) {
                    showError(error);
                }
            })
            .show();
    }

    private void promptExportPassphrase() {
        if (busy) return;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        EditText first = passwordField("新建档案口令");
        EditText second = passwordField("再次输入口令");
        fields.addView(first);
        fields.addView(second);
        new AlertDialog.Builder(this)
            .setTitle("加密导出")
            .setMessage("请使用至少 12 个字符的长口令，并妥善保存。忘记口令后无法恢复档案内容。")
            .setView(fields)
            .setNegativeButton("取消", null)
            .setPositiveButton("选择保存位置", (dialog, which) -> {
                char[] passphrase = first.getText().toString().toCharArray();
                char[] confirmation = second.getText().toString().toCharArray();
                first.setText("");
                second.setText("");
                if (!Arrays.equals(passphrase, confirmation)) {
                    clear(passphrase);
                    clear(confirmation);
                    showMessage("两次输入的口令不一致");
                    return;
                }
                clear(confirmation);
                if (Character.codePointCount(passphrase, 0, passphrase.length) < 12) {
                    clear(passphrase);
                    showMessage("口令至少需要 12 个字符");
                    return;
                }
                pendingExportPassphrase = passphrase;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, "agent-os-memory-" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()) + ".taos");
                try {
                    setBusy(true, "请选择档案保存位置");
                    startActivityForResult(intent, CREATE_ARCHIVE_REQUEST);
                } catch (ActivityNotFoundException error) {
                    clearPendingExportPassphrase();
                    setBusy(false, "");
                    showMessage("系统没有可用的文件保存位置");
                }
            })
            .show();
    }

    private void chooseArchiveToImport() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            setBusy(true, "请选择 Agent OS 加密档案");
            startActivityForResult(intent, OPEN_ARCHIVE_REQUEST);
        } catch (ActivityNotFoundException error) {
            setBusy(false, "");
            showMessage("系统没有可用的文件选择器");
        }
    }

    private void promptImportPassphrase(Uri uri) {
        EditText input = passwordField("档案口令");
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(input);
        new AlertDialog.Builder(this)
            .setTitle("解锁并导入档案")
            .setMessage("导入会新增本机不存在的记录；同 ID 冲突保留本机内容，档案中的删除标记优先应用。")
            .setView(holder)
            .setNegativeButton("取消", (dialog, which) -> setBusy(false, "导入已取消"))
            .setPositiveButton("导入", (dialog, which) -> {
                char[] passphrase = input.getText().toString().toCharArray();
                input.setText("");
                if (passphrase.length == 0) {
                    setBusy(false, "");
                    showMessage("请输入档案口令");
                    return;
                }
                importArchive(uri, passphrase);
            })
            .setOnCancelListener(dialog -> setBusy(false, "导入已取消"))
            .show();
    }

    private EditText passwordField(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    private void writeArchive(Uri uri, char[] passphrase) {
        setBusy(true, "正在加密并保存档案…");
        io.execute(() -> {
            Exception failure = null;
            try {
                byte[] archive = store.exportArchive(passphrase);
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException("无法打开所选保存位置");
                    output.write(archive);
                    output.flush();
                } finally {
                    Arrays.fill(archive, (byte) 0);
                }
            } catch (Exception error) {
                failure = error;
            } finally {
                clear(passphrase);
            }
            Exception result = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                setBusy(false, result == null ? "加密档案已保存" : "导出失败");
                if (result != null) showError(result);
            });
        });
    }

    private void importArchive(Uri uri, char[] passphrase) {
        setBusy(true, "正在解密、校验并合并档案…");
        io.execute(() -> {
            AgentStore.ImportResult result = null;
            Exception failure = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("无法打开所选档案");
                result = store.importArchive(input, passphrase);
            } catch (Exception error) {
                failure = error;
            } finally {
                clear(passphrase);
            }
            AgentStore.ImportResult importResult = result;
            Exception importFailure = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                setBusy(false, importFailure == null ? "档案已导入" : "导入失败");
                if (importFailure != null) {
                    showError(importFailure);
                } else {
                    refreshTasks();
                    refreshMemories();
                    showMessage("导入完成：任务 " + importResult.tasksImported + " 条，记忆 "
                        + importResult.memoriesImported + " 条，删除标记 " + importResult.tombstonesApplied
                        + " 条；跳过冲突或已删除记录 " + importResult.recordsSkipped + " 条");
                }
            });
        });
    }

    private void addActionButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        button.setEnabled(!busy);
        persistentButtons.add(button);
        parent.addView(button, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    private void addPageButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        button.setEnabled(!busy);
        persistentButtons.add(button);
        parent.addView(button);
    }

    private void addCardActionButton(LinearLayout parent, String label, View.OnClickListener listener,
            java.util.ArrayList<Button> buttonGroup) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        button.setEnabled(!busy);
        buttonGroup.add(button);
        parent.addView(button, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    private void addDivider(LinearLayout parent, LinearLayout child) {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFDDDDDD);
        child.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        parent.addView(child);
    }

    private void addEmptyMessage(LinearLayout parent, String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        empty.setPadding(0, 8, 0, 8);
        parent.addView(empty);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        if (activityStatus != null && message != null && !message.isEmpty()) activityStatus.setText(message);
        for (Button button : persistentButtons) button.setEnabled(!value);
        for (Button button : taskButtons) button.setEnabled(!value);
        for (Button button : memoryButtons) button.setEnabled(!value);
    }

    private void clearPendingExportPassphrase() {
        clear(pendingExportPassphrase);
        pendingExportPassphrase = null;
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    private String memoryKindLabel(String kind) {
        if (AgentStore.MEMORY_PREFERENCE.equals(kind)) return "偏好";
        if (AgentStore.MEMORY_FACT.equals(kind)) return "事实";
        if (AgentStore.MEMORY_WORKFLOW.equals(kind)) return "工作流";
        return "未知类型";
    }

    private String statusLabel(String status) {
        if (AgentStore.QUEUED.equals(status)) return "待处理";
        if (AgentStore.ACTIVE.equals(status)) return "进行中（仅状态记录）";
        if (AgentStore.PAUSED.equals(status)) return "已暂停";
        if (AgentStore.COMPLETED.equals(status)) return "已完成";
        if (AgentStore.CANCELLED.equals(status)) return "已取消";
        return "未知状态";
    }

    private void showError(Exception error) {
        String message = error.getMessage() == null ? "操作失败" : error.getMessage();
        showMessage(message);
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
