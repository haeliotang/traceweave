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
                ? (T.s("memorydetailpanel.1", en))
                : (T.s("memorydetailpanel.2", en));
        } else if (AgentStore.MEMORY_SOURCE_TRACEFORM.equals(memory.source)) {
            source = "Traceform";
        } else {
            source = T.s("memorydetailpanel.3", en);
        }
        TextView meta = Ui.meta(host, source + " · " + memory.kind);
        meta.setPadding(0, host.dpRef(4), 0, host.dpRef(6));
        body.addView(meta);

        TextView metadata = Ui.meta(host, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(new Date(memory.updatedAt)));
        if (memory.userEdited) metadata.append(T.s("memorydetailpanel.4", en));
        if (memory.expiresAt > 0) metadata.append((memory.isExpired(System.currentTimeMillis())
            ? (T.s("memorydetailpanel.5", en)) : (T.s("memorydetailpanel.6", en)))
            + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(memory.expiresAt)));
        body.addView(metadata);

        Button edit = new Button(host);
        edit.setText(T.s("memorydetailpanel.7", en));
        edit.setAllCaps(false);
        edit.setTextSize(12);
        edit.setOnClickListener(view -> { edit(memory); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(40));
        lp.setMargins(0, host.dpRef(6), 0, 0);
        body.addView(edit, lp);

        if (AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(memory.source) && !memory.sourceReference.startsWith("confirmed:")) {
            Button review = new Button(host);
            review.setText(T.s("memorydetailpanel.8", en));
            review.setAllCaps(false);
            review.setTextSize(12);
            review.setOnClickListener(view -> new AlertDialog.Builder(host)
                .setTitle(T.s("memorydetailpanel.9", en))
                .setMessage(memory.content + "\n\n" + (T.s("memorydetailpanel.10", en)) + memory.sourceReference)
                .setNegativeButton(T.s("memorydetailpanel.11", en), null)
                .setPositiveButton(T.s("memorydetailpanel.12", en), (dialog, which) -> {
                    try { host.storeRef().reviewLegacyMemory(memory.id, memory.updatedAt); host.refreshAfterSync(); }
                    catch (Exception error) { host.failWith(error); }
                }).show());
            LinearLayout.LayoutParams reviewLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(40));
            reviewLp.setMargins(0, host.dpRef(4), 0, 0);
            body.addView(review, reviewLp);
        }

        Button references = new Button(host);
        references.setText(T.s("memorydetailpanel.13", en));
        references.setAllCaps(false);
        references.setTextSize(12);
        references.setOnClickListener(view -> {
            try {
                StringBuilder text = new StringBuilder();
                for (AgentStore.Task task : host.storeRef().memoryReferences(memory.id))
                    text.append(task.title).append(" · ").append(task.status).append("\n");
                new AlertDialog.Builder(host).setTitle(T.s("memorydetailpanel.14", en))
                    .setMessage(text.length() == 0 ? (T.s("memorydetailpanel.15", en)) : text.toString())
                    .setPositiveButton(T.s("memorydetailpanel.16", en), null).show();
            } catch (Exception error) { host.failWith(error); }
        });
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(40));
        refLp.setMargins(0, host.dpRef(4), 0, 0);
        body.addView(references, refLp);

        new AlertDialog.Builder(host)
            .setTitle(T.s("memorydetailpanel.17", en))
            .setView(body)
            .setNegativeButton(T.s("memorydetailpanel.18", en), (dialog, which) -> confirmDelete(memory))
            .setPositiveButton(T.s("memorydetailpanel.19", en), null)
            .show();
    }


    private void confirmDelete(AgentStore.Memory memory) {
        boolean en = host.english();
        new AlertDialog.Builder(host)
            .setTitle(T.s("memorydetailpanel.20", en))
            .setMessage(en ? "A tombstone prevents old archives from restoring this record. Historical task snapshots and generated files retain their copies; delete those separately. You can review task references first."
                : "删除标记会随加密档案迁移，旧档案不能恢复这条记忆。历史任务快照和已生成文件中的副本仍保留，需要分别删除。可先查看任务引用。")
            .setNegativeButton(T.s("memorydetailpanel.21", en), null)
            .setPositiveButton(T.s("memorydetailpanel.22", en), (dialog, which) -> {
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
        new AlertDialog.Builder(host).setTitle(T.s("memorydetailpanel.23", en)).setView(panel)
            .setNegativeButton(T.s("memorydetailpanel.24", en), null)
            .setPositiveButton(T.s("memorydetailpanel.25", en), (dialog, which) -> {
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
        new AlertDialog.Builder(host).setTitle(T.s("memorydetailpanel.26", en))
            .setItems(labels, (dialog, which) -> {
                try {
                    java.util.ArrayList<AgentStore.Memory> selected = new java.util.ArrayList<>();
                    for (AgentStore.Memory memory : host.storeRef().listMemories()) {
                        if (!MemorySources.matches(memory, which)) continue;
                        selected.add(memory);
                    }
                    String message = (T.s("memorydetailpanel.27", en)) + selected.size() + (T.s("memorydetailpanel.28", en)) + labels[which]
                        + (en ? ". Workflow templates in this source are included. Existing task snapshots and files remain. Changes since this preview are rejected."
                            : " 的记录（包括此来源的工作流模板）。已有任务快照和文件仍保留。预览后发生变化的记录将拒绝删除。");
                    new AlertDialog.Builder(host).setTitle(T.s("memorydetailpanel.29", en)).setMessage(message)
                        .setNegativeButton(T.s("memorydetailpanel.30", en), null)
                        .setPositiveButton(T.s("memorydetailpanel.31", en), (confirm, button) -> {
                            try { host.storeRef().deleteMemoriesReviewed(selected); host.refreshAfterSync(); } catch (Exception error) { host.failWith(error); }
                        }).show();
                } catch (Exception error) { host.failWith(error); }
            }).setNegativeButton(T.s("memorydetailpanel.32", en), null).show();
    }

}
