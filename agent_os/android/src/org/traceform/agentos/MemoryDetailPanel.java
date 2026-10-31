package org.traceform.agentos;

import android.app.AlertDialog;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;

/** Memory detail, edit, delete and source review dialogs, extracted from MainActivity. */
final class MemoryDetailPanel {
    private final MainActivity host;

    MemoryDetailPanel(MainActivity host) {
        this.host = host;
    }

    void showDetail(AgentStore.Memory memory) {
        boolean en = host.english();
        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(host.dpRef(16), host.dpRef(8), host.dpRef(16), 0);

        TextView content = new TextView(host);
        content.setText(memory.content);
        content.setTextSize(13);
        content.setTextColor(0xFF1E293B);
        content.setTextIsSelectable(true);
        body.addView(content);

        String source;
        if (AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(memory.source)) {
            source = memory.sourceReference.startsWith("confirmed:")
                ? (en ? "User-confirmed suggestion" : "用户已确认的建议")
                : (en ? "Legacy inference · unreviewed" : "历史自动推断 · 未确认");
        } else if (AgentStore.MEMORY_SOURCE_TRACEFORM.equals(memory.source)) {
            source = "Traceform";
        } else {
            source = en ? "User provided" : "你提供";
        }
        TextView meta = Ui.meta(host, source + " · " + memory.kind);
        meta.setPadding(0, host.dpRef(4), 0, host.dpRef(6));
        body.addView(meta);

        TextView metadata = Ui.meta(host, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(new Date(memory.updatedAt)));
        if (memory.userEdited) metadata.append(en ? " · User edited" : " · 用户已修正");
        if (memory.expiresAt > 0) metadata.append((memory.isExpired(System.currentTimeMillis())
            ? (en ? " · Expired " : " · 已到期 ") : (en ? " · Expires " : " · 到期 "))
            + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(memory.expiresAt)));
        body.addView(metadata);

        Button edit = new Button(host);
        edit.setText(en ? "Edit / expiration" : "修改 / 有效期");
        edit.setAllCaps(false);
        edit.setTextSize(12);
        edit.setOnClickListener(view -> { edit(memory); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(40));
        lp.setMargins(0, host.dpRef(6), 0, 0);
        body.addView(edit, lp);

        if (AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(memory.source) && !memory.sourceReference.startsWith("confirmed:")) {
            Button review = new Button(host);
            review.setText(en ? "Review legacy inference" : "审核历史推断");
            review.setAllCaps(false);
            review.setTextSize(12);
            review.setOnClickListener(view -> new AlertDialog.Builder(host)
                .setTitle(en ? "Confirm this inference?" : "确认这条推断？")
                .setMessage(memory.content + "\n\n" + (en ? "Source: " : "来源：") + memory.sourceReference)
                .setNegativeButton(en ? "Keep untrusted" : "保留为未确认", null)
                .setPositiveButton(en ? "Confirm for future tasks" : "确认供后续任务使用", (dialog, which) -> {
                    try { host.storeRef().reviewLegacyMemory(memory.id, memory.updatedAt); host.refreshAfterSync(); }
                    catch (Exception error) { host.failWith(error); }
                }).show());
            LinearLayout.LayoutParams reviewLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(40));
            reviewLp.setMargins(0, host.dpRef(4), 0, 0);
            body.addView(review, reviewLp);
        }

