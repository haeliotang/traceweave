package com.inceptless.traceweave;

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
    private final MemoryHost host;

    MemoryDetailPanel(MemoryHost host) {
        this.host = host;
    }

    private void showHistory(AgentStore.Memory memory, java.util.List<AgentStore.MemorySnapshot> snapshots) {
        boolean en = host.english();
        LinearLayout body = new LinearLayout(context());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(host.dpRef(16), host.dpRef(8), host.dpRef(16), 0);
        for (AgentStore.MemorySnapshot snapshot : snapshots) {
            LinearLayout row = new LinearLayout(context());
            row.setOrientation(LinearLayout.VERTICAL);
            TextView content = new TextView(context());
            content.setText(snapshot.content);
            content.setTextSize(12);
            content.setTextColor(Ui.text(context()));
            row.addView(content);
            TextView meta = Ui.meta(context(), DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(snapshot.recordedAt)));
            row.addView(meta);
            Button restore = new Button(context());
            restore.setText(T.s("memorydetailpanel.restore_version", en));
            restore.setAllCaps(false);
            restore.setTextSize(11);
            restore.setOnClickListener(view -> {
                try {
                    host.storeRef().restoreMemorySnapshot(memory.id, snapshot);
                    host.refreshAfterSync();
                    host.toast(T.s("memorydetailpanel.version_restored", en));
                } catch (Exception error) {
                    host.failWith(error);
                }
            });
            row.addView(restore);
            body.addView(row);
        }
        AlertDialog dialog = new AlertDialog.Builder(context())
            .setView(body)
            .setNegativeButton(T.s("common.close", en), null)
            .create();
        dialog.show();
        Ui.themeDialog(dialog, context());
    }

    private android.content.Context context() {
        // Both hosts are Activities; this used to call itself and overflow the stack the
        // moment a memory detail was opened.
        return (android.content.Context) host;
    }

    void showDetail(AgentStore.Memory memory) {
        boolean en = host.english();
        LinearLayout body = new LinearLayout(context());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(host.dpRef(16), host.dpRef(8), host.dpRef(16), 0);

        TextView content = new TextView(context());
        content.setText(memory.content);
        content.setTextSize(13);
        content.setTextColor(Ui.text(context()));
        content.setTextIsSelectable(true);
        body.addView(content);

        String source;
        if (AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(memory.source)) {
            source = memory.sourceReference.startsWith("confirmed:")
                ? (T.s("memorydetailpanel.1", en))
                : (T.s("memorydetailpanel.2", en));
        } else if (AgentStore.MEMORY_SOURCE_TRACEWEAVE.equals(memory.source)) {
            source = "Traceweave";
        } else {
            source = T.s("memorydetailpanel.3", en);
        }
        TextView meta = Ui.meta(context(), source + " · " + memory.kind);
        meta.setPadding(0, host.dpRef(4), 0, host.dpRef(6));
        body.addView(meta);

        TextView metadata = Ui.meta(context(), DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(new Date(memory.updatedAt)));
        if (memory.userEdited) metadata.append(T.s("memorydetailpanel.4", en));
        if (memory.expiresAt > 0) metadata.append((memory.isExpired(System.currentTimeMillis())
            ? (T.s("memorydetailpanel.5", en)) : (T.s("memorydetailpanel.6", en)))
            + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(memory.expiresAt)));
        body.addView(metadata);

        java.util.List<AgentStore.MemorySnapshot> history;
        try {
            history = host.storeRef().listMemoryHistory(memory.id);
        } catch (Exception error) {
            history = new java.util.ArrayList<>();
        }
        if (!history.isEmpty()) {
            Button previous = new Button(context());
            previous.setText(T.format("memorydetailpanel.previous_versions", en, history.size()));
            previous.setAllCaps(false);
            previous.setTextSize(12);
            final java.util.List<AgentStore.MemorySnapshot> snapshots = history;
            previous.setOnClickListener(view -> showHistory(memory, snapshots));
            LinearLayout.LayoutParams historyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(40));
            historyLp.setMargins(0, host.dpRef(6), 0, 0);
            body.addView(previous, historyLp);
        }

        Button edit = new Button(context());
        edit.setText(T.s("memorydetailpanel.7", en));
        edit.setAllCaps(false);
        edit.setTextSize(12);
        edit.setOnClickListener(view -> { edit(memory); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(40));
        lp.setMargins(0, host.dpRef(6), 0, 0);
        body.addView(edit, lp);

        if (AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(memory.source) && !memory.sourceReference.startsWith("confirmed:")) {
            Button review = new Button(context());
            review.setText(T.s("memorydetailpanel.8", en));
            review.setAllCaps(false);
            review.setTextSize(12);
            review.setOnClickListener(view -> new AlertDialog.Builder(context())
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

        Button references = new Button(context());
        references.setText(T.s("memorydetailpanel.13", en));
        references.setAllCaps(false);
        references.setTextSize(12);
        references.setOnClickListener(view -> {
            try {
                StringBuilder text = new StringBuilder();
                for (AgentStore.Task task : host.storeRef().memoryReferences(memory.id))
                    text.append(task.title).append(" · ").append(task.status).append("\n");
                new AlertDialog.Builder(context()).setTitle(T.s("memorydetailpanel.14", en))
                    .setMessage(text.length() == 0 ? (T.s("memorydetailpanel.15", en)) : text.toString())
                    .setPositiveButton(T.s("memorydetailpanel.16", en), null).show();
            } catch (Exception error) { host.failWith(error); }
        });
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(40));
        refLp.setMargins(0, host.dpRef(4), 0, 0);
        body.addView(references, refLp);

        TextView heading = Ui.title(context(), T.s("memorydetailpanel.17", en));
        heading.setTextSize(14);
        heading.setPadding(0, 0, 0, host.dpRef(4));
        body.addView(heading, 0);
        java.util.List<AgentStore.MemoryConflict> conflicts = host.storeRef().listMemoryConflicts(memory.id);
        if (!conflicts.isEmpty()) {
            Button resolve = new Button(context());
            resolve.setText(T.format("memory.conflicts_found", en, conflicts.size()));
            resolve.setAllCaps(false);
            resolve.setTextSize(12);
            resolve.setOnClickListener(view -> showConflictDialog(memory, conflicts, en));
            body.addView(resolve);
        }
        AlertDialog detail = new AlertDialog.Builder(context())
            .setView(body)
            .setNegativeButton(T.s("memorydetailpanel.18", en), (dialog, which) -> confirmDelete(memory))
            .setPositiveButton(T.s("memorydetailpanel.19", en), null)
            .create();
        detail.show();
        Ui.themeDialog(detail, context());
    }


    void showConflicts(AgentStore.Memory memory, boolean en) {
        java.util.List<AgentStore.MemoryConflict> conflicts = host.storeRef().listMemoryConflicts(memory.id);
        if (conflicts.isEmpty()) {
            host.toast(T.s("memory.no_pending_versions", en));
            return;
        }
        showConflictDialog(memory, conflicts, en);
    }

    private void showConflictDialog(AgentStore.Memory memory, java.util.List<AgentStore.MemoryConflict> conflicts, boolean en) {
        LinearLayout body = new LinearLayout(context());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(host.dpRef(16), host.dpRef(8), host.dpRef(16), 0);
        TextView heading = Ui.title(context(), T.s("memory.conflict_title", en));
        heading.setTextSize(14);
        body.addView(heading);
        for (AgentStore.MemoryConflict conflict : conflicts) {
            TextView item = new TextView(context());
            item.setText(conflict.content);
            item.setTextSize(12);
            item.setTextColor(Ui.text(context()));
            item.setPadding(0, host.dpRef(6), 0, 0);
            body.addView(item);
            Button use = new Button(context());
            use.setText(T.s("memory.use_this_version", en));
            use.setAllCaps(false);
            use.setTextSize(12);
            use.setOnClickListener(view -> {
                try {
                    host.storeRef().resolveMemoryConflict(memory.id, conflict.content, conflict.source,
                        conflict.reference, conflict.expiresAt);
                    host.refreshAfterSync();
                    host.toast(T.s("memory.conflict_resolved", en));
                } catch (Exception error) {
                    host.failWith(error);
                }
            });
            body.addView(use);
        }
        AlertDialog dialog = new AlertDialog.Builder(context())
            .setView(body)
            .setPositiveButton(T.s("common.close", en), null)
            .create();
        dialog.show();
        Ui.themeDialog(dialog, context());
    }

    private void confirmDelete(AgentStore.Memory memory) {
        boolean en = host.english();
        LinearLayout confirmBody = new LinearLayout(context());
        confirmBody.setOrientation(LinearLayout.VERTICAL);
        confirmBody.setPadding(host.dpRef(16), host.dpRef(8), host.dpRef(16), 0);
        TextView confirmTitle = Ui.title(context(), T.s("memorydetailpanel.20", en));
        confirmTitle.setTextSize(14);
        confirmBody.addView(confirmTitle);
        TextView confirmText = new TextView(context());
        confirmText.setText(T.s("memorydetail.a_tombstone_prevents_old_archives_from", en));
        confirmText.setTextSize(13);
        confirmText.setTextColor(Ui.text(context()));
        confirmText.setPadding(0, host.dpRef(4), 0, 0);
        confirmBody.addView(confirmText);
        AlertDialog confirm = new AlertDialog.Builder(context())
            .setView(confirmBody)
            .setNegativeButton(T.s("memorydetailpanel.21", en), null)
            .setPositiveButton(T.s("memorydetailpanel.22", en), (dialog, which) -> {
                try {
                    host.storeRef().deleteMemoriesReviewed(java.util.Collections.singletonList(memory));
                    host.refreshAfterSync();
                } catch (Exception error) {
                    host.failWith(error);
                }
            })
            .create();
        confirm.show();
        Ui.themeDialog(confirm, context());
    }


    private void edit(AgentStore.Memory memory) {
        boolean en = host.english();
        LinearLayout panel = new LinearLayout(context()); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(36, 12, 36, 0);
        EditText input = new EditText(context()); input.setText(memory.content); input.setMinLines(2);
        if (WorkflowTemplate.isTemplate(memory)) input.setEnabled(false);
        panel.addView(input);
        TextView help = new TextView(context());
        help.setText(T.s("memorydetail.expiration_excludes_this_memory_from_n", en));
        panel.addView(help);
        Spinner expiry = new Spinner(context()); expiry.setAdapter(new ArrayAdapter<>(context(), android.R.layout.simple_spinner_dropdown_item,
            new String[]{T.s("memory.keep_expiration", en), T.s("memory.no_expiration", en),
                T.s("memory.expire_7", en), T.s("memory.expire_30", en), T.s("memory.expire_90", en)}));
        panel.addView(expiry);
        new AlertDialog.Builder(context()).setTitle(T.s("memorydetailpanel.23", en)).setView(panel)
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
        String[] labels = new String[]{T.s("memory.source_user", en), T.s("memory.source_traceweave", en),
            T.s("memory.source_suggestions", en), T.s("memory.source_sensor", en)};
        new AlertDialog.Builder(context()).setTitle(T.s("memorydetailpanel.26", en))
            .setItems(labels, (dialog, which) -> {
                try {
                    java.util.ArrayList<AgentStore.Memory> selected = new java.util.ArrayList<>();
                    for (AgentStore.Memory memory : host.storeRef().listMemories()) {
                        if (!MemorySources.matches(memory, which)) continue;
                        selected.add(memory);
                    }
                    String message = (T.s("memorydetailpanel.27", en)) + selected.size() + (T.s("memorydetailpanel.28", en)) + labels[which]
                        + (T.s("memorydetail.workflow_templates_in_this_source_are", en));
                    new AlertDialog.Builder(context()).setTitle(T.s("memorydetailpanel.29", en)).setMessage(message)
                        .setNegativeButton(T.s("memorydetailpanel.30", en), null)
                        .setPositiveButton(T.s("memorydetailpanel.31", en), (confirm, button) -> {
                            try { host.storeRef().deleteMemoriesReviewed(selected); host.refreshAfterSync(); } catch (Exception error) { host.failWith(error); }
                        }).show();
                } catch (Exception error) { host.failWith(error); }
            }).setNegativeButton(T.s("memorydetailpanel.32", en), null).show();
    }

}
