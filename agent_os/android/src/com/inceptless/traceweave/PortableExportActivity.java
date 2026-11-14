// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.TextView;
import java.io.OutputStream;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Track B read-only exporter: builds a canonical v2 portable state from the frozen app database,
 * signs it with the production device identity (Android Keystore), and writes a TWPH1 handoff to
 * Downloads. No database schema or wire protocol changes; this build is not the 0.1.5 release.
 */
public final class PortableExportActivity extends Activity {
    private static final byte[] DOMAIN = "traceweave-portable-handoff-v1".getBytes(StandardCharsets.UTF_8);

    private TextView status;
    private android.widget.EditText passphraseInput;
    private android.widget.CheckBox strictSynthesis;
    private android.widget.Button exportButton;
    private android.widget.EditText policyName;
    private android.widget.EditText policyValue;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);
        TextView title = new TextView(this);
        title.setText("Sealed portable export (production key)");
        title.setTextSize(18);
        root.addView(title);
        passphraseInput = new android.widget.EditText(this);
        passphraseInput.setHint("Passphrase (12+ characters)");
        passphraseInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(passphraseInput);
        strictSynthesis = new android.widget.CheckBox(this);
        strictSynthesis.setText("Require a model grant on resume (strict synthesis)");
        strictSynthesis.setChecked(false);
        root.addView(strictSynthesis);
        policyName = new android.widget.EditText(this);
        policyName.setHint("Policy name (allow_model.*, allow_adapter.*, ...)");
        root.addView(policyName);
        policyValue = new android.widget.EditText(this);
        policyValue.setHint("Policy value (true or false)");
        root.addView(policyValue);
        android.widget.Button policyButton = new android.widget.Button(this);
        policyButton.setText("Save policy");
        root.addView(policyButton);
        policyButton.setOnClickListener(view -> {
            String name = policyName.getText().toString().trim();
            boolean value = Boolean.parseBoolean(policyValue.getText().toString().trim());
            boolean known = name.equals("require_signed_adapters") || name.equals("strict_synthesis")
                || name.startsWith("allow_model.") || name.startsWith("allow_adapter.")
                || name.startsWith("deny_adapter.");
            if (!known) {
                status.setText("FAIL: unknown policy name");
                return;
            }
            try (AgentStore store = new AgentStore(this)) {
                store.setPolicy(name, value);
                status.setText("PASS policy_saved name=" + name + " value=" + value);
            } catch (Exception error) {
                status.setText("FAIL policy_save " + error);
            }
        });
        exportButton = new android.widget.Button(this);
        exportButton.setText("Export sealed handoff");
        root.addView(exportButton);
        status = new TextView(this);
        root.addView(status);
        exportButton.setOnClickListener(view -> {
            String passphrase = passphraseInput.getText().toString();
            if (passphrase.length() < 12) {
                status.setText("FAIL: passphrase must be at least 12 characters");
                return;
            }
            exportButton.setEnabled(false);
            startExport(passphrase);
        });
        setContentView(root);
        if (getIntent().getBooleanExtra("schema_probe", false)) {
            TextView probeStatus = status;
            new Thread(() -> {
                String result = MigrationProbe.run(this);
                runOnUiThread(() -> probeStatus.setText(result));
            }).start();
        }
    }

    private void startExport(String passphrase) {
        TextView output = status;
        new Thread(() -> {
            String result;
            try {
                DeviceIdentity.create();
                long signedAt = System.currentTimeMillis();
                byte[] canonical;
                int memoryCount;
                int taskCount;
                try (AgentStore store = new AgentStore(this)) {
                    MemoryArchive.Data data = store.exportData();
                    TreeSet<String> memoryTombstones = new TreeSet<>();
                    TreeSet<String> taskTombstones = new TreeSet<>();
                    for (AgentStore.Tombstone tombstone : data.tombstones) {
                        if ("memory".equals(tombstone.entityType)) memoryTombstones.add(tombstone.entityId);
                        else if ("task".equals(tombstone.entityType)) taskTombstones.add(tombstone.entityId);
                    }
                    TreeMap<String, AgentStore.Memory> memories = new TreeMap<>();
                    for (AgentStore.Memory memory : data.memories) {
                        if (!memoryTombstones.contains(memory.id)) memories.put(memory.id, memory);
                    }
                    TreeMap<String, AgentStore.Task> tasks = new TreeMap<>();
                    for (AgentStore.Task task : data.tasks) {
                        if (!taskTombstones.contains(task.id)) tasks.put(task.id, task);
                    }
                    String synthetic = getIntent().getStringExtra("synthetic_memory");
                    if (synthetic != null && !synthetic.isEmpty()) {
                        long now = System.currentTimeMillis();
                        memories.put("synthetic-1", new AgentStore.Memory(
                            "synthetic-1", "fact", synthetic, "trackb-synthetic", "intent",
                            now, now));
                    }
                    memoryCount = memories.size();
                    taskCount = tasks.size();
                    canonical = canonicalState(memories, tasks, strictSynthesis.isChecked(),
                        store.exportPolicies());
                }
                byte[] message = ByteBuffer.allocate(DOMAIN.length + 8 + canonical.length)
                    .put(DOMAIN)
                    .putLong(signedAt)
                    .put(canonical)
                    .array();
                byte[] signature = DeviceIdentity.sign(message);
                byte[] spki = DeviceIdentity.publicKey();
                byte[] envelope;
                if (passphrase != null && !passphrase.isEmpty()) {
                    // TWPH3: sign the canonical state, then seal with Argon2id + AES-256-GCM.
                    envelope = Twph3Sealer.seal(canonical, spki, signature, signedAt, passphrase);
                } else {
                    envelope = ByteBuffer.allocate(5 + 8 + 4 + spki.length + 4 + canonical.length + 4 + signature.length)
                        .put("TWPH1".getBytes(StandardCharsets.UTF_8))
                        .putLong(signedAt)
                        .putInt(spki.length).put(spki)
                        .putInt(canonical.length).put(canonical)
                        .putInt(signature.length).put(signature)
                        .array();
                }
                String name = "traceweave-portable-" + signedAt + ".twph";
                if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Downloads export requires API 29");
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("could not create the Downloads item");
                try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                    stream.write(envelope);
                }
                result = "PASS portable_export mode=" + (passphrase == null || passphrase.isEmpty() ? "signed" : "sealed")
                    + " file=" + name
                    + " bytes=" + envelope.length
                    + " memories=" + memoryCount
                    + " tasks=" + taskCount
                    + " state_sha256=" + sha256(canonical)
                    + " signed_at=" + signedAt
                    + " fingerprint=" + DeviceIdentity.fingerprint();
                Log.i("AgentOsPortableExport", result);
            } catch (Throwable error) {
                result = "FAIL portable_export " + error;
                Log.e("AgentOsPortableExport", result);
            }
            String text = result;
            runOnUiThread(() -> output.setText(text));
        }).start();
    }

    private static byte[] canonicalState(TreeMap<String, AgentStore.Memory> memories,
            TreeMap<String, AgentStore.Task> tasks, boolean strictSynthesis,
            TreeMap<String, Boolean> storedPolicies) {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\"version\":5,\"memories\":{");
        boolean first = true;
        for (AgentStore.Memory memory : memories.values()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(escape(memory.id)).append("\":[{\"clock\":{\"android\":1},\"value\":\"")
                .append(escape(memory.content == null ? "" : memory.content)).append("\"}]");
        }
        json.append("},\"tasks\":{");
        first = true;
        for (AgentStore.Task task : tasks.values()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(escape(task.id)).append("\":{\"task_id\":\"").append(escape(task.id))
                .append("\",\"goal\":\"").append(escape(task.title == null ? "" : task.title))
                .append("\",\"status\":\"").append(portableStatus(task.status))
                .append("\",\"steps\":[");
            if (task.plan != null) {
                boolean firstStep = true;
                for (ExecutionPlan.Step step : task.plan.steps) {
                    if (!firstStep) json.append(',');
                    firstStep = false;
                    json.append("{\"index\":").append(step.index)
                        .append(",\"capability\":\"").append(escape(step.capability))
                        .append("\",\"scope\":\"").append(escape(step.scope))
                        .append("\",\"status\":\"").append(portableStepStatus(step.status))
                        .append("\",\"evidence\":");
                    if (step.observation == null || step.observation.isEmpty()) {
                        json.append("[]");
                    } else {
                        json.append("[\"").append(escape(step.observation)).append("\"]");
                    }
                    if (step.prompt != null && !step.prompt.isEmpty()) {
                        json.append(",\"payload\":\"").append(escape(step.prompt)).append("\"");
                    }
                    json.append('}');
                }
            }
            json.append("],\"effects\":{},\"updated_at\":").append(task.updatedAt)
                .append(",\"needs_authorization\":true}");
        }
        json.append("},\"grants\":[]");
        TreeMap<String, Boolean> policies = new TreeMap<>(storedPolicies);
        if (strictSynthesis) {
            policies.put("strict_synthesis", true);
        }
        if (!policies.isEmpty()) {
            json.append(",\"policies\":{");
            boolean firstPolicy = true;
            for (java.util.Map.Entry<String, Boolean> entry : policies.entrySet()) {
                if (!firstPolicy) json.append(',');
                firstPolicy = false;
                json.append('"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
            }
            json.append('}');
        }
        json.append(",\"audit\":{\"entries\":[]}}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String portableStepStatus(String status) {
        if ("done".equals(status)) return "completed";
        if ("failed".equals(status)) return "failed";
        if ("cancelled".equals(status)) return "skipped";
        if ("awaiting_grant".equals(status) || "handed_off".equals(status) || "outcome_unknown".equals(status))
            return "awaiting_approval";
        if ("running".equals(status)) return "ready";
        return "pending";
    }

    private static String portableStatus(String status) {
        if ("completed".equals(status)) return "completed";
        if ("cancelled".equals(status)) return "cancelled";
        if ("failed".equals(status)) return "failed";
        return "waiting_for_user";
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            switch (item) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (item < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) item));
                    else out.append(item);
            }
        }
        return out.toString();
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }
}
