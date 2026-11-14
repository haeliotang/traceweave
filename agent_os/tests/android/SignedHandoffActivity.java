// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Proof H v2: build a tiny canonical portable-substrate state, sign it with the device's
 * hardware P-256 key, and write a signed handoff envelope for the macOS reference node.
 *
 * Synthetic data only: the frozen 0.1.5 production app has no exporter, so this activity lives in
 * the validation package. It proves the device-key binding, not real user-data export.
 */
public final class SignedHandoffActivity extends Activity {
    private static final byte[] DOMAIN = "traceweave-portable-handoff-v1".getBytes(StandardCharsets.UTF_8);
    private static final String OUTPUT = "signed-handoff.bin";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView output = new TextView(this);
        setContentView(output);
        new Thread(() -> {
            String result;
            try {
                DeviceIdentity.create();
                long signedAt = System.currentTimeMillis();
                boolean archiveMode = "SIGN_ARCHIVE".equals(getIntent().getAction());
                boolean taskMode = "SEED_TASK_AND_SIGN".equals(getIntent().getAction());
                byte[] canonical = archiveMode ? archiveState() : taskMode ? taskState() : canonicalState();
                byte[] message = ByteBuffer.allocate(DOMAIN.length + 8 + canonical.length)
                    .put(DOMAIN)
                    .putLong(signedAt)
                    .put(canonical)
                    .array();
                byte[] signature = DeviceIdentity.sign(message);
                byte[] spki = DeviceIdentity.publicKey();
                byte[] envelope = ByteBuffer.allocate(5 + 8 + 4 + spki.length + 4 + canonical.length + 4 + signature.length)
                    .put("TWPH1".getBytes(StandardCharsets.UTF_8))
                    .putLong(signedAt)
                    .putInt(spki.length).put(spki)
                    .putInt(canonical.length).put(canonical)
                    .putInt(signature.length).put(signature)
                    .array();
                File file = new File(getFilesDir(), archiveMode ? "signed-archive-handoff.bin"
                    : taskMode ? "signed-task-handoff.bin" : OUTPUT);
                try (FileOutputStream stream = new FileOutputStream(file)) {
                    stream.write(envelope);
                }
                result = "PASS signed_handoff mode=" + (archiveMode ? "archive" : taskMode ? "task" : "synthetic")
                    + " bytes=" + envelope.length
                    + " state_sha256=" + sha256(canonical)
                    + " signed_at=" + signedAt
                    + " fingerprint=" + DeviceIdentity.fingerprint();
                Log.i("AgentOsSignedHandoff", result);
            } catch (Throwable error) {
                result = "FAIL signed_handoff " + error;
                Log.e("AgentOsSignedHandoff", result);
            }
            String text = result;
            runOnUiThread(() -> output.setText(text));
        }).start();
    }

    /**
     * Real-data mode: decrypt the archive the frozen 0.1.5 app exported, convert its memories,
     * memory tombstones and tasks into canonical v2 JSON, then sign it. The archive format is
     * read-only; the production app is untouched.
     */
    private byte[] archiveState() throws Exception {
        String path = getIntent().getStringExtra("archive_path");
        String name = getIntent().getStringExtra("archive_name");
        String passphrase = getIntent().getStringExtra("passphrase");
        if ((path == null && name == null) || passphrase == null)
            throw new IllegalArgumentException("archive_path/archive_name and passphrase are required");
        // Scoped storage: read from the app's own external files dir through Context, not /sdcard.
        File archive = name != null ? new File(getExternalFilesDir(null), name) : new File(path);
        MemoryArchive.Data data;
        try (FileInputStream stream = new FileInputStream(archive)) {
            data = MemoryArchive.decrypt(stream, passphrase.toCharArray());
        }

        TreeSet<String> memoryTombstones = new TreeSet<>();
        TreeSet<String> taskTombstones = new TreeSet<>();
        for (AgentStore.Tombstone tombstone : data.tombstones) {
            if ("memory".equals(tombstone.entityType)) memoryTombstones.add(tombstone.entityId);
            else if ("task".equals(tombstone.entityType)) taskTombstones.add(tombstone.entityId);
        }

        TreeMap<String, AgentStore.Memory> memories = new TreeMap<>();
        for (AgentStore.Memory memory : data.memories) memories.put(memory.id, memory);
        TreeMap<String, AgentStore.Task> tasks = new TreeMap<>();
        for (AgentStore.Task task : data.tasks) {
            if (!taskTombstones.contains(task.id)) tasks.put(task.id, task);
        }

        StringBuilder json = new StringBuilder(4096);
        json.append("{\"version\":4,\"memories\":{");
        boolean first = true;
        for (AgentStore.Memory memory : memories.values()) {
            if (memoryTombstones.contains(memory.id)) continue;
            if (memory.content != null && memory.content.length() > 32000)
                throw new IllegalArgumentException("memory content too large: " + memory.id);
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
                .append("\",\"steps\":[],\"effects\":{},\"updated_at\":").append(task.updatedAt)
                .append(",\"needs_authorization\":true}");
        }
        json.append("},\"grants\":[],\"audit\":{\"entries\":[]}}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String portableStatus(String status) {
        if ("completed".equals(status)) return "completed";
        if ("cancelled".equals(status)) return "cancelled";
        if ("failed".equals(status)) return "failed";
        return "waiting_for_user";
    }

    /** Minimal JSON string escaping; matches serde_json for the characters used here. */
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

    /**
     * Create a real task (with plan steps) in the validation package's own database and export it.
     * The task is synthetic test data, but it is created through the same AgentStore path the app
     * uses and read back through exportData().
     */
    private byte[] taskState() throws Exception {
        try (AgentStore store = new AgentStore(this)) {
            AgentStore.Task task = store.createTask("finish the trip checklist");
            java.util.ArrayList<ExecutionPlan.Step> steps = new java.util.ArrayList<>();
            steps.add(new ExecutionPlan.Step(0, "Write the checklist", "system.document.write_verified",
                "user_selected_document", "write the checklist", ExecutionPlan.STEP_PENDING, "", 0));
            steps.add(new ExecutionPlan.Step(1, "Review the checklist", "system.document.read",
                "user_selected_document", "read it back", ExecutionPlan.STEP_PENDING, "", 0));
            store.savePlan(task.id, new ExecutionPlan(task.id, task.title, steps,
                System.currentTimeMillis(), 0));
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
            for (AgentStore.Task item : data.tasks) {
                if (!taskTombstones.contains(item.id)) tasks.put(item.id, item);
            }
            return canonicalTaskState(memories, tasks);
        }
    }

    private static byte[] canonicalTaskState(TreeMap<String, AgentStore.Memory> memories,
            TreeMap<String, AgentStore.Task> tasks) {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\"version\":4,\"memories\":{");
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
                        .append("\",\"evidence\":[]}");
                }
            }
            json.append("],\"effects\":{},\"updated_at\":").append(task.updatedAt)
                .append(",\"needs_authorization\":true}");
        }
        json.append("},\"grants\":[],\"audit\":{\"entries\":[]}}");
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

    /** Compact canonical v2 JSON; field order must match the Rust serde layout exactly. */
    private static byte[] canonicalState() {
        StringBuilder json = new StringBuilder(512);
        json.append("{\"version\":4,\"memories\":{");
        json.append("\"mem-1\":[{\"clock\":{\"android\":1},\"value\":\"synthetic handoff memory\"}]");
        json.append("},\"tasks\":{");
        json.append("\"task-1\":{\"task_id\":\"task-1\",\"goal\":\"continue on the mac\",\"status\":\"waiting_for_user\",");
        json.append("\"steps\":[{\"index\":0,\"capability\":\"calendar\",\"scope\":\"write\",\"status\":\"pending\",\"evidence\":[]}],");
        json.append("\"effects\":{},\"updated_at\":0,\"needs_authorization\":true}");
        json.append("},\"grants\":[],\"audit\":{\"entries\":[]}}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }
}