        Button references = new Button(host);
        references.setText(en ? "Tasks using this memory" : "引用此记忆的任务");
        references.setAllCaps(false);
        references.setTextSize(12);
        references.setOnClickListener(view -> {
            try {
                StringBuilder text = new StringBuilder();
                for (AgentStore.Task task : host.storeRef().memoryReferences(memory.id))
                    text.append(task.title).append(" · ").append(task.status).append("\n");
                new AlertDialog.Builder(host).setTitle(en ? "Historical task references" : "历史任务引用")
                    .setMessage(text.length() == 0 ? (en ? "No task snapshots reference this memory." : "没有任务快照引用此记忆。") : text.toString())
                    .setPositiveButton(en ? "Close" : "关闭", null).show();
            } catch (Exception error) { host.failWith(error); }
        });
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(40));
        refLp.setMargins(0, host.dpRef(4), 0, 0);
        body.addView(references, refLp);

        new AlertDialog.Builder(host)
            .setTitle(en ? "Memory detail" : "记忆详情")
            .setView(body)
            .setNegativeButton(en ? "Delete" : "删除", (dialog, which) -> confirmDelete(memory))
            .setPositiveButton(en ? "Close" : "关闭", null)
            .show();
    }


    private void confirmDelete(AgentStore.Memory memory) {
        boolean en = host.english();
        new AlertDialog.Builder(host)
            .setTitle(en ? "Delete this memory?" : "删除这条记忆？")
            .setMessage(en ? "A tombstone prevents old archives from restoring this record. Historical task snapshots and generated files retain their copies; delete those separately. You can review task references first."
                : "删除标记会随加密档案迁移，旧档案不能恢复这条记忆。历史任务快照和已生成文件中的副本仍保留，需要分别删除。可先查看任务引用。")
            .setNegativeButton(en ? "Keep" : "保留", null)
            .setPositiveButton(en ? "Delete" : "删除", (dialog, which) -> {
                try {
                    host.storeRef().deleteMemoriesReviewed(java.util.Collections.singletonList(memory));
                    host.refreshAfterSync();
                } catch (Exception error) {
                    host.failWith(error);
                }
            })
            .show();
    }


    private void edit(AgentStore.Memory memory) {
        boolean en = host.english();
        LinearLayout panel = new LinearLayout(host); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(36, 12, 36, 0);
        EditText input = new EditText(host); input.setText(memory.content); input.setMinLines(2);
        if (WorkflowTemplate.isTemplate(memory)) input.setEnabled(false);
        panel.addView(input);
        TextView help = new TextView(host);
        help.setText(en ? "Expiration excludes this memory from new task selections; existing task snapshots remain."
            : "到期后不再用于新任务选择；已有任务快照仍保留。工作流模板内容请在工作流界面管理。");
        panel.addView(help);
        Spinner expiry = new Spinner(host); expiry.setAdapter(new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item,
            en ? new String[]{"Keep current expiration", "No expiration", "Expire in 7 days", "Expire in 30 days", "Expire in 90 days"}
                : new String[]{"保持当前有效期", "不设到期时间", "7 天后到期", "30 天后到期", "90 天后到期"}));
        panel.addView(expiry);
        new AlertDialog.Builder(host).setTitle(en ? "Edit memory" : "修改记忆").setView(panel)
            .setNegativeButton(en ? "Cancel" : "取消", null)
            .setPositiveButton(en ? "Save" : "保存", (dialog, which) -> {
                try {
                    int choice = expiry.getSelectedItemPosition();
                    long expiresAt = choice == 0 ? memory.expiresAt : choice == 1 ? 0
                        : System.currentTimeMillis() + (choice == 2 ? 7L : choice == 3 ? 30L : 90L) * 24 * 60 * 60 * 1000;
                    host.storeRef().reviseMemory(memory, input.getText().toString(), expiresAt);
                    host.refreshAfterSync();
                } catch (Exception error) { host.failWith(error); }
            }).show();
    }


    void reviewBySource() {
        boolean en = host.english();
        String[] labels = en ? new String[]{"User-provided", "Traceform", "All confirmed / inferred suggestions (including sensor)", "Sensor observations only"}
            : new String[]{"你提供", "Traceform", "全部 Agent 建议（含感知）", "仅感知观察"};
        new AlertDialog.Builder(host).setTitle(en ? "Choose a source" : "选择来源")
            .setItems(labels, (dialog, which) -> {
                try {
                    java.util.ArrayList<AgentStore.Memory> selected = new java.util.ArrayList<>();
                    for (AgentStore.Memory memory : host.storeRef().listMemories()) {
                        if (!MemorySources.matches(memory, which)) continue;
                        selected.add(memory);
                    }
                    String message = (en ? "Delete " : "删除 ") + selected.size() + (en ? " selected records from " : " 条来源为 ") + labels[which]
                        + (en ? ". Workflow templates in this source are included. Existing task snapshots and files remain. Changes since this preview are rejected."
                            : " 的记录（包括此来源的工作流模板）。已有任务快照和文件仍保留。预览后发生变化的记录将拒绝删除。");
                    new AlertDialog.Builder(host).setTitle(en ? "Review deletion" : "确认删除范围").setMessage(message)
                        .setNegativeButton(en ? "Cancel" : "取消", null)
                        .setPositiveButton(en ? "Delete selected records" : "删除选中记录", (confirm, button) -> {
                            try { host.storeRef().deleteMemoriesReviewed(selected); host.refreshAfterSync(); } catch (Exception error) { host.failWith(error); }
                        }).show();
                } catch (Exception error) { host.failWith(error); }
            }).setNegativeButton(en ? "Cancel" : "取消", null).show();
    }

}
