package org.traceform.agentos;

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
        Button refresh = compact(T.s("audit.refresh", en), view -> render());
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

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, Ui.dp(this, 6), 0, 0);
        root.addView(rows);

        Button back = compact(T.s("audit.back", en), view -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 40));
        backLp.setMargins(0, Ui.dp(this, 8), 0, 0);
        root.addView(back, backLp);
        render();
    }

    private Button compact(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setOnClickListener(listener);
        return button;
    }

    private void render() {
        rows.removeAllViews();
        List<AgentStore.AuditEvent> events = store.listRecentAuditEvents(200);
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
            line.setText(event.event);
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
            StringBuilder out = new StringBuilder();
            for (AgentStore.AuditEvent event : store.listRecentAuditEvents(500)) {
                out.append(event.occurredAt).append('\t').append(event.taskId).append('\t')
                   .append(event.event.replace('\n', ' ')).append('\n');
            }
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/tab-separated-values")
                .putExtra(Intent.EXTRA_TITLE, "traceform-audit-" + System.currentTimeMillis() + ".tsv");
            pendingExport = out.toString();
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (Exception error) {
            android.widget.Toast.makeText(this, error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void exportDiagnostics() {
        try {
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
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "traceform-diagnostics-" + System.currentTimeMillis() + ".json");
            pendingExport = report.toString(2);
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (Exception error) {
            android.widget.Toast.makeText(this, error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
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
