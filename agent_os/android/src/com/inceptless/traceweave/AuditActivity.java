package com.inceptless.traceweave;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Read-only audit log with an explicit export action. */
public final class AuditActivity extends Activity {
    private AgentStore store;
    private boolean en;
    private LinearLayout rows;
    private LinearLayout chipRow;
    private TextView integrityLine;
    private TextView countLine;
    private String category = "all";
    private String query = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AgentStore(this);
        en = "en".equals(getSharedPreferences("agent_os_prefs", 0).getString("app_lang", "zh"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.background(this));
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 40), Ui.dp(this, 12), Ui.dp(this, 12));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.background(this));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = Ui.title(this, T.s("audit.title", en));
        title.setTextSize(15);
        root.addView(title);
        TextView note = Ui.meta(this, T.s("audit.note", en));
        note.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        root.addView(note);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = compact(T.s("audit.refresh", en), view -> { render(); showIntegrity(); });
        Button export = compact(T.s("audit.export", en), view -> export());
        Button diagnosticsButton = compact(T.s("audit.diagnostics", en), view -> exportDiagnostics());
        actions.addView(refresh, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f));
        LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f);
        exportLp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        actions.addView(export, exportLp);
        root.addView(actions);
        LinearLayout.LayoutParams diagLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 40));
        diagLp.setMargins(0, Ui.dp(this, 4), 0, 0);
        root.addView(diagnosticsButton, diagLp);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, Ui.dp(this, 6), 0, 0);
        String[][] categories = {
            {"all", T.s("audit.filter_all", en)},
            {"task", T.s("audit.filter_task", en)},
            {"memory", T.s("audit.filter_memory", en)},
            {"sync", T.s("audit.filter_sync", en)},
        };
        for (String[] entry : categories) {
            Button chip = compact(entry[1], view -> { category = entry[0]; refreshChips(); render(); });
            chip.setTag(entry[0]);
            chip.setTextSize(11);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f);
            chipLp.setMargins(0, 0, Ui.dp(this, 4), 0);
            chips.addView(chip, chipLp);
        }
        chipRow = chips;
        root.addView(chips);

        android.widget.EditText search = new android.widget.EditText(this);
        search.setHint(T.s("audit.search_hint", en));
        search.setTextSize(13);
        search.setMinHeight(Ui.dp(this, 48));
        search.setBackgroundColor(Ui.secondaryBg(this));
        search.setTextColor(Ui.text(this));
        search.setHintTextColor(Ui.muted(this));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                query = text.toString().trim().toLowerCase(java.util.Locale.ROOT);
                render();
            }
            @Override public void afterTextChanged(android.text.Editable text) {}
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        searchLp.setMargins(0, Ui.dp(this, 6), 0, 0);
        root.addView(search, searchLp);

        countLine = Ui.meta(this, "");
        root.addView(countLine);

        integrityLine = new TextView(this);
        integrityLine.setTextSize(12);
        integrityLine.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));
        root.addView(integrityLine);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, Ui.dp(this, 6), 0, 0);
        root.addView(rows);

        Button back = compact(T.s("audit.back", en), view -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 40));
        backLp.setMargins(0, Ui.dp(this, 8), 0, 0);
        root.addView(back, backLp);
        refreshChips();
        render();
        showIntegrity();
    }

    private Button compact(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setOnClickListener(listener);
        return button;
    }

    private void showIntegrity() {
        if (integrityLine == null) return;
        try {
            java.util.Map<String, Object> report = store.auditIntegrity();
            boolean ok = Boolean.TRUE.equals(report.get("ok"));
            int checked = ((Number) report.get("checked")).intValue();
            int anchors = report.get("anchors") == null ? 0 : ((Number) report.get("anchors")).intValue();
            boolean macsValid = !Boolean.FALSE.equals(report.get("anchorMacsValid"));
            if (!ok) {
                integrityLine.setText(T.format("audit.integrity_broken", en,
                    ((Number) report.get("brokenAt")).intValue() + 1));
            } else if (anchors > 0 && macsValid) {
                integrityLine.setText(T.format("audit.integrity_anchored", en, checked, anchors));
            } else {
                integrityLine.setText(T.format("audit.integrity_ok", en, checked));
            }
            integrityLine.setTextColor(ok ? 0xFF15803D : 0xFFB91C1C);
        } catch (Exception error) {
            integrityLine.setText(T.s("audit.integrity_unknown", en));
            integrityLine.setTextColor(Ui.muted(this));
        }
    }

    private void refreshChips() {
        if (chipRow == null) return;
        for (int index = 0; index < chipRow.getChildCount(); index++) {
            View child = chipRow.getChildAt(index);
            if (!(child instanceof Button)) continue;
            Button chip = (Button) child;
            boolean selected = category.equals(chip.getTag());
            chip.setBackgroundColor(selected ? 0xFF334155 : Ui.secondaryBg(this));
            chip.setTextColor(selected ? 0xFFFFFFFF : Ui.secondaryFg(this));
        }
    }

    private boolean matches(AgentStore.AuditEvent event) {
        if (!"all".equals(category)) {
            String name = event.event == null ? "" : event.event;
            boolean taskCategory = name.startsWith("task_") || name.startsWith("plan_") || name.startsWith("status_")
                || name.startsWith("local_rule_");
            if ("task".equals(category) && !taskCategory) return false;
            if ("memory".equals(category) && !name.startsWith("memory_")) return false;
            if ("sync".equals(category) && !(name.startsWith("sync_") || name.startsWith("pair_"))) return false;
        }
        if (query == null || query.isEmpty()) return true;
        String haystack = (event.event == null ? "" : event.event)
            + " " + (event.taskId == null ? "" : event.taskId);
        return haystack.toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    private void render() {
        rows.removeAllViews();
        java.util.List<AgentStore.AuditEvent> events = new java.util.ArrayList<>();
        for (AgentStore.AuditEvent event : store.listRecentAuditEvents(500)) {
            if (matches(event)) events.add(event);
            if (events.size() >= 200) break;
        }
        if (countLine != null) countLine.setText(T.format("audit.count", en, events.size()));
        if (events.isEmpty()) {
            rows.addView(Ui.meta(this, T.s("audit.empty", en)));
            return;
        }
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (AgentStore.AuditEvent event : events) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            TextView line = new TextView(this);
            line.setText(AuditText.describe(event.event, en));
            line.setTextSize(12);
            line.setTextColor(Ui.text(this));
            row.addView(line);
            String task = event.taskId == null || event.taskId.isEmpty() ? "-"
                : (event.taskId.length() > 8 ? event.taskId.substring(0, 8) : event.taskId);
            TextView meta = Ui.meta(this, format.format(new Date(event.occurredAt)) + " · " + T.s("audit.task", en) + " " + task);
            row.addView(meta);
            View divider = new View(this);
            divider.setBackgroundColor(0xFFE2E8F0);
            row.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            rows.addView(row);
        }
    }

    private void export() {
        try {
            java.util.List<AgentStore.AuditEvent> events = store.listRecentAuditEvents(500);
            StringBuilder out = new StringBuilder();
            for (AgentStore.AuditEvent event : events) {
                out.append(event.occurredAt).append('\t').append(event.taskId).append('\t')
                   .append(event.event.replace('\n', ' ')).append('\n');
            }
            String range = events.isEmpty() ? "-" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new java.util.Date(events.get(events.size() - 1).occurredAt))
                + " → " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new java.util.Date(events.get(0).occurredAt));
            previewTsv(out.toString(), events.size(), range);
        } catch (Exception error) {
            android.widget.Toast.makeText(this, error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void previewTsv(String content, int count, String range) {
        String message = T.format("audit.tsv_scope", en, count, range);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
            .setView(Ui.dialogBody(this, T.s("audit.tsv_scope_title", en), message, 12))
            .setNegativeButton(T.s("common.cancel", en), null)
            .setPositiveButton(T.s("audit.choose_location", en), (inner, which) -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/tab-separated-values")
                    .putExtra(Intent.EXTRA_TITLE, "traceweave-audit-" + System.currentTimeMillis() + ".tsv");
                pendingExport = content;
                startActivityForResult(intent, REQUEST_EXPORT);
            })
            .create();
        dialog.show();
        Ui.themeDialog(dialog, this);
    }

    private void exportDiagnostics() {
        try {
            String json = buildDiagnostics().toString(2);
            previewDiagnostics(json);
        } catch (Exception error) {
            android.widget.Toast.makeText(this, error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void previewDiagnostics(String json) throws Exception {
        org.json.JSONObject report = new org.json.JSONObject(json);
        StringBuilder fields = new StringBuilder();
        java.util.Iterator<String> keys = report.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            fields.append(fields.length() == 0 ? "" : "、").append(key);
        }
        String crash = report.optJSONObject("last_crash") == null
            ? T.s("audit.no_crash", en) : report.getJSONObject("last_crash").optString("summary", "");
        String message = T.format("audit.diagnostics_fields", en, fields.toString())
            + "\n\n" + crash;
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
            .setView(Ui.dialogBody(this, T.s("audit.diagnostics_preview_title", en), message, 12))
            .setNegativeButton(T.s("common.cancel", en), null)
            .setPositiveButton(T.s("audit.choose_location", en), (inner, which) -> writeDiagnostics(json))
            .create();
        dialog.show();
        Ui.themeDialog(dialog, this);
    }

    private void writeDiagnostics(String json) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "traceweave-diagnostics-" + System.currentTimeMillis() + ".json");
        pendingExport = json;
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    private org.json.JSONObject buildDiagnostics() throws Exception {
        {
            org.json.JSONObject report = new org.json.JSONObject();
            report.put("app", getPackageName());
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            report.put("version_name", info.versionName == null ? "" : info.versionName);
            report.put("version_code", info.getLongVersionCode());
            report.put("android", android.os.Build.VERSION.RELEASE);
            report.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            report.put("tasks", store.listTasks().size());
            report.put("memories", store.listMemories().size());
            report.put("sync_runtime", SyncTransport.available());
            org.json.JSONArray events = new org.json.JSONArray();
            for (AgentStore.AuditEvent event : store.listRecentAuditEvents(20)) {
                events.put(new org.json.JSONObject().put("at", event.occurredAt).put("event", event.event));
            }
            report.put("recent_events", events);
            String crash = CrashLog.read(this);
            if (crash.isEmpty()) {
                report.put("last_crash", org.json.JSONObject.NULL);
            } else {
                report.put("last_crash", new org.json.JSONObject()
                    .put("summary", CrashLog.summarize(crash))
                    .put("raw", crash));
            }
            report.put("note", "No memory contents, task texts or device identifiers are included.");
            return report;
        }
    }

    private static final int REQUEST_EXPORT = 91;
    private String pendingExport;

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_EXPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        try (java.io.OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
            if (stream == null) throw new java.io.IOException("No output stream");
            stream.write(pendingExport.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            android.widget.Toast.makeText(this, T.s("audit.exported", en), android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            android.widget.Toast.makeText(this, error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onDestroy() {
        store.close();
        super.onDestroy();
    }
}
